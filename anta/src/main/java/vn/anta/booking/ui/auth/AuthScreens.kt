package vn.anta.booking.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.R
import vn.anta.booking.data.ApiException
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Identifiers
import vn.anta.booking.data.Messages
import vn.anta.booking.data.OtpChallenge
import vn.anta.booking.data.OtpPurpose
import vn.anta.booking.ui.components.PrimaryButton
import vn.anta.booking.ui.components.appViewModel

// ---- Sign in ----

class LoginViewModel(private val app: AppContainer) : ViewModel() {
    var identifier by mutableStateOf("")
    var password by mutableStateOf("")
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    fun login() {
        if (working) return
        val account = Identifiers.normalize(identifier)
        error = when {
            account == null -> "Nhập email hoặc số điện thoại hợp lệ."
            password.isEmpty() -> "Nhập mật khẩu."
            else -> null
        }
        if (account == null || error != null) return
        working = true
        viewModelScope.launch {
            try {
                app.repository.login(account, password)
                password = ""
                done = true
            } catch (failure: BackendException) {
                error = Messages.of(failure)
            } finally {
                working = false
            }
        }
    }
}

@Composable
fun LoginScreen(onBack: () -> Unit, onDone: () -> Unit, onRegister: () -> Unit, onForgot: () -> Unit) {
    val model = appViewModel { LoginViewModel(it) }
    LaunchedEffect(model.done) { if (model.done) onDone() }

    AuthPage("Đăng nhập", "Chào mừng bạn quay lại. Đăng nhập để đặt sân và theo dõi lịch của bạn.", onBack) {
        IdentifierField(model.identifier, { model.identifier = it }, ImeAction.Next)
        PasswordField(model.password, { model.password = it }, "Mật khẩu", ImeAction.Done, onDone = model::login)
        ErrorText(model.error)
        PrimaryButton("Đăng nhập", model::login, Modifier.fillMaxWidth(), loading = model.working)
        TextButton(onClick = onForgot, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Quên mật khẩu?") }
        GoogleSignIn(onDone)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Chưa có tài khoản?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onRegister) { Text("Đăng ký ngay") }
        }
    }
}

// ---- Create an account, or set a new password: both start with a code sent to the user ----

enum class CodeStep { IDENTIFIER, CODE, DETAILS }

class CodeFlowViewModel(private val app: AppContainer, val purpose: OtpPurpose) : ViewModel() {
    var step by mutableStateOf(CodeStep.IDENTIFIER)
        private set
    var identifier by mutableStateOf("")
    var code by mutableStateOf("")
    var displayName by mutableStateOf("")
    var password by mutableStateOf("")
    var working by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var done by mutableStateOf(false)
        private set

    private var account: String? = null
    private var challenge: OtpChallenge? = null
    private var verification: String? = null

    /** A debug build next to a local backend can read the code itself: that backend has no way to send one. */
    val canFetchCode: Boolean get() = app.repository.hasDevelopmentKey

    fun sendCode() = act {
        val normalized = Identifiers.normalize(identifier) ?: return@act fail("Nhập email hoặc số điện thoại hợp lệ.")
        challenge = app.repository.sendCode(normalized, purpose)
        account = normalized
        code = ""
        step = CodeStep.CODE
    }

    fun fetchCode() = act {
        app.repository.developmentCode(challenge?.id ?: return@act)?.let { code = it }
    }

    fun verify() = act {
        val pending = challenge ?: return@act
        if (code.length != CODE_LENGTH) return@act fail("Nhập đủ $CODE_LENGTH chữ số của mã xác thực.")
        verification = app.repository.verifyCode(pending.id, code)
        step = CodeStep.DETAILS
    }

    fun finish() = act {
        val target = account ?: return@act
        val proof = verification ?: return@act
        if (purpose == OtpPurpose.REGISTER && displayName.isBlank()) return@act fail("Nhập tên hiển thị của bạn.")
        if (password.length < Identifiers.MIN_PASSWORD) return@act fail("Mật khẩu cần ít nhất ${Identifiers.MIN_PASSWORD} ký tự.")
        try {
            if (purpose == OtpPurpose.REGISTER) app.repository.register(target, displayName, password, proof) else app.repository.resetPassword(target, password, proof)
        } catch (failure: ApiException) {
            // The proof is spent by the attempt, whatever its outcome: a second try needs a new code.
            verification = null
            step = CodeStep.IDENTIFIER
            throw failure
        }
        password = ""
        done = true
    }

    /** Back to the first step to use another address or get a new code. */
    fun restart() {
        step = CodeStep.IDENTIFIER
        error = null
    }

    private fun fail(message: String) {
        error = message
    }

