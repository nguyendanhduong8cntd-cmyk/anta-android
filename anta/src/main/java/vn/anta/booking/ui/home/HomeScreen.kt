package vn.anta.booking.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.DeviceLocation
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Repository
import vn.anta.booking.data.Sport
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenueSort
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.HeaderBand
import vn.anta.booking.ui.components.HeaderButton
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.SearchBar
import vn.anta.booking.ui.components.SectionHeader
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StateMessage
import vn.anta.booking.ui.components.StatusBarScrim
import vn.anta.booking.ui.components.VenueFeed
import vn.anta.booking.ui.components.VenueRowCard
import vn.anta.booking.ui.components.VenueTallCard
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.components.sportEmoji

class HomeViewModel(private val app: AppContainer) : ViewModel() {
    val sports = app.catalog.sports
    val session = app.sessions.session
    val position = app.location.position

    var sportId by mutableStateOf<String?>(null)
        private set
    var featured by mutableStateOf<Load<List<Venue>>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var locationAllowed by mutableStateOf(app.location.isAllowed)
        private set

    /** Nearest first once the phone's position is known; by name until then, as the best rated already lead the page. */
    val venues = VenueFeed(viewModelScope) { page -> app.repository.venues(position.value, sportId, sort = VenueSort.DISTANCE, page = page) }
    private var featuredJob: Job? = null

    init {
        refresh(byUser = false)
    }

    fun refresh(byUser: Boolean = true) {
        viewModelScope.launch {
            refreshing = byUser
            locationAllowed = app.location.isAllowed
            launch { app.catalog.ensure(force = byUser) }
            // The lists start from whatever position the system already has. Getting a new fix can take seconds
            // indoors, so it runs afterwards and reloads the lists only if it puts the user somewhere else.
            val start = app.location.recall()
            venues.reload(keepShown = byUser)
            loadFeatured().join()
            refreshing = false
            val now = app.location.refresh()
            if (now != null && (start == null || Repository.distanceMeters(start, now) > MOVED_METERS)) {
                venues.reload(keepShown = true)
                loadFeatured()
            }
        }
    }

    fun selectSport(id: String?) {
        if (sportId == id) return
        sportId = id
        venues.reload()
        loadFeatured()
    }

    private fun loadFeatured(): Job {
        featuredJob?.cancel()
        return viewModelScope.launch {
            featured = try {
                Load.Ready(app.repository.venues(position.value, sportId, sort = VenueSort.RATING, pageSize = FEATURED_COUNT).items)
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }.also { featuredJob = it }
    }

    private companion object {
        const val FEATURED_COUNT = 10
        const val MOVED_METERS = 300.0
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onSearch: () -> Unit,
    onVenue: (String) -> Unit,
    onFeatured: (sportId: String?) -> Unit,
    onAccount: () -> Unit
) {
    val model = appViewModel { HomeViewModel(it) }
    val sports by model.sports.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    val position by model.position.collectAsStateWithLifecycle()
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, model.venues.state) { if (nearEnd) model.venues.loadMore() }
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    PullToRefreshBox(isRefreshing = model.refreshing, onRefresh = model::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 20.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") { HomeHeader(session?.user?.displayName, onSearch, onAccount) }
            item(key = "sports") { SportFilter(sports, model.sportId, model::selectSport, Modifier.padding(top = 16.dp)) }
            if (!model.locationAllowed) item(key = "location") { LocationPrompt { askLocation.launch(DeviceLocation.PERMISSIONS) } }

            val featured = (model.featured as? Load.Ready)?.value.orEmpty()
            if (featured.isNotEmpty()) {
                item(key = "featured-title") { SectionHeader("Sân nổi bật", Modifier.padding(top = 12.dp), action = "Xem tất cả") { onFeatured(model.sportId) } }
                item(key = "featured") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(featured, key = Venue::id) { venue -> VenueTallCard(venue, { onVenue(venue.id) }, Modifier.padding(bottom = 6.dp)) }
                    }
                }
            }

            // "Near you" only when the nearest venue really is: a user abroad gets the same list under an honest title.
            val nearest = (model.venues.state as? Load.Ready)?.value?.firstOrNull()?.distanceMeters
            val title = if (position == null) "Tất cả sân" else if (nearest != null && nearest > NEARBY_METERS) "Sân gần nhất" else "Gần bạn"
            item(key = "venues-title") { SectionHeader(title, Modifier.padding(top = 12.dp)) }
            when (val state = model.venues.state) {
                Load.Loading -> item(key = "loading") { SkeletonRows(4) }
                is Load.Failed -> item(key = "failed") { FailedState(state.message, model::refresh) }
                is Load.Ready -> if (state.value.isEmpty()) {
                    item(key = "empty") { StateMessage("🔍", "Chưa có sân phù hợp", "Thử chọn môn khác hoặc bỏ lọc để xem thêm sân.") }
                } else {
                    items(state.value, key = Venue::id) { venue ->
                        VenueRowCard(venue, { onVenue(venue.id) }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                    }
                    if (model.venues.loadingMore) item(key = "more") { SkeletonRows(1, Modifier.padding(top = 6.dp)) }
                }
            }
        }
        StatusBarScrim(headerGone)
    }
}

@Composable
private fun HomeHeader(name: String?, onSearch: () -> Unit, onAccount: () -> Unit) {
    HeaderBand {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (name.isNullOrBlank()) "Chào bạn 👋" else "Chào $name 👋",
                        style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text("Hôm nay chơi môn gì?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                val initial = name?.trim()?.firstOrNull()?.uppercase()
                if (initial == null) HeaderButton(Icons.Rounded.Person, "Tài khoản", onAccount) else Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).clickable(role = Role.Button, onClickLabel = "Tài khoản", onClick = onAccount),
                    contentAlignment = Alignment.Center
                ) { Text(initial, style = MaterialTheme.typography.titleLarge, color = Color.White) }
            }
            Spacer(Modifier.height(18.dp))
            SearchBar(onSearch)
        }
    }
}

/** One row of sports to filter by; "Tất cả" clears the filter. */
@Composable
fun SportFilter(sports: List<Sport>, selectedId: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(modifier, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item(key = "all") { SportChip("✨ Tất cả", selectedId == null) { onSelect(null) } }
        items(sports, key = Sport::id) { sport -> SportChip("${sportEmoji(sport.slug)} ${sport.name}", selectedId == sport.id) { onSelect(sport.id) } }
    }
}

@Composable
private fun SportChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val colors = MaterialTheme.colorScheme
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) colors.onPrimary else colors.onSurface,
        modifier = Modifier.clip(shape)
            .background(if (selected) colors.primary else colors.surface)
            .border(1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun LocationPrompt(onAllow: () -> Unit) {
    Row(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp).fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primaryContainer).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text("Bật định vị để xem sân gần bạn", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = onAllow) { Text("Bật") }
    }
}

private const val NEARBY_METERS = 50_000.0
