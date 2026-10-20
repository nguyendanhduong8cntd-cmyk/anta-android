package vn.anta.booking.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Reads the backend's JSON. Every reply is an envelope: `{"data": ..., "meta": {...}}`, or
 * `{"data": null, "error": {"code", "message"}}` when the request failed.
 *
 * A list never fails because of one bad item: the item is left out and the rest is shown.
 */
object ApiJson {
    val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Ho_Chi_Minh")
    private val GOOGLE_CLIENT_ID = Regex("^[\\w-]+\\.apps\\.googleusercontent\\.com$")

    fun venuePage(body: String): VenuePage {
        val data = dataObject(body)
        return VenuePage(data.getJSONArray("items").objects().mapNotNull { attempt { venue(it) } }, data.optInt("page", 1), data.optInt("total", 0))
    }

    /** A plain list of venues, such as the user's favourites. */
    fun venues(body: String): List<Venue> = dataArray(body).objects().mapNotNull { attempt { venue(it) } }

    fun sports(body: String): List<Sport> = dataArray(body).objects().mapNotNull {
        attempt { Sport(it.required("id"), it.required("name"), it.required("slug"), it.optInt("venue_count", 0)) }
    }

    fun venueDetail(body: String, servicesBody: String?): VenueDetail {
        val data = dataObject(body)
        val services = servicesBody?.let { text -> attempt { dataArray(text) } }?.objects().orEmpty()
        return VenueDetail(
            venue = venue(data),
            zone = zone(data.text("timezone")),
            rules = data.optJSONArray("rules").strings(),
            yards = data.optJSONArray("yards").objects().mapNotNull {
                attempt { Yard(it.required("id"), it.text("sport_id"), it.required("name"), it.getLong("hourly_price")) }
            },
            services = services.mapNotNull { attempt { VenueService(it.required("name"), it.getLong("price")) } },
            priceLists = priceLists(data.text("description").orEmpty())
        )
    }

    fun slots(body: String): List<Slot> = dataArray(body).objects().mapNotNull {
        attempt {
            Slot(it.required("yard_id"), it.required("name"), instant(it, "starts_at"), instant(it, "ends_at"), it.getLong("amount"), it.getBoolean("available"))
        }
    }

    fun hold(body: String): Hold {
        val data = dataObject(body)
        return Hold(data.required("holdId"), instant(data, "expiresAt"), data.getJSONObject("quote").getLong("total"))
    }

    fun booking(body: String): Booking = booking(dataObject(body))

    fun bookings(body: String): List<Booking> = dataObject(body).getJSONArray("items").objects().mapNotNull { attempt { booking(it) } }

    fun paymentIntent(body: String): PaymentIntent {
        val data = dataObject(body)
        return PaymentIntent(data.required("id"), data.required("status"), data.getLong("amount"), data.text("checkoutUrl")?.takeIf { it.startsWith("https://") }, data.optBoolean("sandbox"))
    }

    /** `SUCCEEDED` when the payment confirmed the booking. */
    fun paymentOutcome(body: String): String = dataObject(body).required("outcome")

    fun tokens(body: String, now: Instant): Tokens {
        val data = dataObject(body)
        return Tokens(data.required("accessToken"), data.required("refreshToken"), now.plusSeconds(data.optLong("expiresIn", 900)))
    }

    fun user(body: String): User {
        val data = dataObject(body)
        return User(data.required("id"), data.required("identifier"), data.required("displayName"), data.optBoolean("passwordLogin", true))
    }

    fun otpChallenge(body: String): OtpChallenge {
        val data = dataObject(body)
        return OtpChallenge(data.required("challengeId"), instant(data, "expiresAt"), data.optString("delivery") == "development-only")
    }

    fun verificationToken(body: String): String = dataObject(body).required("verificationToken")

    fun developmentCode(body: String): String = dataObject(body).required("code")

