package vn.anta.booking.ui.booking

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.AppConfig
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Booking
import vn.anta.booking.data.BookingStatus
import vn.anta.booking.data.Formats
import vn.anta.booking.data.Messages
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.Pill
import vn.anta.booking.ui.components.PrimaryButton
import vn.anta.booking.ui.components.RemoteImage
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StateMessage
import vn.anta.booking.ui.components.TabHeader
import vn.anta.booking.ui.components.appViewModel

// ---- One booking: pay for it, follow it, cancel it ----

class BookingViewModel(private val app: AppContainer, private val bookingId: String) : ViewModel() {
    var booking by mutableStateOf<Load<Booking>>(Load.Loading)
        private set
    var config by mutableStateOf<AppConfig?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    /** A payment page to open in the browser; cleared when opened. */
    var checkoutUrl by mutableStateOf<String?>(null)
        private set

    /** Payment can be finished inside the app only against a local sandbox backend, in a debug build. */
    val canPayInSandbox: Boolean get() = config?.sandboxPayment == true && app.repository.hasDevelopmentKey

    init {
        load()
        viewModelScope.launch {
            config = try {
                app.repository.config()
            } catch (_: BackendException) {
                null
            }
        }
    }

    fun load() {
        if (booking !is Load.Ready) booking = Load.Loading
        viewModelScope.launch {
            booking = try {
                Load.Ready(app.repository.booking(bookingId))
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }

    /** Looks again without disturbing the screen: used while a payment is on its way. */
    suspend fun check() {
        try {
            booking = Load.Ready(app.repository.booking(bookingId))
        } catch (_: BackendException) {
            // The next check tries again.
        }
    }

    fun pay() = act {
        val intent = app.repository.paymentIntent(bookingId)
        when {
            intent.checkoutUrl != null -> checkoutUrl = intent.checkoutUrl
            intent.sandbox && app.repository.hasDevelopmentKey -> {
                if (!app.repository.developmentPay(intent.id)) notice = "Thanh toán đến sau hạn giữ chỗ nên lịch không được xác nhận."
            }
            else -> notice = "Máy chủ đang ở chế độ thử nghiệm nên chưa nhận thanh toán."
        }
        check()
    }

    fun cancel() = act {
        app.repository.cancel(bookingId)
        check()
    }

    fun noticeShown() {
        notice = null
    }

    fun checkoutOpened() {
        checkoutUrl = null
    }

    private fun act(work: suspend () -> Unit) {
        if (working) return
        working = true
        viewModelScope.launch {
            try {
                work()
            } catch (error: BackendException) {
                notice = Messages.of(error)
                check()
            } finally {
                working = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingScreen(bookingId: String, onBack: () -> Unit, onVenue: (String) -> Unit) {
    val model = appViewModel { BookingViewModel(it, bookingId) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(model.notice) { model.notice?.let { snackbar.showSnackbar(it); model.noticeShown() } }
    LaunchedEffect(model.checkoutUrl) {
        model.checkoutUrl?.let { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                snackbar.showSnackbar("Không mở được trang thanh toán trên máy này.")
            }
            model.checkoutOpened()
        }
    }
    val now by produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }
    val current = (model.booking as? Load.Ready)?.value
    // While payment is awaited the backend is asked every few seconds, so the screen turns to "confirmed" on its
    // own. It is the backend's status that counts, until a little after the deadline: a payment can land late.
    val awaited = current?.status == BookingStatus.PENDING_PAYMENT && current.paymentExpiresAt?.plusSeconds(POLL_GRACE_SECONDS)?.isAfter(now) != false
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(awaited, lifecycle) {
        if (!awaited) return@LaunchedEffect
        // Not while the app is in the background, as when the user is on the payment page.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                model.check()
                delay(POLL_MS)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lịch đặt", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        when (val state = model.booking) {
            Load.Loading -> SkeletonRows(3, Modifier.padding(padding).padding(top = 16.dp))
            is Load.Failed -> FailedState(state.message, model::load, Modifier.padding(padding))
            is Load.Ready -> BookingContent(state.value, now, model, Modifier.padding(padding), onVenue)
        }
    }
}

@Composable
private fun BookingContent(booking: Booking, now: Instant, model: BookingViewModel, modifier: Modifier, onVenue: (String) -> Unit) {
    val status = booking.statusAt(now)
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusBanner(status, booking, now)

        Surface(onClick = { onVenue(booking.branchId) }, shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(booking.branchImage, Modifier.size(72.dp).clip(MaterialTheme.shapes.medium))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(booking.branchName.ifBlank { "Sân thể thao" }, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (booking.branchAddress.isNotBlank()) Text(booking.branchAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (booking.yardName.isNotBlank()) Fact("Sân", booking.yardName)
                    Fact("Ngày", Formats.fullDate(booking.startsAt, booking.zone))
                    Fact("Giờ", "${Formats.timeRange(booking.startsAt, booking.endsAt, booking.zone)} (${Duration.between(booking.startsAt, booking.endsAt).toHours()} giờ)")
                    Fact("Mã đặt lịch", booking.id.take(8).uppercase())
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tổng tiền", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(Formats.price(booking.amount), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        if (status == BookingStatus.PENDING_PAYMENT) {
            PrimaryButton(if (model.canPayInSandbox) "Thanh toán thử nghiệm" else "Thanh toán", model::pay, Modifier.fillMaxWidth(), loading = model.working)
            if (model.config?.sandboxPayment == true) {
                Text("Máy chủ đang chạy chế độ thử nghiệm: không có khoản tiền thật nào bị trừ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
        val cancellable = status == BookingStatus.PENDING_PAYMENT || (status == BookingStatus.CONFIRMED && booking.startsAt.isAfter(now))
        if (cancellable) {
            OutlinedButton(onClick = { confirmCancel = true }, enabled = !model.working, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Huỷ lịch đặt") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Huỷ lịch đặt này?") },
            text = { Text(if (status == BookingStatus.CONFIRMED) "Khoản đã thanh toán được hoàn theo chính sách huỷ. Sát giờ chơi có thể không huỷ được." else "Khung giờ sẽ được trả lại cho người khác đặt.") },
            confirmButton = { TextButton(onClick = { confirmCancel = false; model.cancel() }) { Text("Huỷ lịch", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Giữ lại") } }
        )
    }
}

@Composable
private fun StatusBanner(status: BookingStatus, booking: Booking, now: Instant) {
    val colors = MaterialTheme.colorScheme
    val (emoji, title, detail) = when (status) {
        BookingStatus.PENDING_PAYMENT -> Triple("⏳", "Chờ thanh toán", booking.paymentExpiresAt?.let { "Giữ chỗ còn ${Formats.countdown(Duration.between(now, it))}" } ?: "Thanh toán để giữ khung giờ này.")
        BookingStatus.CONFIRMED -> Triple("✅", "Đặt lịch thành công", if (booking.endsAt.isAfter(now)) "Hẹn gặp bạn ở sân!" else "Lịch này đã diễn ra.")
        BookingStatus.CANCELLED -> Triple("🚫", "Đã huỷ", "Lịch đặt này đã được huỷ.")
        BookingStatus.EXPIRED -> Triple("⌛", "Hết hạn thanh toán", "Khung giờ đã được trả lại. Bạn có thể đặt lại.")
        BookingStatus.UNKNOWN -> Triple("ℹ️", "Đang cập nhật", "Trạng thái lịch đặt chưa rõ.")
    }
    val background = when (status) {
        BookingStatus.CONFIRMED -> colors.secondaryContainer
        BookingStatus.PENDING_PAYMENT -> colors.tertiaryContainer
        else -> colors.surfaceContainerHigh
    }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(background).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(colors.surface.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 24.sp) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

// ---- All of the user's bookings ----

class BookingsViewModel(private val app: AppContainer) : ViewModel() {
    val session = app.sessions.session

    var bookings by mutableStateOf<Load<List<Booking>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** Whose bookings [bookings] holds. The view model outlives a sign-out, so the list is tied to its owner. */
    private var owner: String? = null
    private var job: Job? = null

    fun load(byUser: Boolean = false) {
        val userId = app.sessions.current?.user?.id ?: return signedOut()
        // A list left by another account is dropped before anything is drawn, not once the new one arrives.
        if (userId != owner || bookings !is Load.Ready) bookings = Load.Loading
        owner = userId
        refreshing = byUser
        job?.cancel()
        job = viewModelScope.launch {
            val loaded = try {
                Load.Ready(app.repository.bookings())
            } catch (error: BackendException) {
                // A list already on screen stays there; pulling again retries.
                if (bookings is Load.Ready) bookings else Load.Failed(Messages.of(error))
            }
            if (app.sessions.current?.user?.id == userId) bookings = loaded
            refreshing = false
        }
    }

    fun signedOut() {
        job?.cancel()
        owner = null
        bookings = Load.Loading
        refreshing = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookingsScreen(contentPadding: PaddingValues, onLogin: () -> Unit, onBooking: (String) -> Unit, onExplore: () -> Unit) {
    val model = appViewModel { BookingsViewModel(it) }
    val session by model.session.collectAsStateWithLifecycle()
    // Runs every time the tab is shown and whenever the signed-in user changes.
    LaunchedEffect(session?.user?.id) { if (session == null) model.signedOut() else model.load() }

    Column(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        TabHeader("Lịch đặt", "Giờ chơi sắp tới và những lần đã đặt")
        if (session == null) {
            StateMessage("📅", "Đăng nhập để xem lịch đặt", "Lịch bạn đã đặt và trạng thái thanh toán sẽ hiện ở đây.", action = "Đăng nhập", onAction = onLogin)
            return@Column
        }
        PullToRefreshBox(isRefreshing = model.refreshing, onRefresh = { model.load(byUser = true) }, modifier = Modifier.fillMaxSize()) {
            when (val state = model.bookings) {
                Load.Loading -> SkeletonRows(4, Modifier.padding(top = 8.dp))
                is Load.Failed -> FailedState(state.message, { model.load() })
                is Load.Ready -> BookingList(state.value, onBooking, onExplore)
            }
        }
    }
}

@Composable
private fun BookingList(bookings: List<Booking>, onBooking: (String) -> Unit, onExplore: () -> Unit) {
    val now = remember(bookings) { Instant.now() }
    val (upcoming, past) = remember(bookings) {
        bookings.partition { it.endsAt.isAfter(now) && it.statusAt(now).let { status -> status == BookingStatus.PENDING_PAYMENT || status == BookingStatus.CONFIRMED } }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
        if (bookings.isEmpty()) item(key = "empty") { StateMessage("🏸", "Chưa có lịch đặt nào", "Tìm một sân ưng ý và đặt giờ chơi đầu tiên của bạn.", action = "Tìm sân", onAction = onExplore) }
        if (upcoming.isNotEmpty()) item(key = "upcoming") { ListTitle("Sắp tới") }
        items(upcoming.sortedBy(Booking::startsAt), key = Booking::id) { booking -> BookingCard(booking, now) { onBooking(booking.id) } }
        if (past.isNotEmpty()) item(key = "past") { ListTitle("Lịch sử") }
        items(past, key = Booking::id) { booking -> BookingCard(booking, now) { onBooking(booking.id) } }
    }
}

@Composable
private fun ListTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun BookingCard(booking: Booking, now: Instant, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(booking.branchImage, Modifier.size(76.dp).clip(MaterialTheme.shapes.medium))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(booking.branchName.ifBlank { "Sân thể thao" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${Formats.fullDate(booking.startsAt, booking.zone)} · ${Formats.timeRange(booking.startsAt, booking.endsAt, booking.zone)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(booking.statusAt(now))
                    Text(Formats.price(booking.amount), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: BookingStatus) {
    val colors = MaterialTheme.colorScheme
    when (status) {
        BookingStatus.CONFIRMED -> Pill("Đã xác nhận", colors.secondaryContainer, colors.onSecondaryContainer)
        BookingStatus.PENDING_PAYMENT -> Pill("Chờ thanh toán", colors.tertiaryContainer, colors.onTertiaryContainer)
        BookingStatus.CANCELLED -> Pill("Đã huỷ", colors.surfaceContainerHighest, colors.onSurfaceVariant)
        BookingStatus.EXPIRED -> Pill("Hết hạn", colors.surfaceContainerHighest, colors.onSurfaceVariant)
        BookingStatus.UNKNOWN -> Pill("Đang cập nhật", colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

private const val POLL_MS = 4_000L
private const val POLL_GRACE_SECONDS = 45L
