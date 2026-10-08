package vn.anta.booking.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenuePage

/** A list of venues that loads a page at a time as the user scrolls. */
class VenueFeed(private val scope: CoroutineScope, private val fetch: suspend (page: Int) -> VenuePage) {
    var state by mutableStateOf<Load<List<Venue>>>(Load.Loading)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var total by mutableStateOf(0)
        private set

    private var page = 0
    private var hasMore = false
    private var job: Job? = null

    /** Starts over from the first page, dropping whatever was still on its way. */
    fun reload(keepShown: Boolean = false) {
        job?.cancel()
        loadingMore = false
        if (!keepShown || state !is Load.Ready) state = Load.Loading
        job = scope.launch {
            state = try {
                val first = fetch(1)
                page = 1
                hasMore = first.hasMore
                total = first.total
                Load.Ready(first.items)
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }

    /** Loads the next page when there is one. A failure here keeps what is shown; scrolling again retries. */
    fun loadMore() {
        val shown = (state as? Load.Ready)?.value ?: return
        // Not while a reload is on its way: its fresh first page would be overwritten by this stale next one.
        if (!hasMore || loadingMore || job?.isActive == true) return
        loadingMore = true
        job = scope.launch {
            try {
                val next = fetch(page + 1)
                page += 1
                hasMore = next.hasMore
                val known = shown.mapTo(HashSet()) { it.id }
                state = Load.Ready(shown + next.items.filterNot { it.id in known })
            } catch (_: BackendException) {
                // Nothing to show for it: the list stays as it is.
            } finally {
                loadingMore = false
            }
        }
    }
}
