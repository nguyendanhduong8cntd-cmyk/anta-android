package vn.anta.booking.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

class SearchViewModel(private val app: AppContainer) : ViewModel() {
    val sports = app.catalog.sports
    val recent = app.recentSearches.terms

    var query by mutableStateOf("")
        private set
    var sportId by mutableStateOf<String?>(null)
        private set

    /** True once something has been asked for: before that the screen shows recent searches, not results. */
    var searched by mutableStateOf(false)
        private set

    /** The text of the search on screen, which lags behind [query] while the user is still typing. */
    private var asked = ""

    val results = VenueFeed(viewModelScope) { page ->
        val near = app.location.position.value
        app.repository.venues(near, sportId, search = asked, sort = if (near != null) VenueSort.DISTANCE else VenueSort.RATING, page = page)
    }
    private var pending: Job? = null

    init {
        viewModelScope.launch { app.catalog.ensure() }
    }

    /** Searches a moment after the user stops typing, so not every letter is a request. */
    fun type(text: String) {
        query = text.take(MAX_QUERY)
        pending?.cancel()
        pending = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            run()
        }
    }

    /** Searches at once: the user pressed search, picked a recent term or changed the sport. */
    fun submit(text: String = query) {
        pending?.cancel()
        query = text.take(MAX_QUERY)
        app.recentSearches.add(query)
        run()
    }

    fun selectSport(id: String?) {
        if (sportId == id) return
        sportId = id
        pending?.cancel()
        run()
    }

    fun clear() {
        pending?.cancel()
        query = ""
        run()
    }

    fun clearRecent() = app.recentSearches.clear()

    /** Remembers what led the user to a venue they opened. */
    fun opened() = app.recentSearches.add(query)

    private fun run() {
        asked = query
        searched = query.isNotBlank() || sportId != null
        if (searched) results.reload()
    }

    private companion object {
        const val TYPING_PAUSE_MS = 350L
        const val MAX_QUERY = 100
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onVenue: (String) -> Unit) {
    val model = appViewModel { SearchViewModel(it) }
    val sports by model.sports.collectAsStateWithLifecycle()
    val recent by model.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 4 } }
    LaunchedEffect(nearEnd, model.results.state) { if (nearEnd && model.searched) model.results.loadMore() }
    // The keyboard opens with the screen, but not again when the user comes back from a venue they opened.
    LaunchedEffect(Unit) { if (!model.searched) focus.requestFocus() }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
            OutlinedTextField(
                value = model.query,
                onValueChange = model::type,
                placeholder = { Text("Tìm sân, khu vực, môn thể thao…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = { if (model.query.isNotEmpty()) IconButton(onClick = model::clear) { Icon(Icons.Rounded.Close, contentDescription = "Xoá") } },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { model.submit(); keyboard?.hide() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier.weight(1f).focusRequester(focus)
            )
        }
        SportFilter(sports, model.sportId, model::selectSport)

        if (!model.searched) {
            RecentSearches(recent, onPick = { model.submit(it); keyboard?.hide() }, onClear = model::clearRecent)
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
                when (val state = model.results.state) {
                    Load.Loading -> item(key = "loading") { SkeletonRows(4) }
                    is Load.Failed -> item(key = "failed") { FailedState(state.message, { model.results.reload() }) }
                    is Load.Ready -> if (state.value.isEmpty()) {
                        item(key = "empty") { StateMessage("🔍", "Không tìm thấy sân", "Thử từ khoá ngắn hơn, tên quận hoặc tên môn thể thao.") }
                    } else {
                        item(key = "count") {
                            Text("${model.results.total} kết quả", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                        }
                        items(state.value, key = Venue::id) { venue ->
                            VenueRowCard(venue, { model.opened(); onVenue(venue.id) }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
                        }
                        if (model.results.loadingMore) item(key = "more") { SkeletonRows(1, Modifier.padding(top = 6.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(terms: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (terms.isEmpty()) {
        StateMessage("🏸", "Tìm sân để chơi", "Gõ tên sân, tên quận hoặc môn thể thao — có dấu hay không dấu đều được.")
        return
    }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tìm gần đây", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onClear) { Text("Xoá") }
        }
        terms.forEach { term ->
            Row(Modifier.fillMaxWidth().clickable { onPick(term) }.padding(horizontal = 20.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(14.dp))
                Text(term, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
