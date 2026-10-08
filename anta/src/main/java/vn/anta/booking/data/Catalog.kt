package vn.anta.booking.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The sports venues are filed under, loaded once and shared by every screen that filters by sport. */
class Catalog(private val repository: Repository) {
    private val state = MutableStateFlow<List<Sport>>(emptyList())
    private val loading = Mutex()

    /** Sports with at least one venue, the ones with most venues first. Empty until loaded. */
    val sports: StateFlow<List<Sport>> = state.asStateFlow()

    /** Loads the sports unless they are already here. A failure leaves the list empty and is tried again next time. */
    suspend fun ensure(force: Boolean = false) = loading.withLock {
        if (state.value.isNotEmpty() && !force) return@withLock
        try {
            state.value = repository.sports().filter { it.venueCount > 0 }.sortedWith(compareByDescending(Sport::venueCount).thenBy(Sport::name))
        } catch (_: BackendException) {
            // Screens work without the filter; they show their own failure for the list they asked for.
        }
    }
}
