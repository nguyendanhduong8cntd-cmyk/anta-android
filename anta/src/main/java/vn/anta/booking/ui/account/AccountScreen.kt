package vn.anta.booking.ui.account

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.BuildConfig
import vn.anta.booking.R
import vn.anta.booking.data.AccountProof
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Document
import vn.anta.booking.data.Identifiers
import vn.anta.booking.data.Messages
import vn.anta.booking.data.User
import vn.anta.booking.ui.auth.GoogleAccount
import vn.anta.booking.ui.auth.GoogleProof
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.HeaderBand
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StatusBarScrim
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.theme.Brand

class AccountViewModel(private val app: AppContainer) : ViewModel() {
    val session = app.sessions.session

    var feedbackEmail by mutableStateOf<String?>(null)
        private set

    /** The backend's Google client, which an account without a password confirms its deletion with. */
    var googleClientId by mutableStateOf<String?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    init {
        loadConfig()
    }

    /** Asks the backend about itself. Asked again when it matters, in case the first try found no network. */
    fun loadConfig() {
        viewModelScope.launch {
            val config = app.config.get() ?: return@launch
            feedbackEmail = config.feedbackEmail
            googleClientId = config.googleClientId
        }
    }

    fun rename(name: String) = act("Đã đổi tên hiển thị.") { app.repository.rename(name) }

    fun logout() = act(null) { app.repository.logout() }

    fun deleteAccount(proof: AccountProof) = act("Đã gửi yêu cầu xoá tài khoản. Dữ liệu cá nhân sẽ được xoá sau thời gian chờ.") { app.repository.deleteAccount(proof) }

    /** Deletes the account once Google has confirmed, again, who is asking. */
    fun deleteAccount(proof: GoogleProof) {
        when (proof) {
            is GoogleProof.Token -> deleteAccount(AccountProof.Google(proof.idToken))
            is GoogleProof.Failed -> notice = proof.message
            GoogleProof.Cancelled -> Unit
        }
    }

    fun noticeShown() {
        notice = null
    }

    private fun act(success: String?, work: suspend () -> Unit) {
        if (working) return
        working = true
        viewModelScope.launch {
            notice = try {
                work()
                success
            } catch (error: BackendException) {
                Messages.of(error)
            } finally {
                working = false
            }
        }
    }
}

private enum class AccountDialog { RENAME, LOGOUT, DELETE }

