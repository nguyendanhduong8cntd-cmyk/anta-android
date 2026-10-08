package vn.anta.booking.ui.auth

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.Logger
import vn.anta.booking.R
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Messages
import vn.anta.booking.ui.components.appViewModel

/** What came of asking the phone for a Google account. */
sealed interface GoogleProof {
    /** A short-lived statement, signed by Google, of who the user is. Only this app's backend accepts it. */
    class Token(val idToken: String) : GoogleProof

    /** The user closed the account picker: there is nothing to report. */
    data object Cancelled : GoogleProof

    class Failed(val message: String) : GoogleProof
}

object GoogleAccount {
    private const val UNAVAILABLE = "Chưa đăng nhập được bằng Google. Vui lòng thử lại."
    private const val NO_ACCOUNT = "Thiết bị chưa có tài khoản Google. Hãy thêm tài khoản trong Cài đặt rồi thử lại."

    /**
     * Shows Google's account picker over [activity] and returns proof of the account the user chose.
     * [serverClientId] names the backend the proof is addressed to.
     */
    suspend fun proof(activity: Context, serverClientId: String): GoogleProof {
        val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(serverClientId).build()).build()
        return try {
            val credential = CredentialManager.create(activity).getCredential(activity, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleProof.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                GoogleProof.Failed(UNAVAILABLE)
            }
        } catch (_: GetCredentialCancellationException) {
            GoogleProof.Cancelled
        } catch (_: NoCredentialException) {
            GoogleProof.Failed(NO_ACCOUNT)
        } catch (error: GetCredentialException) {
            Logger.w("Google sign-in failed: ${error.type}")
            GoogleProof.Failed(UNAVAILABLE)
        } catch (error: GoogleIdTokenParsingException) {
            Logger.w("Google sign-in returned an unreadable credential", throwable = error)
            GoogleProof.Failed(UNAVAILABLE)
        }
    }
}

class GoogleSignInViewModel(private val app: AppContainer) : ViewModel() {
    /** The backend's Google client. Null until the backend has been asked, and for good when it does not offer Google sign-in. */
    var clientId by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    /** Asks the backend whether it offers Google sign-in. Asked again each time the form opens, until it has answered. */
    fun load() {
        if (clientId != null) return
        viewModelScope.launch { clientId = app.config.get()?.googleClientId }
    }

    /** Signs in with what the account picker returned. */
    fun accept(proof: GoogleProof) {
        error = (proof as? GoogleProof.Failed)?.message
        if (proof !is GoogleProof.Token || working) return
        working = true
        viewModelScope.launch {
            try {
                app.repository.googleLogin(proof.idToken)
                done = true
            } catch (failure: BackendException) {
                error = Messages.of(failure)
            } finally {
                working = false
            }
        }
    }
}

/** "or continue with Google" under a sign-in form. Takes no room when the backend does not offer Google sign-in. */
@Composable
fun ColumnScope.GoogleSignIn(onDone: () -> Unit) {
    val model = appViewModel { GoogleSignInViewModel(it) }
    val activity = LocalActivity.current
    val clientId = model.clientId
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.done) { if (model.done) onDone() }
    if (clientId == null || activity == null) return

    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text("hoặc", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
    GoogleButton("Tiếp tục với Google", loading = picking || model.working, modifier = Modifier.fillMaxWidth()) {
        // A second tap can arrive before the button is redrawn as busy.
        if (picking) return@GoogleButton
        picking = true
        scope.launch {
            try {
                model.accept(GoogleAccount.proof(activity, clientId))
            } finally {
                picking = false
            }
        }
    }
    model.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

/** Google's own look for its sign-in button: a plain surface, the "G" and a neutral label. */
@Composable
fun GoogleButton(text: String, loading: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = !loading, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier.heightIn(min = 52.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp) else Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_google), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
