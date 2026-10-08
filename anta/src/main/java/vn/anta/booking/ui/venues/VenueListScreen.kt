package vn.anta.booking.ui.venues

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenueSort
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StateMessage
import vn.anta.booking.ui.components.VenueFeed
import vn.anta.booking.ui.components.VenueRowCard
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.home.SportFilter

class VenueListViewModel(private val app: AppContainer, sportId: String?, sort: VenueSort) : ViewModel() {
    val sports = app.catalog.sports
    val position = app.location.position

    var sportId by mutableStateOf(sportId)
        private set
    var sort by mutableStateOf(sort)
        private set

    val venues = VenueFeed(viewModelScope) { page -> app.repository.venues(position.value, this.sportId, sort = this.sort, page = page) }

    init {
        viewModelScope.launch { app.catalog.ensure() }
        venues.reload()
    }

    fun selectSport(id: String?) {
        if (sportId == id) return
        sportId = id
        venues.reload()
    }

    fun sortBy(order: VenueSort) {
        if (sort == order) return
        sort = order
        venues.reload()
    }
}

/** Every venue, narrowed by sport and ordered the way the user chooses. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueListScreen(sportId: String?, sort: VenueSort, onBack: () -> Unit, onVenue: (String) -> Unit) {
    val model = appViewModel { VenueListViewModel(it, sportId, sort) }
    val sports by model.sports.collectAsStateWithLifecycle()
    val position by model.position.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, model.venues.state) { if (nearEnd) model.venues.loadMore() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(sports.firstOrNull { it.id == model.sportId }?.name ?: "Tất cả sân", style = MaterialTheme.typography.titleLarge)
                        if (model.venues.state is Load.Ready) Text("${model.venues.total} sân", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        LazyColumn(state = listState, contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 20.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "sports") { SportFilter(sports, model.sportId, model::selectSport) }
            item(key = "sort") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Without a position there is no "nearest": the order falls back to names, so the choice is not offered.
                    if (position != null) SortChip("Gần nhất", model.sort == VenueSort.DISTANCE) { model.sortBy(VenueSort.DISTANCE) }
                    SortChip("Đánh giá cao", model.sort == VenueSort.RATING) { model.sortBy(VenueSort.RATING) }
                    SortChip("Giá thấp", model.sort == VenueSort.PRICE) { model.sortBy(VenueSort.PRICE) }
                }
            }
            when (val state = model.venues.state) {
                Load.Loading -> item(key = "loading") { SkeletonRows(5, Modifier.padding(top = 6.dp)) }
                is Load.Failed -> item(key = "failed") { FailedState(state.message, { model.venues.reload() }) }
                is Load.Ready -> if (state.value.isEmpty()) {
                    item(key = "empty") { StateMessage("🔍", "Chưa có sân phù hợp", "Thử chọn môn khác để xem thêm sân.") }
                } else {
                    items(state.value, key = Venue::id) { venue -> VenueRowCard(venue, { onVenue(venue.id) }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                    if (model.venues.loadingMore) item(key = "more") { SkeletonRows(1, Modifier.padding(top = 6.dp)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    // A pill, like the sport chips above it.
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) }, shape = CircleShape)
}
