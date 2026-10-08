package vn.anta.booking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ApiJsonTest {
    private val listBody = """
        {"data":{"items":[
          {"id":"a1","name":"Pickleball Hoàng Minh","address":"32-38 Lê Anh Xuân, Quận 1","timezone":"Asia/Ho_Chi_Minh",
           "opening_hour":6,"closing_hour":22,"images":["https://files.example/cover.jpg","javascript:alert(1)",""],
           "rating":4.7,"review_count":12,"min_price":300000,"sports":[{"slug":"pickleball","name":"Pickleball"},{"slug":null}],
           "lat":10.7712,"lng":106.6954,"distance_meters":820},
          {"id":"b2","name":"Sân demo","address":null,"images":[],"rating":null,"review_count":0,"min_price":null,"sports":[],
           "lat":16.07,"lng":108.22,"distance_meters":null},
          {"id":"bad","name":"Thiếu toạ độ","address":"x","images":[],"lat":null,"lng":106.7},
          {"id":null,"name":"Thiếu mã","address":"x","images":[],"lat":10.7,"lng":106.7}
        ],"page":2,"pageSize":20,"total":41},"meta":{"requestId":"r"}}
    """.trimIndent()

    @Test
    fun `a listed venue keeps what a card shows and only web image links`() {
        val page = ApiJson.venuePage(listBody)

        assertEquals(
            Venue("a1", "Pickleball Hoàng Minh", "32-38 Lê Anh Xuân, Quận 1", 10.7712, 106.6954, 820.0, 6, 22,
                listOf("https://files.example/cover.jpg"), 4.7, 12, 300000, listOf(SportRef("pickleball", "Pickleball"))),
            page.items[0]
        )
        assertEquals(2 to 41, page.page to page.total)
        assertTrue(page.hasMore)
    }

    @Test
    fun `a venue without rating, price, hours or position data shows none of them`() {
        val venue = ApiJson.venuePage(listBody).items[1]

        assertEquals("", venue.address)
        assertNull(venue.distanceMeters)
        assertNull(venue.openingHour)
        assertNull(venue.rating)
        assertNull(venue.minPrice)
        assertNull(venue.cover)
    }

    @Test
    fun `a malformed venue is left out without hiding the others`() {
        assertEquals(listOf("a1", "b2"), ApiJson.venuePage(listBody).items.map(Venue::id))
    }

    @Test
    fun `the last page has no more to load`() {
        val page = ApiJson.venuePage("""{"data":{"items":[{"id":"a","name":"A","lat":1,"lng":2}],"page":3,"pageSize":20,"total":41}}""")

        assertFalse(page.hasMore)
        assertFalse(ApiJson.venuePage("""{"data":{"items":[],"page":1,"pageSize":20,"total":0}}""").hasMore)
    }

    @Test
    fun `sports carry how many venues offer them`() {
        val sports = ApiJson.sports("""{"data":[{"id":"s1","name":"Cầu lông","slug":"badminton","venue_count":18},{"id":"s2","name":null,"slug":"x"}]}""")

        assertEquals(listOf(Sport("s1", "Cầu lông", "badminton", 18)), sports)
    }

    @Test
    fun `venue detail carries yards, rules, price lists and the separately loaded services`() {
        val body = """{"data":{"id":"a1","name":"Hyperhall","address":"47 Đường Số 29","timezone":"Asia/Bangkok","opening_hour":6,"closing_hour":24,
            "description":"Nguyên Sân - Thuê 60 phút: T2 - CN 6h - 17h: 1.000.000 ₫; T2 - CN 17h - 24h: 1.200.000 ₫.\nSân 7: 6h - 15h: 880.000 ₫/1h30.",
            "images":["https://files.example/1.jpg"],"rules":["Mang giày đế bằng","",null],"lat":10.79,"lng":106.72,"rating":5,"review_count":3,
            "min_price":880000,"sports":[{"slug":"basketball","name":"Bóng rổ"}],
            "yards":[{"id":"y1","sport_id":"s","name":"Nguyên Sân","hourly_price":1200000},{"id":"y2","name":"Hỏng","hourly_price":"không rõ"}]}}"""
        val services = """{"data":[{"name":"Nước suối (chai)","price":10000},{"name":null,"price":1}]}"""

        val detail = ApiJson.venueDetail(body, services)

        assertEquals("Hyperhall", detail.venue.name)
        assertEquals(ZoneId.of("Asia/Bangkok"), detail.zone)
        assertEquals(listOf(Yard("y1", "s", "Nguyên Sân", 1200000)), detail.yards)
        assertEquals(listOf("Mang giày đế bằng"), detail.rules)
        assertEquals(listOf(VenueService("Nước suối (chai)", 10000)), detail.services)
        assertEquals(
            listOf(
                PriceList("Nguyên Sân - Thuê 60 phút", listOf("T2 - CN 6h - 17h: 1.000.000 ₫", "T2 - CN 17h - 24h: 1.200.000 ₫")),
                PriceList("Sân 7", listOf("6h - 15h: 880.000 ₫/1h30"))
            ),
            detail.priceLists
        )
    }

    @Test
    fun `a venue opens without services, and an unknown time zone falls back to Vietnam's`() {
        val body = """{"data":{"id":"a1","name":"Sân","timezone":"Mars/Olympus","description":null,"lat":10.7,"lng":106.7}}"""

        assertEquals(emptyList<VenueService>(), ApiJson.venueDetail(body, null).services)
        assertEquals(emptyList<VenueService>(), ApiJson.venueDetail(body, "<html>bad gateway</html>").services)
        assertEquals(ApiJson.DEFAULT_ZONE, ApiJson.venueDetail(body, null).zone)
    }

    @Test
    fun `free text in a description is not a price list`() {
        assertEquals(emptyList<PriceList>(), ApiJson.priceLists("Dữ liệu mẫu: không phải chi nhánh thật.\nGiờ mở cửa: 6h - 22h"))
        assertEquals(emptyList<PriceList>(), ApiJson.priceLists(""))
    }

    @Test
    fun `bookable hours are read with their yard, price and whether they are free`() {
        val slots = ApiJson.slots("""{"data":[
            {"yard_id":"y1","name":"Sân 1","amount":180000,"currency":"VND","starts_at":"2026-10-09T02:00:00.000Z","ends_at":"2026-10-09T03:00:00.000Z","available":true},
            {"yard_id":"y1","name":"Sân 1","amount":180000,"starts_at":"2026-10-09T03:00:00.000Z","ends_at":"2026-10-09T04:00:00.000Z","available":false},
            {"yard_id":"y1","name":"Sân 1","amount":180000,"starts_at":"hôm nay","ends_at":"2026-10-09T04:00:00.000Z","available":true}]}""")

        assertEquals(2, slots.size)
        assertEquals(Slot("y1", "Sân 1", Instant.parse("2026-10-09T02:00:00Z"), Instant.parse("2026-10-09T03:00:00Z"), 180000, true), slots[0])
        assertEquals(slots[0].hour + 1, slots[1].hour)
        assertFalse(slots[1].available)
    }

    @Test
    fun `a hold and the booking made from it are read`() {
        val hold = ApiJson.hold("""{"data":{"holdId":"h1","expiresAt":"2026-10-09T02:05:00.000Z","quote":{"total":360000,"currency":"VND"}}}""")
        val booking = ApiJson.booking("""{"data":{"id":"b1","branch_id":"a1","yard_id":"y1","starts_at":"2026-10-09T02:00:00.000Z",
            "ends_at":"2026-10-09T04:00:00.000Z","amount":360000,"status":"PENDING_PAYMENT","payment_expires_at":"2026-10-09T01:05:00.000Z",
            "branch_name":"Hyperhall","branch_address":"47 Đường Số 29","branch_timezone":"Asia/Ho_Chi_Minh","branch_image":"https://files.example/1.jpg","yard_name":"Sân 1"}}""")

        assertEquals(Hold("h1", Instant.parse("2026-10-09T02:05:00Z"), 360000), hold)
        assertEquals("Hyperhall" to "Sân 1", booking.branchName to booking.yardName)
        assertEquals(BookingStatus.PENDING_PAYMENT, booking.statusAt(Instant.parse("2026-10-09T01:04:59Z")))
        assertEquals(BookingStatus.EXPIRED, booking.statusAt(Instant.parse("2026-10-09T01:05:00Z")))
    }

    @Test
    fun `a booking list skips an unreadable booking and an unknown status stays unknown`() {
        val bookings = ApiJson.bookings("""{"data":{"items":[
            {"id":"b1","branch_id":"a1","starts_at":"2026-10-09T02:00:00.000Z","ends_at":"2026-10-09T03:00:00.000Z","amount":"180000","status":"REFUNDED"},
            {"id":"b2","branch_id":"a1","starts_at":null,"ends_at":"2026-10-09T03:00:00.000Z","amount":1,"status":"CONFIRMED"}],"total":2}}""")

        assertEquals(listOf("b1"), bookings.map(Booking::id))
        assertEquals(BookingStatus.UNKNOWN, bookings[0].status)
        assertEquals(180000L, bookings[0].amount)
        assertEquals("", bookings[0].branchName)
    }

    @Test
    fun `a payment intent only keeps a checkout page served over HTTPS`() {
        val sandbox = ApiJson.paymentIntent("""{"data":{"id":"p1","status":"CREATED","amount":360000,"checkoutUrl":null,"qrCode":null,"sandbox":true}}""")
        val unsafe = ApiJson.paymentIntent("""{"data":{"id":"p2","status":"CREATED","amount":1,"checkoutUrl":"intent://evil","sandbox":false}}""")
        val real = ApiJson.paymentIntent("""{"data":{"id":"p3","status":"CREATED","amount":1,"checkoutUrl":"https://pay.example/x","sandbox":false}}""")

        assertEquals(PaymentIntent("p1", "CREATED", 360000, null, true), sandbox)
        assertNull(unsafe.checkoutUrl)
        assertNull(ApiJson.paymentIntent("""{"data":{"id":"p4","status":"CREATED","amount":1,"checkoutUrl":"http://pay.example/x","sandbox":false}}""").checkoutUrl)
        assertEquals("https://pay.example/x", real.checkoutUrl)
    }

    @Test
    fun `tokens expire when the backend says, counted from now`() {
        val now = Instant.parse("2026-10-08T03:00:00Z")
        val tokens = ApiJson.tokens("""{"data":{"accessToken":"a","refreshToken":"r","expiresIn":900,"tokenType":"Bearer"}}""", now)

        assertEquals(Tokens("a", "r", Instant.parse("2026-10-08T03:15:00Z")), tokens)
    }

    @Test
    fun `account replies are read`() {
        assertEquals(User("u1", "an@example.com", "An"), ApiJson.user("""{"data":{"id":"u1","identifier":"an@example.com","displayName":"An","version":1}}"""))
        // An account made by Google sign-in has no password to confirm things with.
        assertFalse(ApiJson.user("""{"data":{"id":"u2","identifier":"b@gmail.com","displayName":"b","passwordLogin":false}}""").passwordLogin)
        assertEquals(OtpChallenge("c1", Instant.parse("2026-10-08T03:05:00Z")),
            ApiJson.otpChallenge("""{"data":{"challengeId":"c1","expiresAt":"2026-10-08T03:05:00.000Z","delivery":"development-only"}}"""))
        assertEquals("v-token", ApiJson.verificationToken("""{"data":{"verificationToken":"v-token","expiresIn":300}}"""))
        assertEquals("123456", ApiJson.developmentCode("""{"data":{"code":"123456"}}"""))
    }

    @Test
    fun `config tells whether payments are sandboxed and hides a placeholder feedback address`() {
        val placeholder = ApiJson.config("""{"data":{"features":{"sandboxPayment":true},"feedbackEmail":"support@your-domain.com"}}""")
        val real = ApiJson.config("""{"data":{"features":{"sandboxPayment":false},"feedbackEmail":"hello@anta.example"}}""")

        assertEquals(AppConfig(true, null, null), placeholder)
        assertEquals(AppConfig(false, "hello@anta.example", null), real)
        assertEquals(AppConfig(false, null, null), ApiJson.config("""{"data":{}}"""))
    }

    @Test
    fun `config carries the Google client only when the backend offers Google sign-in`() {
        assertEquals("123-abc.apps.googleusercontent.com", ApiJson.config("""{"data":{"googleClientId":"123-abc.apps.googleusercontent.com"}}""").googleClientId)
        assertNull(ApiJson.config("""{"data":{"googleClientId":null}}""").googleClientId)
        assertNull(ApiJson.config("""{"data":{"googleClientId":""}}""").googleClientId)
        assertNull(ApiJson.config("""{"data":{"googleClientId":"https://example.com/not-a-client-id"}}""").googleClientId)
    }

    @Test
    fun `a plain list of venues, such as the favourites, leaves out what it cannot read`() {
        val venues = ApiJson.venues("""{"data":[
            {"id":"a1","name":"Sân A","address":"1 Lê Lợi","images":["https://files.example/a.jpg"],"rating":4.5,"review_count":3,"min_price":80000,
             "sports":[{"slug":"badminton","name":"Cầu lông"}],"lat":10.77,"lng":106.69,"opening_hour":5,"closing_hour":23},
            {"id":"broken","name":"Thiếu toạ độ"}
        ],"meta":{}}""")

        assertEquals(listOf("a1"), venues.map { it.id })
        assertEquals(80000L, venues[0].minPrice)
        assertNull(venues[0].distanceMeters)
    }

    @Test
    fun `a failed reply yields the backend's code, and a reply that is not JSON still yields an error`() {
        val known = ApiJson.error(401, """{"data":null,"meta":{},"error":{"code":"INVALID_CREDENTIALS","message":"Invalid credentials"}}""")
        val unknown = ApiJson.error(502, "<html>Bad gateway</html>")

        assertEquals(Triple(401, "INVALID_CREDENTIALS", "Invalid credentials"), Triple(known.status, known.code, known.message))
        assertTrue(known.isUnauthorized)
        assertEquals(502 to "HTTP_502", unknown.status to unknown.code)
    }
}