@Composable
fun AccountScreen(contentPadding: PaddingValues, onLogin: () -> Unit, onRegister: () -> Unit, onBookings: () -> Unit, onFavorites: () -> Unit, onTerms: () -> Unit) {
    val model = appViewModel { AccountViewModel(it) }
    val session by model.session.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    var dialog by rememberSaveable { mutableStateOf<AccountDialog?>(null) }
    LaunchedEffect(model.notice) { model.notice?.let { snackbar.showSnackbar(it); model.noticeShown() } }

    val scroll = rememberScrollState()
    var headerHeight by remember { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val user = session?.user
            Box(Modifier.onSizeChanged { headerHeight = it.height }) { if (user == null) Welcome(onLogin, onRegister) else Profile(user) }

            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (user != null) MenuGroup {
                    MenuItem(Icons.Rounded.EventAvailable, "Lịch đặt của tôi", onClick = onBookings)
                    MenuDivider()
                    MenuItem(Icons.Rounded.FavoriteBorder, "Sân yêu thích", onClick = onFavorites)
                    MenuDivider()
                    MenuItem(Icons.Rounded.Edit, "Đổi tên hiển thị") { dialog = AccountDialog.RENAME }
                }
                MenuGroup {
                    MenuItem(Icons.Rounded.Description, "Điều khoản sử dụng", onClick = onTerms)
                    model.feedbackEmail?.let { email ->
                        MenuDivider()
                        MenuItem(Icons.Rounded.MailOutline, "Góp ý cho Anta") {
                            try {
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).putExtra(Intent.EXTRA_SUBJECT, "Góp ý cho Anta"))
                            } catch (_: ActivityNotFoundException) {
                                // No mail app on this phone: nothing to open.
                            }
                        }
                    }
                }
                if (user != null) MenuGroup {
                    MenuItem(Icons.AutoMirrored.Rounded.Logout, "Đăng xuất") { dialog = AccountDialog.LOGOUT }
                    MenuDivider()
                    MenuItem(Icons.Rounded.DeleteOutline, "Xoá tài khoản", tint = MaterialTheme.colorScheme.error) { dialog = AccountDialog.DELETE }
                }
                Text("Anta ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        StatusBarScrim(headerHeight > 0 && scroll.value >= headerHeight)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    when (dialog) {
        AccountDialog.RENAME -> session?.user?.let { user ->
            TextDialog("Đổi tên hiển thị", "Tên hiển thị", user.displayName, "Lưu", secret = false, onDismiss = { dialog = null }) { model.rename(it); dialog = null }
        }
        AccountDialog.LOGOUT -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Đăng xuất?") },
            text = { Text("Bạn cần đăng nhập lại để đặt sân và xem lịch đặt.") },
            confirmButton = { TextButton(onClick = { dialog = null; model.logout() }) { Text("Đăng xuất") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Ở lại") } }
        )
        AccountDialog.DELETE -> if (session?.user?.passwordLogin != false) TextDialog(
            "Xoá tài khoản?", "Mật khẩu", "", "Xoá tài khoản", secret = true,
            message = "Tài khoản sẽ bị khoá ngay và dữ liệu cá nhân được xoá sau thời gian chờ. Nhập mật khẩu để xác nhận.",
            onDismiss = { dialog = null }
        ) { model.deleteAccount(AccountProof.Password(it)); dialog = null } else {
            // An account that signs in with Google has no password: the user picks their Google account once more instead.
            val clientId = model.googleClientId
            LaunchedEffect(Unit) { model.loadConfig() }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Xoá tài khoản?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Tài khoản sẽ bị khoá ngay và dữ liệu cá nhân được xoá sau thời gian chờ. Chọn lại tài khoản Google của bạn để xác nhận.")
                        if (clientId == null) Text("Chưa kết nối được máy chủ để xác nhận bằng Google. Kiểm tra mạng rồi mở lại mục này.", color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = {
                    TextButton(enabled = clientId != null && activity != null, onClick = {
                        dialog = null
                        if (clientId != null && activity != null) scope.launch { model.deleteAccount(GoogleAccount.proof(activity, clientId)) }
                    }) { Text("Xác nhận bằng Google", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Huỷ") } }
            )
        }
        null -> Unit
    }
}

@Composable
private fun Welcome(onLogin: () -> Unit, onRegister: () -> Unit) {
    HeaderBand {
        Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.app_logo), contentDescription = null, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(24.dp)))
            Spacer(Modifier.height(16.dp))
            Text("Chào mừng đến với Anta", style = MaterialTheme.typography.headlineSmall, color = Color.White, textAlign = TextAlign.Center)
            Text("Đăng nhập để đặt sân và theo dõi lịch chơi của bạn.", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(20.dp))
            Surface(onClick = onLogin, shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("Đăng nhập", style = MaterialTheme.typography.titleMedium, color = Brand.Blue) }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRegister, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.5.dp, Color.White), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Tạo tài khoản", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
        }
    }
}

@Composable
private fun Profile(user: User) {
    HeaderBand {
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                Text(user.displayName.trim().take(1).uppercase().ifEmpty { "A" }, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(user.displayName, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(user.identifier, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) { Column { content() } }
}

@Composable
private fun MenuDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))

@Composable
private fun MenuItem(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.primary, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dialog that asks for one line of text: a new name, or the password that confirms a deletion. */
@Composable
private fun TextDialog(title: String, label: String, initial: String, confirm: String, secret: Boolean, message: String? = null, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    // A password is deliberately not kept across a rotation; a half-typed name is.
    val start = TextFieldValue(initial, TextRange(initial.length))
    var field by if (secret) remember { mutableStateOf(start) } else rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(start) }
    val limit = if (secret) Identifiers.MAX_PASSWORD else MAX_NAME
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (message != null) Text(message)
                OutlinedTextField(
                    value = field, onValueChange = { if (it.text.length <= limit) field = it }, label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(field.text) }, enabled = field.text.isNotBlank()) { Text(confirm, color = if (secret) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Huỷ") } }
    )
}

// ---- A document from the backend, such as the terms of use ----

class DocumentViewModel(private val app: AppContainer, private val slug: String) : ViewModel() {
    var document by mutableStateOf<Load<Document>>(Load.Loading)
        private set

    init {
        load()
    }

    fun load() {
        document = Load.Loading
        viewModelScope.launch {
            document = try {
                Load.Ready(app.repository.document(slug))
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(slug: String, fallbackTitle: String, onBack: () -> Unit) {
    val model = appViewModel(key = slug) { DocumentViewModel(it, slug) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text((model.document as? Load.Ready)?.value?.title ?: fallbackTitle, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        when (val state = model.document) {
            Load.Loading -> SkeletonRows(3, Modifier.padding(padding).padding(top = 16.dp))
            is Load.Failed -> FailedState(state.message, model::load, Modifier.padding(padding))
            is Load.Ready -> Text(
                state.value.body, style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

private const val MAX_NAME = 100
