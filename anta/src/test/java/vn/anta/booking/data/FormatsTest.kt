package vn.anta.booking.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FormatsTest {
    private val vietnam = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun `prices print in full with dots and short for cards`() {
        assertEquals("330.000 ₫", Formats.price(330_000))
        assertEquals("1.200.000 ₫", Formats.price(1_200_000))
        assertEquals("0 ₫", Formats.price(0))
        assertEquals("80K", Formats.shortPrice(80_000))
        assertEquals("330K", Formats.shortPrice(330_000))
        assertEquals("1 triệu", Formats.shortPrice(1_000_000))
        assertEquals("999K", Formats.shortPrice(999_499))
        assertEquals("1 triệu", Formats.shortPrice(999_500))
        assertEquals("1,2 triệu", Formats.shortPrice(1_200_000))
        assertEquals("500 ₫", Formats.shortPrice(500))
    }

    @Test
    fun `distance prints metres, then kilometres with one decimal, then whole kilometres`() {
        assertEquals("820 m", Formats.distance(820.0))
        assertEquals("1,3 km", Formats.distance(1_287.0))
        assertEquals("99,9 km", Formats.distance(99_900.0))
        assertEquals("1.154 km", Formats.distance(1_154_200.0))
        assertNull(Formats.distance(null))
    }

    @Test
    fun `opening hours need both ends`() {
        assertEquals("05:00 – 24:00", Formats.hours(5, 24))
        assertNull(Formats.hours(5, null))
        assertNull(Formats.hours(null, 22))
    }

    @Test
    fun `a rating prints with a comma`() {
        assertEquals("4,7", Formats.rating(4.7))
        assertEquals("5,0", Formats.rating(5.0))
    }

    @Test
    fun `a time range is in the venue's time zone and a slot ending at midnight ends at 24`() {
        val start = Instant.parse("2026-10-09T11:00:00Z") // 18:00 in Vietnam
        assertEquals("18:00 – 20:00", Formats.timeRange(start, start.plusSeconds(7200), vietnam))
        assertEquals("22:00 – 24:00", Formats.timeRange(Instant.parse("2026-10-09T15:00:00Z"), Instant.parse("2026-10-09T17:00:00Z"), vietnam))
        assertEquals("Thứ 6, 09/10/2026", Formats.fullDate(start, vietnam))
    }

    @Test
    fun `days are named relative to today, then by weekday`() {
        val today = LocalDate.of(2026, 10, 8)
        assertEquals("Hôm nay", Formats.day(today, today))
        assertEquals("Ngày mai", Formats.day(today.plusDays(1), today))
        assertEquals("Thứ 7, 10/10", Formats.day(today.plusDays(2), today))
        assertEquals("Chủ nhật, 11/10", Formats.day(today.plusDays(3), today))
    }

    @Test
    fun `a countdown never goes below zero`() {
        assertEquals("04:32", Formats.countdown(Duration.ofSeconds(272)))
        assertEquals("00:00", Formats.countdown(Duration.ofSeconds(-5)))
    }

    @Test
    fun `an email is lower-cased and a local phone number becomes international`() {
        assertEquals("an@example.com", Identifiers.normalize("  An@Example.com "))
        assertEquals("+84912345678", Identifiers.normalize("0912 345 678"))
        assertEquals("+84912345678", Identifiers.normalize("84912345678"))
        assertEquals("+84912345678", Identifiers.normalize("+84 912-345-678"))
    }

    @Test
    fun `text that is neither an email nor a phone number is refused`() {
        for (text in listOf("", "an", "an@", "an@example", "0123", "912345678", "+0912345678", "abc@ def.com")) assertNull(text, Identifiers.normalize(text))
    }

    @Test
    fun `known failures get their own wording and unknown ones the general line`() {
        assertEquals("Tài khoản hoặc mật khẩu không đúng.", Messages.of(ApiException(401, "INVALID_CREDENTIALS", "Invalid credentials")))
        assertEquals(Messages.SESSION_ENDED, Messages.of(ApiException(401, "INVALID_REFRESH_TOKEN", "x")))
        assertEquals(Messages.GENERAL, Messages.of(ApiException(500, "HTTP_500", "x")))
        assertEquals(Messages.OFFLINE, Messages.of(OfflineException(java.io.IOException("timeout"))))
        assertEquals(Messages.GENERAL, Messages.of(IllegalStateException("x")))
    }

    @Test
    fun `distance between two points matches the map`() {
        // Ben Thanh market to Notre-Dame cathedral in Ho Chi Minh City is roughly 900 m.
        val meters = Repository.distanceMeters(GeoPoint(10.7725, 106.6980), GeoPoint(10.7798, 106.6990))
        assertEquals(820.0, meters, 30.0)
    }

    @Test
    fun `a recent search moves to the front once, ignoring case, and the list stays short`() {
        assertEquals(listOf("cầu lông", "tennis"), RecentSearches.remember(listOf("tennis", "Cầu Lông"), " cầu lông "))
        assertEquals(listOf("tennis"), RecentSearches.remember(listOf("tennis"), "   "))
        assertEquals(RecentSearches.LIMIT, RecentSearches.remember((1..20).map { "t$it" }, "mới").size)
    }

    @Test
    fun `a stored session comes back as it was and damaged text is no session`() {
        val session = Session(User("u1", "an@example.com", "An \"Bình\""), Tokens("access", "refresh", Instant.ofEpochMilli(1_780_000_000_123)))

        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
        val googleOnly = session.copy(user = session.user.copy(passwordLogin = false))
        assertEquals(googleOnly, SessionCodec.decode(SessionCodec.encode(googleOnly)))
        // A session stored before accounts could be without a password was a password account.
        assertEquals(true, SessionCodec.decode(SessionCodec.encode(googleOnly).replace("\"passwordLogin\":false,", ""))?.user?.passwordLogin)
        assertNull(SessionCodec.decode("{\"id\":\"u1\"}"))
        assertNull(SessionCodec.decode("not json"))
    }
}
