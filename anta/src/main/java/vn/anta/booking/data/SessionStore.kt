package vn.anta.booking.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import vn.anta.booking.Logger
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A session as text, so it can be stored. Kept apart from the storage itself to be testable without a device. */
object SessionCodec {
    fun encode(session: Session): String = JSONObject()
        .put("id", session.user.id).put("identifier", session.user.identifier).put("displayName", session.user.displayName)
        .put("passwordLogin", session.user.passwordLogin)
        .put("accessToken", session.tokens.accessToken).put("refreshToken", session.tokens.refreshToken)
        .put("accessExpiresAt", session.tokens.accessExpiresAt.toEpochMilli())
        .toString()

    fun decode(text: String): Session? = try {
        val json = JSONObject(text)
        Session(
            User(json.getString("id"), json.getString("identifier"), json.getString("displayName"), json.optBoolean("passwordLogin", true)),
            Tokens(json.getString("accessToken"), json.getString("refreshToken"), Instant.ofEpochMilli(json.getLong("accessExpiresAt")))
        )
    } catch (_: JSONException) {
        null
    }
}

/** Where the current session is kept. The code that talks to the backend only needs this much of it. */
interface SessionHolder {
    val current: Session?

    /** Names this installation to the backend, which keeps one session per device. It identifies nothing else. */
    val deviceId: String

    /** Makes [session] the current one, durably, before returning. */
    suspend fun save(session: Session)

    fun clear()
}

/**
 * The signed-in user and their tokens. On disk the session is encrypted with a key that the Android Keystore
 * generates and never releases, so a copy of the app's files is useless on another device.
 */
class SessionStore(context: Context) : SessionHolder {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())

    val session: StateFlow<Session?> = state.asStateFlow()
    override val current: Session? get() = state.value

    override val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
    }

    /**
     * Makes [session] the current one and writes it to disk before returning. The write is not left for later:
     * after a renewal the copy on disk holds a refresh token the backend will never accept again.
     */
    override suspend fun save(session: Session) {
        state.value = session
        withContext(Dispatchers.IO) {
            val sealed = try {
                encrypt(SessionCodec.encode(session))
            } catch (error: GeneralSecurityException) {
                Logger.w("Session could not be stored", throwable = error)
                null
            } catch (error: ProviderException) {
                Logger.w("Session could not be stored", throwable = error)
                null
            }
            // A session that cannot be stored still works until the app closes. The stale copy goes either way, so
            // the next start finds no session rather than one that gets the account's sessions revoked.
            prefs.edit().apply { if (sealed != null) putString(KEY_SESSION, sealed) else remove(KEY_SESSION) }.commit()
        }
    }

    override fun clear() {
        state.value = null
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private fun load(): Session? {
        val stored = prefs.getString(KEY_SESSION, null) ?: return null
        return try {
            SessionCodec.decode(decrypt(stored))
        } catch (error: GeneralSecurityException) {
            Logger.w("Stored session could not be read", throwable = error)
            null
        } catch (error: ProviderException) {
            Logger.w("Stored session could not be read", throwable = error)
            null
        } catch (error: IllegalArgumentException) {
            Logger.w("Stored session is malformed", throwable = error)
            null
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Stored session is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "anta.session"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_SESSION = "session"
        const val KEY_DEVICE = "deviceId"
    }
}
