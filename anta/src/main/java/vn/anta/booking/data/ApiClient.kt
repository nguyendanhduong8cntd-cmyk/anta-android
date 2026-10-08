package vn.anta.booking.data

import java.io.IOException
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import vn.anta.booking.NetworkLogger

/**
 * Talks to the backend. A request that needs the user carries their access token; when the token is about to
 * expire, or the backend rejects it, the client renews the session once and repeats the request.
 */
class ApiClient(
    baseUrl: String,
    private val sessions: SessionHolder,
    private val http: OkHttpClient,
    private val clock: Clock = Clock.systemUTC()
) {
    private val base = baseUrl.trimEnd('/')
    private val refreshLock = Mutex()

    /** False when the build was not given a backend address. */
    val isConfigured: Boolean = base.startsWith("https://") || base.startsWith("http://")

    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), signedIn: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        call("GET", path, query, null, signedIn, headers)

    suspend fun post(path: String, body: JSONObject? = null, signedIn: Boolean = false, headers: Map<String, String> = emptyMap()): String =
        call("POST", path, emptyMap(), body ?: JSONObject(), signedIn, headers)

    /** A signed-in read with a token that is not the stored session's yet: the first request after signing in. */
    suspend fun getAs(accessToken: String, path: String): String =
        execute("GET", path, emptyMap(), null, mapOf("Authorization" to "Bearer $accessToken"))

    suspend fun patch(path: String, body: JSONObject): String = call("PATCH", path, emptyMap(), body, true, emptyMap())

    suspend fun delete(path: String, body: JSONObject? = null): String = call("DELETE", path, emptyMap(), body, true, emptyMap())

    private suspend fun call(method: String, path: String, query: Map<String, String?>, body: JSONObject?, signedIn: Boolean, headers: Map<String, String>): String {
        if (!signedIn) return execute(method, path, query, body, headers)
        val token = accessToken(rejected = null)
        return try {
            execute(method, path, query, body, headers + ("Authorization" to "Bearer $token"))
        } catch (error: ApiException) {
            // Only a refused token is worth a renewal. Other 401s (a wrong password on a confirmation) are the answer.
            if (error.code != TOKEN_REFUSED) throw error
            execute(method, path, query, body, headers + ("Authorization" to "Bearer ${accessToken(rejected = token)}"))
        }
    }

    /**
     * A usable access token. Renewals are serialised: the backend treats a refresh token used twice as stolen and
     * ends every session of the family, so two requests must never renew at the same time.
     */
    private suspend fun accessToken(rejected: String?): String = refreshLock.withLock {
        val session = sessions.current ?: throw ApiException(401, "UNAUTHENTICATED", "Not signed in")
        val expiring = !session.tokens.accessExpiresAt.isAfter(clock.instant().plus(EXPIRY_MARGIN))
        if (session.tokens.accessToken != rejected && !expiring) return session.tokens.accessToken
        // Once the request is sent the backend may have swapped the refresh token, so its reply has to be stored
        // even if the screen that asked has gone: the caller's cancellation must not cut the exchange short.
        withContext(NonCancellable) { renew(session) }
    }

    private suspend fun renew(session: Session): String {
        val renewed = try {
            val reply = execute("POST", "/public/auth/refresh", emptyMap(), JSONObject().put("refreshToken", session.tokens.refreshToken), emptyMap())
            try {
                ApiJson.tokens(reply, clock.instant())
            } catch (error: JSONException) {
                throw ApiException(502, "BAD_RESPONSE", error.message ?: "Unreadable response")
            }
        } catch (error: ApiException) {
            // The backend no longer honours this session. A failure to reach it at all leaves the session alone.
            if (error.isUnauthorized) sessions.clear()
            throw error
        }
        sessions.save(session.copy(tokens = renewed))
        return renewed.accessToken
    }

    private suspend fun execute(method: String, path: String, query: Map<String, String?>, body: JSONObject?, headers: Map<String, String>): String {
        if (!isConfigured) throw OfflineException(IOException("No backend address in this build"))
        val url = "$base$path".toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> if (value != null) addQueryParameter(name, value) }
        }.build()
        val payload = body?.toString()
        val request = Request.Builder().url(url).method(method, payload?.toRequestBody(JSON)).apply {
            header("Accept", "application/json")
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        NetworkLogger.request(method, url.toString(), headers, payload)
        val (status, text) = try {
            http.newCall(request).read()
        } catch (error: IOException) {
            throw OfflineException(error)
        }
        NetworkLogger.response(method, url.toString(), status, text)
        if (status !in 200..299) throw ApiJson.error(status, text)
        return text
    }

    private fun Response.limitedBody(): String {
        val source = body?.source() ?: return ""
        source.request(MAX_RESPONSE_BYTES + 1)
        if (source.buffer.size > MAX_RESPONSE_BYTES) throw IOException("Response is larger than $MAX_RESPONSE_BYTES bytes")
        return source.buffer.readUtf8()
    }

    /**
     * The reply's status and body. The body is read on OkHttp's own thread, inside the callback: the caller is
     * usually on the main thread, where waiting for the network is not allowed.
     */
    private suspend fun Call.read(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                val reply = try {
                    response.use { it.code to it.limitedBody() }
                } catch (error: IOException) {
                    return onFailure(call, error)
                }
                // Ignored when the caller has already gone.
                continuation.resume(reply)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        })
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /** The backend's code for a missing, expired or revoked access token. */
        const val TOKEN_REFUSED = "UNAUTHORIZED"
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(30)
        const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    }
}