    fun config(body: String): AppConfig {
        val data = dataObject(body)
        val features = data.optJSONObject("features")
        // A placeholder address is no address: the feedback entry is hidden rather than opening a dead mailbox.
        val email = data.text("feedbackEmail")?.takeIf { '@' in it && !it.endsWith("@your-domain.com") }
        return AppConfig(features?.optBoolean("sandboxPayment") == true, email, data.text("googleClientId")?.takeIf(GOOGLE_CLIENT_ID::matches))
    }

    fun document(body: String): Document {
        val data = dataObject(body)
        return Document(data.required("title"), data.text("body").orEmpty())
    }

    /** The failure a non-2xx reply describes. A reply that is not the envelope still yields an error for its status. */
    fun error(status: Int, body: String): ApiException {
        val error = attempt { JSONObject(body).optJSONObject("error") }
        return ApiException(status, error?.text("code") ?: "HTTP_$status", error?.text("message") ?: "HTTP $status")
    }

    /**
     * A venue's description is its published price lists, one per line:
     * `"<title>: <time band>: <price>; <time band>: <price>."`. Lines that are not a price list are ignored.
     */
    fun priceLists(description: String): List<PriceList> = description.lines().mapNotNull { line ->
        val split = line.indexOf(": ")
        val slots = if (split <= 0) "" else line.substring(split + 2).trim().removeSuffix(".")
        if ('₫' !in slots) null else PriceList(line.substring(0, split).trim(), slots.split("; ").map(String::trim).filter(String::isNotEmpty))
    }

    private fun venue(item: JSONObject) = Venue(
        id = item.required("id"),
        name = item.required("name"),
        address = item.text("address").orEmpty(),
        lat = item.getDouble("lat"),
        lng = item.getDouble("lng"),
        distanceMeters = if (item.isNull("distance_meters")) null else item.getDouble("distance_meters"),
        openingHour = if (item.isNull("opening_hour")) null else item.getInt("opening_hour"),
        closingHour = if (item.isNull("closing_hour")) null else item.getInt("closing_hour"),
        images = item.optJSONArray("images").strings().filter(::isWebLink),
        rating = if (item.isNull("rating")) null else item.getDouble("rating"),
        reviewCount = item.optInt("review_count", 0),
        minPrice = if (item.isNull("min_price")) null else item.getLong("min_price"),
        sports = item.optJSONArray("sports").objects().mapNotNull { attempt { SportRef(it.required("slug"), it.required("name")) } }
    )

    private fun booking(item: JSONObject) = Booking(
        id = item.required("id"),
        branchId = item.required("branch_id"),
        branchName = item.text("branch_name").orEmpty(),
        branchAddress = item.text("branch_address").orEmpty(),
        branchImage = item.text("branch_image")?.takeIf(::isWebLink),
        yardName = item.text("yard_name").orEmpty(),
        zone = zone(item.text("branch_timezone")),
        startsAt = instant(item, "starts_at"),
        endsAt = instant(item, "ends_at"),
        amount = item.getLong("amount"),
        status = BookingStatus.entries.firstOrNull { it.name == item.text("status") } ?: BookingStatus.UNKNOWN,
        paymentExpiresAt = item.text("payment_expires_at")?.let(Instant::parse)
    )

    private fun dataObject(body: String): JSONObject = JSONObject(body).getJSONObject("data")
    private fun dataArray(body: String): JSONArray = JSONObject(body).getJSONArray("data")
    private fun instant(item: JSONObject, key: String): Instant = Instant.parse(item.required(key))
    private fun zone(name: String?): ZoneId = name?.let { attempt { ZoneId.of(it) } } ?: DEFAULT_ZONE
    private fun isWebLink(url: String) = url.startsWith("https://") || url.startsWith("http://")

    /** The value as text, or null when it is absent, JSON null or blank. Android's `optString` turns JSON null into "null". */
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONObject.required(key: String): String = text(key) ?: throw JSONException("Missing $key")
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index -> if (isNull(index)) null else optString(index).takeIf { it.isNotBlank() } }

    /** Null instead of an exception, for one malformed item among good ones. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (_: JSONException) {
        null
    } catch (_: java.time.DateTimeException) {
        null
    }
}
