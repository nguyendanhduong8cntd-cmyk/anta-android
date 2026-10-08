package vn.anta.booking.ui.explore

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Formats
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Sport
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenueSort
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.HeaderButton
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.RemoteImage
import vn.anta.booking.ui.components.SectionHeader
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StatusBarScrim
import vn.anta.booking.ui.components.TabHeader
import vn.anta.booking.ui.components.VenueSmallCard
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.components.emoji
import vn.anta.booking.ui.components.sportColor
import vn.anta.booking.ui.components.sportEmoji
import vn.anta.booking.ui.theme.Brand

/** What Explore shows besides the sports: the venues rated best, the ones priced lowest, and how many there are in all. */
data class Collections(val bestRated: List<Venue>, val bestPriced: List<Venue>, val venueCount: Int)

class ExploreViewModel(private val app: AppContainer) : ViewModel() {
    val sports = app.catalog.sports
    val favorites = app.favorites.venues

    var collections by mutableStateOf<Load<Collections>>(Load.Loading)
        private set
    var refreshing by mutableStateOf(false)
        private set
    private var job: Job? = null

    init {
        load(byUser = false)
    }

    fun load(byUser: Boolean = true) {
        // One load at a time: a pull while another is on its way replaces it, so the older answer cannot win.
        job?.cancel()
        job = viewModelScope.launch {
            refreshing = byUser
            if (!byUser) collections = Load.Loading
            launch { app.catalog.ensure(force = byUser) }
            val position = app.location.position.value
            collections = try {
                // Its own scope: a failed request ends both and is caught here, instead of cancelling this coroutine.
                coroutineScope {
                    val rated = async { app.repository.venues(position, sort = VenueSort.RATING, pageSize = RANKED) }
                    val priced = async { app.repository.venues(position, sort = VenueSort.PRICE, pageSize = PRICED).items }
                    Load.Ready(Collections(rated.await().items, priced.await().filter { it.minPrice != null }, rated.await().total))
                }
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
            refreshing = false
        }
    }

    private companion object {
        const val RANKED = 5
        const val PRICED = 10
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    contentPadding: PaddingValues,
    onSearch: () -> Unit,
    onVenue: (String) -> Unit,
    onBrowse: (sportId: String?, sort: VenueSort) -> Unit,
    onFavorites: () -> Unit
) {
    val model = appViewModel { ExploreViewModel(it) }
    val sports by model.sports.collectAsStateWithLifecycle()
    val favorites by model.favorites.collectAsStateWithLifecycle()
    val collections = model.collections
    val listState = rememberLazyListState()
    val headerGone by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }

    PullToRefreshBox(isRefreshing = model.refreshing, onRefresh = model::load, modifier = Modifier.fillMaxSize()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 20.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "header") {
                TabHeader("Khám phá", "Chọn môn bạn thích, tìm sân hợp ý") { HeaderButton(Icons.Rounded.Search, "Tìm kiếm", onSearch) }
            }
            if (sports.isNotEmpty()) {
                item(key = "sports-title") { SectionHeader("Môn thể thao", Modifier.padding(top = 8.dp)) }
                item(key = "sports") {
                    SportGrid(sports, (collections as? Load.Ready)?.value?.venueCount, onSport = { onBrowse(it, VenueSort.RATING) })
                }
            }
            if (favorites.isNotEmpty()) venueRow("favorites", "Sân yêu thích", favorites.take(FAVORITES_SHOWN), onVenue, onFavorites)
            when (collections) {
                Load.Loading -> item(key = "loading") { SkeletonRows(3, Modifier.padding(top = 20.dp)) }
                is Load.Failed -> item(key = "failed") { FailedState(collections.message, model::load) }
                is Load.Ready -> {
                    if (collections.value.bestRated.isNotEmpty()) {
                        item(key = "rated-title") { SectionHeader("Đánh giá cao nhất", Modifier.padding(top = 12.dp), action = "Xem tất cả") { onBrowse(null, VenueSort.RATING) } }
                        item(key = "rated") { Ranking(collections.value.bestRated, onVenue) }
                    }
                    venueRow("priced", "Giá tốt", collections.value.bestPriced, onVenue) { onBrowse(null, VenueSort.PRICE) }
                }
            }
        }
        StatusBarScrim(headerGone)
    }
}

private fun LazyListScope.venueRow(key: String, title: String, venues: List<Venue>, onVenue: (String) -> Unit, onAll: () -> Unit) {
    if (venues.isEmpty()) return
    item(key = "$key-title") { SectionHeader(title, Modifier.padding(top = 12.dp), action = "Xem tất cả", onAction = onAll) }
    item(key = key) {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(venues, key = Venue::id) { venue -> VenueSmallCard(venue, { onVenue(venue.id) }, Modifier.padding(bottom = 6.dp)) }
        }
    }
}

/**
 * How many of [count] sports the grid shows. Its last tile is always "all venues", and a ragged last row looks
 * unfinished, so the sports fill whole rows with that tile: 3 or 7 of them. The rest are one tap away, behind "all".
 */
internal fun sportsShown(count: Int): Int {
    val fullRows = ((count + 1) / GRID_COLUMNS).coerceAtMost(GRID_ROWS)
    return if (fullRows == 0) count else fullRows * GRID_COLUMNS - 1
}

/** The sports as rows of four round tiles, the ones with most venues first. */
@Composable
private fun SportGrid(sports: List<Sport>, venueCount: Int?, onSport: (String?) -> Unit) {
    val tiles: List<Sport?> = sports.take(sportsShown(sports.size)) + null
    Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        tiles.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { sport ->
                    if (sport == null) {
                        SportTile("Tất cả", venueCount?.let { "$it sân" }.orEmpty(), MaterialTheme.colorScheme.primaryContainer, Modifier.weight(1f), onClick = { onSport(null) }) {
                            Icon(Icons.Rounded.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        }
                    } else {
                        SportTile(sport.name, "${sport.venueCount} sân", sportColor(sport.slug).copy(alpha = 0.18f), Modifier.weight(1f), onClick = { onSport(sport.id) }) {
                            Text(sportEmoji(sport.slug), fontSize = 28.sp)
                        }
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SportTile(name: String, count: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(62.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) { picture() }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp))
        Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The best-rated venues as a numbered list on one card. */
@Composable
private fun Ranking(venues: List<Venue>, onVenue: (String) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column {
            venues.forEachIndexed { index, venue ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))
                Row(Modifier.fillMaxWidth().clickable { onVenue(venue.id) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", style = MaterialTheme.typography.titleLarge, color = if (index < PODIUM) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(10.dp))
                    RemoteImage(venue.cover, Modifier.size(52.dp).clip(MaterialTheme.shapes.small), emoji = venue.emoji())
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(venue.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(venue.sports.firstOrNull()?.name, Formats.distance(venue.distanceMeters) ?: venue.address.ifBlank { null }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    venue.rating?.let { rating ->
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Rounded.Star, contentDescription = null, tint = Brand.Star, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(2.dp))
                        Text(Formats.rating(rating), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

private const val GRID_COLUMNS = 4
private const val GRID_ROWS = 2
private const val PODIUM = 3
private const val FAVORITES_SHOWN = 10
