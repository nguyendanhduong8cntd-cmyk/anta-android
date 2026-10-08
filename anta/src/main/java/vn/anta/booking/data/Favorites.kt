package vn.anta.booking.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import vn.anta.booking.Logger

/**
 * The signed-in user's favourite venues, shared by every screen that shows or changes them.
 *
 * A change shows at once and is confirmed by the backend afterwards. Until then it is kept apart from what the
 * backend last reported, as a wish: reading the list again in the meantime cannot lose it, and a refusal simply
 * drops the wish, which leaves what the backend really holds.
 */
class Favorites(
    private val repository: Repository,
    private val session: StateFlow<Session?>,
    private val position: () -> GeoPoint?,
    scope: CoroutineScope
) {
    /** What the user asked for and the backend has not answered yet. */
    private class Wish(val owner: String, val venue: Venue, val favorite: Boolean)

    private val lock = Any()
    private var confirmed: List<Venue> = emptyList()
    private var wishes: Map<String, Wish> = emptyMap()
    private val shown = MutableStateFlow<List<Venue>>(emptyList())
    private val requests = Mutex()

    /** The latest addition first. Empty while signed out and until the first load. */
    val venues: StateFlow<List<Venue>> = shown.asStateFlow()

    init {
        scope.launch {
            // One account's favourites never show under another: the list empties the moment the user changes.
            session.map { it?.user?.id }.distinctUntilChanged().collectLatest { userId ->
                update { confirmed = emptyList(); wishes = emptyMap() }
                if (userId == null) return@collectLatest
                try {
                    refresh()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    // Hearts stay empty for now; the favourites screen shows the failure when it asks again.
                    Logger.w("Favourites could not be loaded", throwable = error)
                }
            }
        }
    }

    /** Reads the list again from the backend. Does nothing while signed out. */
    suspend fun refresh() {
        val owner = userId() ?: return
        val loaded = requests.withLock { repository.favorites(position()) }
        update { if (userId() == owner) confirmed = loaded }
    }

    /**
     * Adds [venue] to the favourites or takes it out. The list changes at once and changes back if the backend
     * refuses; the request is finished even when the screen that asked has closed.
     */
    suspend fun set(venue: Venue, favorite: Boolean) {
        val wish = Wish(userId() ?: return, venue, favorite)
        update { wishes = wishes + (venue.id to wish) }
        withContext(NonCancellable) {
            try {
                requests.withLock { repository.setFavorite(venue.id, favorite) }
            } catch (error: Exception) {
                settle(wish, granted = false)
                throw error
            }
            settle(wish, granted = true)
        }
    }

    /** The backend has answered [wish]. A newer wish for the same venue stays in force. */
    private fun settle(wish: Wish, granted: Boolean) = update {
        if (granted && userId() == wish.owner) confirmed = confirmed.with(wish)
        if (wishes[wish.venue.id] === wish) wishes = wishes - wish.venue.id
    }

    private inline fun update(change: () -> Unit) = synchronized(lock) {
        change()
        shown.value = wishes.values.fold(confirmed) { list, wish -> list.with(wish) }
    }

    private fun List<Venue>.with(wish: Wish): List<Venue> = when {
        !wish.favorite -> filterNot { it.id == wish.venue.id }
        any { it.id == wish.venue.id } -> this
        else -> listOf(wish.venue) + this
    }

    private fun userId(): String? = session.value?.user?.id
}
