package vn.anta.booking.data

import java.time.Instant
import java.time.ZoneId

data class SportRef(val slug: String, val name: String)

data class Sport(val id: String, val name: String, val slug: String, val venueCount: Int)

data class Venue(
    val id: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lng: Double,
    val distanceMeters: Double?,
    val openingHour: Int?,
    val closingHour: Int?,
    val images: List<String>,
    val rating: Double?,
    val reviewCount: Int,
    val minPrice: Long?,
    val sports: List<SportRef>
) {
    val cover: String? get() = images.firstOrNull()
}

data class VenuePage(val items: List<Venue>, val page: Int, val total: Int) {
    val hasMore: Boolean get() = items.isNotEmpty() && page * PAGE_SIZE < total

    companion object {
        const val PAGE_SIZE = 20
    }
}

data class Yard(val id: String, val sportId: String?, val name: String, val hourlyPrice: Long)

data class VenueService(val name: String, val price: Long)

/** One published price list of a venue: its title and one line per time band. */
data class PriceList(val title: String, val slots: List<String>)

data class VenueDetail(
    val venue: Venue,
    val zone: ZoneId,
    val rules: List<String>,
    val yards: List<Yard>,
    val services: List<VenueService>,
    val priceLists: List<PriceList>
)

/** One bookable hour of one yard. */
data class Slot(val yardId: String, val yardName: String, val startsAt: Instant, val endsAt: Instant, val amount: Long, val available: Boolean) {
    /** Hours since the epoch: consecutive slots of a yard have consecutive values. */
    val hour: Long get() = startsAt.epochSecond / 3600
}

data class Hold(val id: String, val expiresAt: Instant, val total: Long)

enum class BookingStatus { PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED, UNKNOWN }

data class Booking(
    val id: String,
    val branchId: String,
    val branchName: String,
    val branchAddress: String,
    val branchImage: String?,
    val yardName: String,
    val zone: ZoneId,
    val startsAt: Instant,
    val endsAt: Instant,
    val amount: Long,
    val status: BookingStatus,
    val paymentExpiresAt: Instant?
) {
    /** What the booking is to the user right now: an unpaid booking past its deadline is already lost. */
    fun statusAt(now: Instant): BookingStatus =
        if (status == BookingStatus.PENDING_PAYMENT && paymentExpiresAt != null && !paymentExpiresAt.isAfter(now)) BookingStatus.EXPIRED else status
}

data class PaymentIntent(val id: String, val status: String, val amount: Long, val checkoutUrl: String?, val sandbox: Boolean)

/** [passwordLogin] is false for an account that only ever signs in with Google: it has no password to confirm with. */
data class User(val id: String, val identifier: String, val displayName: String, val passwordLogin: Boolean = true)

data class Tokens(val accessToken: String, val refreshToken: String, val accessExpiresAt: Instant)

data class Session(val user: User, val tokens: Tokens)

data class OtpChallenge(val id: String, val expiresAt: Instant)

/** [googleClientId] is the backend's Google sign-in client; null when the backend does not offer Google sign-in. */
data class AppConfig(val sandboxPayment: Boolean, val feedbackEmail: String?, val googleClientId: String?)

data class Document(val title: String, val body: String)

enum class VenueSort(val query: String) { DISTANCE("distance"), RATING("rating"), PRICE("price") }

data class GeoPoint(val lat: Double, val lng: Double)

/** A request that did not give the screen what it asked for. */
sealed class BackendException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** The backend answered with a failure; [code] is its own error code when it gave one. */
class ApiException(val status: Int, val code: String, message: String) : BackendException(message) {
    val isUnauthorized: Boolean get() = status == 401
}

/** The backend could not be reached at all. */
class OfflineException(cause: Throwable) : BackendException(cause.message, cause)