    private fun act(work: suspend () -> Unit) {
        if (working) return
        working = true
        error = null
        viewModelScope.launch {
            try {
                work()
            } catch (failure: BackendException) {
                error = if (failure is ApiException && failure.code == "CONFLICT") "Tài khoản này đã tồn tại. Hãy đăng nhập." else Messages.of(failure)
            } finally {
                working = false
            }
        }
    }

    companion object {
        const val CODE_LENGTH = 6
    }
}

@Composable
fun CodeFlowScreen(purpose: OtpPurpose, onBack: () -> Unit, onDone: () -> Unit) {
    val model = appViewModel(key = purpose.name) { CodeFlowViewModel(it, purpose) }
    LaunchedEffect(model.done) { if (model.done) onDone() }
    val registering = purpose == OtpPurpose.REGISTER
    // The system back button steps back through the form before it leaves it.
    BackHandler(enabled = model.step != CodeStep.IDENTIFIER, onBack = model::restart)
    val title = if (registering) "Tạo tài khoản" else "Đặt lại mật khẩu"

    when (model.step) {
        CodeStep.IDENTIFIER -> AuthPage(title, if (registering) "Nhập email hoặc số điện thoại. Chúng tôi sẽ gửi mã để xác nhận đó là bạn." else "Nhập email hoặc số điện thoại của tài khoản để nhận mã xác thực.", onBack, step = 1) {
            IdentifierField(model.identifier, { model.identifier = it }, ImeAction.Done, onDone = model::sendCode)
            ErrorText(model.error)
            PrimaryButton("Gửi mã xác thực", model::sendCode, Modifier.fillMaxWidth(), loading = model.working)
            // A Google account needs no code and no password: signing in with one creates the Anta account.
            if (registering) GoogleSignIn(onDone)
        }
        CodeStep.CODE -> AuthPage(title, "Nhập mã ${CodeFlowViewModel.CODE_LENGTH} chữ số vừa gửi tới ${model.identifier.trim()}. Mã có hiệu lực 5 phút.", model::restart, step = 2) {
            OutlinedTextField(
                value = model.code,
                onValueChange = { text -> model.code = text.filter(Char::isDigit).take(CodeFlowViewModel.CODE_LENGTH) },
                label = { Text("Mã xác thực") },
                singleLine = true,
                shape = FIELD_SHAPE,
                textStyle = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 10.sp, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { model.verify() }),
                modifier = Modifier.fillMaxWidth()
            )
            ErrorText(model.error)
            PrimaryButton("Xác nhận", model::verify, Modifier.fillMaxWidth(), loading = model.working)
            if (model.canFetchCode) TextButton(onClick = model::fetchCode, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Lấy mã từ máy chủ thử nghiệm") }
            TextButton(onClick = model::restart, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Dùng địa chỉ khác hoặc gửi lại mã") }
        }
        CodeStep.DETAILS -> AuthPage(title, if (registering) "Bước cuối: chọn tên hiển thị và mật khẩu." else "Chọn mật khẩu mới cho tài khoản.", model::restart, step = 3) {
            if (registering) {
                OutlinedTextField(
                    value = model.displayName, onValueChange = { model.displayName = it.take(MAX_NAME) }, label = { Text("Tên hiển thị") }, singleLine = true, shape = FIELD_SHAPE,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth()
                )
            }
            PasswordField(model.password, { model.password = it }, if (registering) "Mật khẩu" else "Mật khẩu mới", ImeAction.Done, onDone = model::finish, hint = "Ít nhất ${Identifiers.MIN_PASSWORD} ký tự")
            ErrorText(model.error)
            PrimaryButton(if (registering) "Hoàn tất đăng ký" else "Lưu mật khẩu mới", model::finish, Modifier.fillMaxWidth(), loading = model.working)
        }
    }
}

// ---- Shared pieces ----

@Composable
private fun AuthPage(title: String, subtitle: String, onBack: () -> Unit, step: Int? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        IconButton(onClick = onBack, modifier = Modifier.padding(4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)))
            Spacer(Modifier.height(2.dp))
            if (step != null) Text("Bước $step/3", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.headlineLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun IdentifierField(value: String, onChange: (String) -> Unit, action: ImeAction, onDone: () -> Unit = {}) {
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(MAX_IDENTIFIER)) }, label = { Text("Email hoặc số điện thoại") }, singleLine = true, shape = FIELD_SHAPE,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = action), keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, action: ImeAction, onDone: () -> Unit, hint: String? = null) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = { onChange(it.take(Identifiers.MAX_PASSWORD)) }, label = { Text(label) }, singleLine = true, shape = FIELD_SHAPE,
        supportingText = hint?.let { { Text(it) } },
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }) {
                Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = if (shown) "Ẩn mật khẩu" else "Hiện mật khẩu")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = action), keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
}

private val FIELD_SHAPE = RoundedCornerShape(16.dp)
private const val MAX_IDENTIFIER = 254
private const val MAX_NAME = 100
