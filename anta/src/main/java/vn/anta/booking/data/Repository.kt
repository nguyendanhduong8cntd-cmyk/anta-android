package vn.anta.booking.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONException
import org.json.JSONObject
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Everything the screens ask the backend for. */
class Repository(
    private val api: ApiClient,
    private val sessions: SessionHolder,
    private val development: DevelopmentBackend?,
    private val clock: Clock = Clock.systemUTC()
) {
    /** True in a debug build that was given the local backend's development key. */
    val hasDevelopmentKey: Boolean get() = development != null

    // ---- Venues ----

    suspend fun sports(): List<Sport> = parse { ApiJson.sports(api.get("/public/sports")) }

    /** Venues nearest first when [near] is known; otherwise in the order [sort] gives. */
    suspend fun venues(
        near: GeoPoint?,
        sportId: String? = null,
        search: String? = null,
        sort: VenueSort = VenueSort.DISTANCE,
        page: Int = 1,
        pageSize: Int = VenuePage.PAGE_SIZE
    ): VenuePage = parse {
        ApiJson.venuePage(api.get("/public/branches", mapOf(
            "lat" to near?.lat?.toString(), "lng" to near?.lng?.toString(), "radius" to near?.let { ANYWHERE_METERS },
            "sportId" to sportId, "q" to search?.trim()?.take(MAX_SEARCH)?.takeIf(String::isNotEmpty), "sort" to sort.query,
            "page" to page.toString(), "pageSize" to pageSize.toString()
        )))
    }

    suspend fun venue(id: String, near: GeoPoint?): VenueDetail = coroutineScope {
        val detail = async { api.get("/public/branches/$id") }
        // Extras are optional: a venue still opens when its services cannot be loaded.
        val services = async { optional { api.get("/public/branches/$id/services") } }
        val parsed = parse { ApiJson.venueDetail(detail.await(), services.await()) }
        parsed.copy(venue = parsed.venue.copy(distanceMeters = near?.let { distanceMeters(it, GeoPoint(parsed.venue.lat, parsed.venue.lng)) }))
    }

    /** Every bookable hour of [date] at a venue, in the venue's own time zone. Hours already past are not listed. */
    suspend fun slots(venueId: String, date: LocalDate, zone: ZoneId): List<Slot> = parse {
        val from = date.atStartOfDay(zone).toInstant()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant()
        ApiJson.slots(api.get("/public/branches/$venueId/availability", mapOf("from" to from.toString(), "to" to to.toString())))
    }

    // ---- Favourites ----

    /** The signed-in user's favourite venues, the latest addition first. */
    suspend fun favorites(near: GeoPoint?): List<Venue> = parse {
        ApiJson.venues(api.get("/users/me/favorites", signedIn = true)).map { venue ->
            venue.copy(distanceMeters = near?.let { distanceMeters(it, GeoPoint(venue.lat, venue.lng)) })
        }
    }

    suspend fun setFavorite(venueId: String, favorite: Boolean) {
        if (favorite) api.post("/users/me/favorites/$venueId", signedIn = true) else api.delete("/users/me/favorites/$venueId")
    }

    // ---- Booking ----

    /**
     * Holds the picked hours and turns the hold into a booking that awaits payment. [attempt] names this try: repeating
     * it after a lost reply returns the same booking instead of holding the hours twice.
     */
    suspend fun book(selection: SlotSelection, attempt: String): Booking {
        val slot = JSONObject().put("yardId", selection.yardId).put("startsAt", selection.startsAt.toString()).put("endsAt", selection.endsAt.toString())
        val hold = parse { ApiJson.hold(api.post("/booking-holds", slot, signedIn = true, headers = idempotent("hold-$attempt"))) }
        val created = parse { ApiJson.booking(api.post("/bookings", JSONObject().put("holdId", hold.id), signedIn = true, headers = idempotent("book-$attempt"))) }
        // The creation reply has no venue or yard name; the booking read back does.
        return optional { booking(created.id) } ?: created
    }

    suspend fun bookings(): List<Booking> = parse { ApiJson.bookings(api.get("/users/me/bookings", mapOf("pageSize" to "100"), signedIn = true)) }

    suspend fun booking(id: String): Booking = parse { ApiJson.booking(api.get("/bookings/$id", signedIn = true)) }

    suspend fun cancel(bookingId: String) {
        api.post("/bookings/$bookingId/cancel", signedIn = true, headers = idempotent("cancel-$bookingId"))
    }

    suspend fun paymentIntent(bookingId: String): PaymentIntent = parse {
        ApiJson.paymentIntent(api.post("/payment-intents", JSONObject().put("bookingId", bookingId), signedIn = true, headers = idempotent("pay-$bookingId")))
    }

    /** Settles a sandbox payment on a local backend. True when the booking is now confirmed; false without the development helpers. */
    suspend fun developmentPay(intentId: String): Boolean = parse { development?.settle(intentId) ?: false }

    // ---- Account ----

    suspend fun login(identifier: String, password: String): Session {
        val body = JSONObject().put("identifier", identifier).put("password", password).put("deviceId", sessions.deviceId)
        val tokens = parse { ApiJson.tokens(api.post("/public/auth/login", body), clock.instant()) }
        return signIn(tokens)
    }

    /**
     * Signs in with the proof Google gave the phone ([idToken]). The backend checks it with Google; a Google account
     * it has not seen before gets a new Anta account.
     */
    suspend fun googleLogin(idToken: String): Session {
        val body = JSONObject().put("idToken", idToken).put("deviceId", sessions.deviceId)
        val tokens = parse { ApiJson.tokens(api.post("/public/auth/social", body), clock.instant()) }
        return signIn(tokens)
    }

    suspend fun sendCode(identifier: String, purpose: OtpPurpose): OtpChallenge = parse {
        ApiJson.otpChallenge(api.post("/public/auth/otp/send", JSONObject().put("identifier", identifier).put("purpose", purpose.query)))
    }

    /** The proof that the code was right; it is spent by [register] or [resetPassword]. */
    suspend fun verifyCode(challengeId: String, code: String): String = parse {
        ApiJson.verificationToken(api.post("/public/auth/otp/verify", JSONObject().put("challengeId", challengeId).put("code", code)))
    }

    /** The code a local backend generated but has no way to deliver; null without the development helpers. */
    suspend fun developmentCode(challengeId: String): String? = parse { development?.verificationCode(challengeId) }

    suspend fun register(identifier: String, displayName: String, password: String, verificationToken: String): Session {
        val body = JSONObject().put("identifier", identifier).put("displayName", displayName.trim()).put("password", password)
            .put("verificationToken", verificationToken).put("deviceId", sessions.deviceId)
        val tokens = parse { ApiJson.tokens(api.post("/public/auth/register", body), clock.instant()) }
        return signIn(tokens)
    }

    suspend fun resetPassword(identifier: String, password: String, verificationToken: String) {
        api.post("/public/auth/reset-password", JSONObject().put("identifier", identifier).put("password", password).put("verificationToken", verificationToken))
    }

    suspend fun rename(displayName: String): User {
        val user = parse { ApiJson.user(api.patch("/users/me", JSONObject().put("displayName", displayName.trim()))) }
        sessions.current?.let { sessions.save(it.copy(user = user)) }
        return user
    }

    /** Ends the session here even when the backend cannot be told: the tokens are dropped either way. */
    suspend fun logout() {
        optional { api.post("/auth/logout", signedIn = true) }
        sessions.clear()
    }

    /**
     * Asks for the account to be deleted. The backend erases personal data after its grace period. The user proves
     * it is them once more: with their password, or with a new Google sign-in when the account has no password.
     */
    suspend fun deleteAccount(proof: AccountProof) {
        val body = when (proof) {
            is AccountProof.Password -> JSONObject().put("password", proof.password)
            is AccountProof.Google -> JSONObject().put("idToken", proof.idToken)
        }
        api.delete("/users/me", body)
        sessions.clear()
    }

    // ---- App ----

    suspend fun config(): AppConfig = parse { ApiJson.config(api.get("/public/config")) }

    suspend fun document(slug: String): Document = parse { ApiJson.document(api.get("/public/content/$slug")) }

    /** The session only exists once the profile is known, so no screen ever sees a signed-in user without a name. */
    private suspend fun signIn(tokens: Tokens): Session {
        val user = parse { ApiJson.user(api.getAs(tokens.accessToken, "/users/me")) }
        return Session(user, tokens).also { sessions.save(it) }
    }

    /** Null when an extra the screen can do without fails to load. Cancellation still propagates. */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (_: BackendException) {
        null
    }

    private fun idempotent(key: String) = mapOf("Idempotency-Key" to key)

    /** A reply the app cannot read is the backend's fault, not a crash. */
    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (error: JSONException) {
        throw ApiException(502, "BAD_RESPONSE", error.message ?: "Unreadable response")
    } catch (error: DateTimeException) {
        throw ApiException(502, "BAD_RESPONSE", error.message ?: "Unreadable response")
    }

    companion object {
        /** The widest search the backend allows: nearest first, however far the nearest is. A user abroad still sees venues. */
        private const val ANYWHERE_METERS = "20100000"
        private const val MAX_SEARCH = 100

        fun newAttempt(): String = UUID.randomUUID().toString()

        /** Great-circle distance, accurate to well under a percent at these ranges. */
        fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
            val earthRadius = 6_371_000.0
            val dLat = Math.toRadians(to.lat - from.lat)
            val dLng = Math.toRadians(to.lng - from.lng)
            val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(from.lat)) * Math.cos(Math.toRadians(to.lat)) * Math.sin(dLng / 2).let { it * it }
            return 2 * earthRadius * Math.asin(Math.sqrt(a))
        }
    }
}

enum class OtpPurpose(val query: String) { REGISTER("register"), RESET_PASSWORD("reset-password") }

/** How a signed-in user confirms it is still them before something that cannot be undone. */
sealed interface AccountProof {
    class Password(val password: String) : AccountProof
    class Google(val idToken: String) : AccountProof
}
