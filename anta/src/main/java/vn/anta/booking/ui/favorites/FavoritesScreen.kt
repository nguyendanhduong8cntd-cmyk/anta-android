package vn.anta.booking.ui.favorites

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Venue
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.StateMessage
import vn.anta.booking.ui.components.VenueRowCard
import vn.anta.booking.ui.components.appViewModel

class FavoritesViewModel(private val app: AppContainer) : ViewModel() {
    val venues = app.favorites.venues
    val session = app.sessions.session

    var loading by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set

    /** Why the list could not be read, while there is nothing older to show instead. */
    var failure by mutableStateOf<String?>(null)
        private set
    private var job: Job? = null

    fun load(byUser: Boolean = false) {
        job?.cancel()
        refreshing = byUser
        loading = !byUser
        job = viewModelScope.launch {
            failure = try {
                app.favorites.refresh()
                null
            } catch (error: BackendException) {
                Messages.of(error)
            }
            loading = false
            refreshing = false
        }
    }
}

/** The venues the user has marked with a heart. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onBack: () -> Unit, onLogin: () -> Unit, onVenue: (String) -> Unit, onExplore: () -> Unit) {
    val model = appViewModel { FavoritesViewModel(it) }
    val venues by model.venues.collectAsStateWithLifecycle()
    val session by model.session.collectAsStateWithLifecycle()
    // Read again each time the screen opens and whenever the signed-in user changes.
    LaunchedEffect(session?.user?.id) { if (session != null) model.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sân yêu thích", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        if (session == null) {
            StateMessage("❤️", "Đăng nhập để lưu sân yêu thích", "Những sân bạn thả tim sẽ được giữ ở đây, trên mọi thiết bị bạn đăng nhập.", Modifier.padding(padding), action = "Đăng nhập", onAction = onLogin)
            return@Scaffold
        }
        PullToRefreshBox(isRefreshing = model.refreshing, onRefresh = { model.load(byUser = true) }, modifier = Modifier.padding(padding).fillMaxSize()) {
            val failure = model.failure
            when {
                venues.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp), modifier = Modifier.fillMaxSize()) {
                    items(venues, key = Venue::id) { venue -> VenueRowCard(venue, { onVenue(venue.id) }, Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                }
                model.loading -> SkeletonRows(4, Modifier.padding(top = 8.dp))
                failure != null -> FailedState(failure, { model.load() })
                // Scrollable although empty, so that pulling down still refreshes.
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { StateMessage("❤️", "Chưa có sân yêu thích", "Chạm vào trái tim ở trang của một sân để lưu sân đó lại.", action = "Khám phá sân", onAction = onExplore) }
                }
            }
        }
    }
}
