package vn.anta.booking.ui.booking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.time.Clock
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.ApiException
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Formats
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Repository
import vn.anta.booking.data.Slot
import vn.anta.booking.data.SlotSelection
import vn.anta.booking.data.VenueDetail
import vn.anta.booking.data.tap
import vn.anta.booking.data.total
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.PrimaryButton
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StateMessage
import vn.anta.booking.ui.components.appViewModel

class SlotPickerViewModel(private val app: AppContainer, private val venueId: String, private val clock: Clock = Clock.systemUTC()) : ViewModel() {
    var venue by mutableStateOf<Load<VenueDetail>>(Load.Loading)
        private set
    var days by mutableStateOf<List<LocalDate>>(emptyList())
        private set
    var date by mutableStateOf<LocalDate?>(null)
        private set
    var slots by mutableStateOf<Load<List<Slot>>>(Load.Loading)
        private set
    var selection by mutableStateOf<SlotSelection?>(null)
        private set
    var submitting by mutableStateOf(false)
        private set

    /** Something to tell the user once; cleared when shown. */
    var notice by mutableStateOf<String?>(null)
        private set

    /** The booking that was just made, for the screen to open; cleared when opened. */
    var bookedId by mutableStateOf<String?>(null)
        private set

    private var attempt = Repository.newAttempt()
    private var slotsJob: Job? = null

    init {
        load()
    }

    fun load() {
        venue = Load.Loading
        viewModelScope.launch {
            val detail = try {
                app.repository.venue(venueId, app.location.position.value)
            } catch (error: BackendException) {
                venue = Load.Failed(Messages.of(error))
                return@launch
            }
            // The calendar is the venue's, not the phone's: "today" is the day it is where the courts are.
            val today = clock.instant().atZone(detail.zone).toLocalDate()
            days = (0 until DAYS_AHEAD).map { today.plusDays(it.toLong()) }
            venue = Load.Ready(detail)
            pickDate(date?.takeIf { it in days } ?: today)
        }
    }

