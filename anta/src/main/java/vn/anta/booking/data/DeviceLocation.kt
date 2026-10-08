package vn.anta.booking.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the phone is, as far as the user has allowed the app to know. */
class DeviceLocation(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val state = MutableStateFlow<GeoPoint?>(null)

    /** The last position found, or null while there is none (not allowed, turned off, or no fix yet). */
    val position: StateFlow<GeoPoint?> = state.asStateFlow()

    val isAllowed: Boolean
        get() = PERMISSIONS.any(::granted)

    /** The newest fix the system already has, however old. It returns at once: enough to order a list while [refresh] looks again. */
    fun recall(): GeoPoint? {
        if (isAllowed) lastKnown()?.let(::publish)
        return state.value
    }

    /** The position now: a fix the system took in the last few minutes, otherwise a new one, waited for a few seconds at most. */
    suspend fun refresh(): GeoPoint? {
        if (!isAllowed) return state.value
        val known = lastKnown()
        val found = known?.takeIf { System.currentTimeMillis() - it.time < RECENT_MS } ?: fresh() ?: known
        found?.let(::publish)
        return state.value
    }

    private fun publish(location: Location) {
        state.value = GeoPoint(location.latitude, location.longitude)
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The providers this app may ask right now, quickest first. Satellite positioning needs the precise permission. */
    private fun providers(): List<String> {
        val enabled = try {
            manager.getProviders(true)
        } catch (_: SecurityException) {
            emptyList()
        }
        val usable = listOfNotNull(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER.takeIf { granted(Manifest.permission.ACCESS_FINE_LOCATION) }
        )
        return usable.filter { it in enabled }
    }

    @SuppressLint("MissingPermission") // Only reached when isAllowed; a provider that still refuses is skipped.
    private fun lastKnown(): Location? = (providers() + LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
        try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }.maxByOrNull(Location::getTime)

    private suspend fun fresh(): Location? {
        val provider = providers().firstOrNull() ?: return null
        return withTimeoutOrNull(FIX_TIMEOUT_MS) { request(provider) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun request(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location -> if (continuation.isActive) continuation.resume(location) }
            } else {
                // Before Android 11 every method of the listener is abstract, so it cannot be a lambda.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Required before Android 11")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        private const val FIX_TIMEOUT_MS = 8_000L
        private const val RECENT_MS = 5 * 60_000L
    }
}
