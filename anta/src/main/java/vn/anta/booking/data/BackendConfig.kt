package vn.anta.booking.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the backend says about itself, asked once and remembered for as long as the app runs. */
class BackendConfig(private val repository: Repository) {
    private var known: AppConfig? = null
    private val loading = Mutex()

    /** Null when the backend could not be asked; the next call asks again. */
    suspend fun get(): AppConfig? = loading.withLock {
        known ?: try {
            repository.config().also { known = it }
        } catch (_: BackendException) {
            null
        }
    }
}