    fun pickDate(day: LocalDate) {
        val zone = (venue as? Load.Ready)?.value?.zone ?: return
        if (day != date) selection = null
        date = day
        slotsJob?.cancel()
        slots = Load.Loading
        slotsJob = viewModelScope.launch {
            slots = try {
                Load.Ready(app.repository.slots(venueId, day, zone))
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }

    fun tap(slot: Slot) {
        if (!slot.available || submitting) return
        selection = selection.tap(slot)
        attempt = Repository.newAttempt()
    }

    val session = app.sessions.session

    fun book() {
        val picked = selection ?: return
        if (submitting) return
        submitting = true
        viewModelScope.launch {
            try {
                bookedId = app.repository.book(picked, attempt).id
                selection = null
            } catch (error: BackendException) {
                notice = Messages.of(error)
                if (error is ApiException) {
                    // The backend refused these hours: the next try is a new one, against a fresh timetable.
                    attempt = Repository.newAttempt()
                    selection = null
                    date?.let(::pickDate)
                }
            } finally {
                submitting = false
            }
        }
    }

    fun noticeShown() {
        notice = null
    }

    fun bookingOpened() {
        bookedId = null
        attempt = Repository.newAttempt()
        date?.let(::pickDate)
    }

    private companion object {
        const val DAYS_AHEAD = 14
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlotPickerScreen(venueId: String, onBack: () -> Unit, onLogin: () -> Unit, onBooked: (String) -> Unit) {
    val model = appViewModel { SlotPickerViewModel(it, venueId) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.notice) { model.notice?.let { snackbar.showSnackbar(it); model.noticeShown() } }
    LaunchedEffect(model.bookedId) { model.bookedId?.let { onBooked(it); model.bookingOpened() } }
    val detail = (model.venue as? Load.Ready)?.value
    val signedIn = model.session.collectAsStateWithLifecycle().value != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Chọn giờ chơi", style = MaterialTheme.typography.titleLarge)
                        detail?.let { Text(it.venue.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (detail != null) SelectionBar(model, detail, signedIn) { if (signedIn) model.book() else onLogin() } }
    ) { padding ->
        when (val venue = model.venue) {
            Load.Loading -> SkeletonRows(4, Modifier.padding(padding).padding(top = 16.dp))
            is Load.Failed -> FailedState(venue.message, model::load, Modifier.padding(padding))
            is Load.Ready -> LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp), modifier = Modifier.fillMaxSize()) {
                item(key = "days") { DayStrip(model.days, model.date, model::pickDate) }
                item(key = "legend") { Legend() }
                when (val slots = model.slots) {
                    Load.Loading -> item(key = "loading") { SkeletonRows(3) }
                    is Load.Failed -> item(key = "failed") { FailedState(slots.message, { model.date?.let(model::pickDate) }) }
                    is Load.Ready -> {
                        val yards = slots.value.groupBy(Slot::yardId).values.toList()
                        if (yards.isEmpty()) item(key = "none") { StateMessage("🌙", "Hết giờ đặt trong ngày", "Hãy chọn một ngày khác để xem giờ trống.") }
                        items(yards, key = { it.first().yardId }) { hours -> YardCard(hours, venue.value, model.selection, model::tap) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayStrip(days: List<LocalDate>, selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(days, key = LocalDate::toEpochDay) { day ->
            val chosen = day == selected
            val colors = MaterialTheme.colorScheme
            Column(
                Modifier.width(58.dp).clip(RoundedCornerShape(18.dp)).background(if (chosen) colors.primary else colors.surface)
                    .border(1.dp, if (chosen) colors.primary else colors.outlineVariant, RoundedCornerShape(18.dp))
                    .clickable(role = Role.Tab, onClickLabel = Formats.day(day, days.first())) { onPick(day) }.padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(if (day == days.first()) "Nay" else Formats.weekdayShort(day.dayOfWeek), style = MaterialTheme.typography.labelMedium, color = if (chosen) colors.onPrimary else colors.onSurfaceVariant)
                Text("${day.dayOfMonth}", style = MaterialTheme.typography.titleLarge, color = if (chosen) colors.onPrimary else colors.onSurface)
                Text("Th${day.monthValue}", style = MaterialTheme.typography.labelSmall, color = if (chosen) colors.onPrimary.copy(alpha = 0.85f) else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.outline, "Trống")
        LegendItem(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary, "Đang chọn")
        LegendItem(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerHighest, "Đã có người đặt")
    }
}

@Composable
private fun LegendItem(fill: Color, border: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(fill).border(1.dp, border, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YardCard(hours: List<Slot>, detail: VenueDetail, selection: SlotSelection?, onTap: (Slot) -> Unit) {
    val first = hours.first()
    Surface(shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(first.yardName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text("${Formats.price(first.amount)}/giờ", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hours.forEach { slot -> HourChip(Formats.hourOfDay(slot.startsAt, detail.zone), slot.available, selection?.contains(slot) == true) { onTap(slot) } }
            }
        }
    }
}

@Composable
private fun HourChip(label: String, available: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val (fill, text, border) = when {
        selected -> Triple(colors.primary, colors.onPrimary, colors.primary)
        available -> Triple(colors.surface, colors.onSurface, colors.outline)
        else -> Triple(colors.surfaceContainerHighest, colors.onSurfaceVariant.copy(alpha = 0.6f), colors.surfaceContainerHighest)
    }
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = text,
        textDecoration = if (available) null else TextDecoration.LineThrough,
        modifier = Modifier.clip(shape).background(fill).border(1.dp, border, shape)
            .clickable(enabled = available, role = Role.Checkbox, onClickLabel = if (selected) "Bỏ chọn $label" else "Chọn $label", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    )
}

@Composable
private fun SelectionBar(model: SlotPickerViewModel, detail: VenueDetail, signedIn: Boolean, onContinue: () -> Unit) {
    val selection = model.selection
    val slots = (model.slots as? Load.Ready)?.value.orEmpty()
    val total = selection?.total(slots)
    Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection == null || total == null) {
                Text(
                    "Chạm một giờ trống để chọn. Chạm giờ liền kề để đặt nhiều giờ.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 8.dp)
                )
            } else {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${Formats.timeRange(selection.startsAt, selection.endsAt, detail.zone)} · ${slots.first { it in selection }.yardName}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(Formats.price(total), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(if (signedIn) "Đặt ${selection.count} giờ" else "Đăng nhập để đặt", onContinue, loading = model.submitting)
            }
        }
    }
}
