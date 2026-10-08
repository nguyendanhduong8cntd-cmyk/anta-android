package vn.anta.booking.data

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** How values are worded on screen. Pure functions, so they are tested without a device. */
object Formats {
    /** "330.000 ₫" */
    fun price(amount: Long): String = "%,d".format(Locale.US, amount).replace(',', '.') + " ₫"

    /** "330K", "1,2 triệu": a price short enough for a card. */
    fun shortPrice(amount: Long): String {
        // The unit is chosen after rounding: 999.600 ₫ rounds to a million, not to "1000K".
        val thousands = Math.round(amount / 1_000.0)
        return when {
            thousands >= 1_000 -> {
                val tenths = Math.round(amount / 100_000.0)
                (if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10},${tenths % 10}") + " triệu"
            }
            amount >= 1_000 -> "${thousands}K"
            else -> "$amount ₫"
        }
    }

    /** "820 m", "1,3 km", "1.154 km" */
    fun distance(meters: Double?): String? = when {
        meters == null -> null
        meters < 1_000 -> "${meters.toInt()} m"
        meters < 100_000 -> "%.1f km".format(Locale.US, meters / 1_000).replace('.', ',')
        else -> "%,d km".format(Locale.US, Math.round(meters / 1_000)).replace(',', '.')
    }

    /** "05:00 – 24:00" */
    fun hours(opening: Int?, closing: Int?): String? =
        if (opening == null || closing == null) null else "%02d:00 – %02d:00".format(Locale.US, opening, closing)

    /** "4,7" */
    fun rating(value: Double): String = "%.1f".format(Locale.US, value).replace('.', ',')

    fun hourOfDay(instant: Instant, zone: ZoneId): String = "%02d:00".format(Locale.US, instant.atZone(zone).hour)

    /** "18:00 – 20:00" */
    fun timeRange(start: Instant, end: Instant, zone: ZoneId): String {
        // A slot that ends at midnight ends at 24:00 of its own day, not at 00:00.
        val endHour = end.atZone(zone).hour.let { if (it == 0 && end.isAfter(start)) 24 else it }
        return "${hourOfDay(start, zone)} – %02d:00".format(Locale.US, endHour)
    }

    fun weekday(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Thứ 2"
        DayOfWeek.TUESDAY -> "Thứ 3"
        DayOfWeek.WEDNESDAY -> "Thứ 4"
        DayOfWeek.THURSDAY -> "Thứ 5"
        DayOfWeek.FRIDAY -> "Thứ 6"
        DayOfWeek.SATURDAY -> "Thứ 7"
        DayOfWeek.SUNDAY -> "Chủ nhật"
    }

    /** "T6", "CN": a weekday small enough for a calendar strip. */
    fun weekdayShort(day: DayOfWeek): String = if (day == DayOfWeek.SUNDAY) "CN" else "T${day.value + 1}"

    /** "Hôm nay", "Ngày mai", otherwise "Thứ 6, 09/10". */
    fun day(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Hôm nay"
        today.plusDays(1) -> "Ngày mai"
        else -> "${weekday(date.dayOfWeek)}, %02d/%02d".format(Locale.US, date.dayOfMonth, date.monthValue)
    }

    /** "Thứ 6, 09/10/2026" */
    fun fullDate(instant: Instant, zone: ZoneId): String {
        val date = instant.atZone(zone).toLocalDate()
        return "${weekday(date.dayOfWeek)}, %02d/%02d/%d".format(Locale.US, date.dayOfMonth, date.monthValue, date.year)
    }

    /** "04:32" left on a countdown; "00:00" once it has run out. */
    fun countdown(remaining: Duration): String {
        val seconds = remaining.seconds.coerceAtLeast(0)
        return "%02d:%02d".format(Locale.US, seconds / 60, seconds % 60)
    }
}

/** What a user types to sign in: an email address or a Vietnamese mobile number. */
object Identifiers {
    private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    private val E164 = Regex("^\\+[1-9]\\d{7,14}$")

    /**
     * The identifier the backend expects, or null when the text is neither an email nor a phone number.
     * A number typed the local way ("0912 345 678") becomes international ("+84912345678").
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if ('@' in text) return text.lowercase(Locale.ROOT).takeIf(EMAIL::matches)
        val digits = text.filterNot { it == ' ' || it == '.' || it == '-' || it == '(' || it == ')' }
        val phone = when {
            digits.startsWith("+") -> digits
            digits.startsWith("84") && digits.length >= 11 -> "+$digits"
            digits.startsWith("0") -> "+84" + digits.drop(1)
            else -> return null
        }
        return phone.takeIf(E164::matches)
    }

    const val MIN_PASSWORD = 10
    const val MAX_PASSWORD = 128
}

/** Vietnamese wording for the failures a user can do something about; everything else gets one general line. */
object Messages {
    const val OFFLINE = "Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại."
    const val GENERAL = "Có lỗi xảy ra, vui lòng thử lại."
    const val SESSION_ENDED = "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại."

    private val BY_CODE = mapOf(
        "INVALID_CREDENTIALS" to "Tài khoản hoặc mật khẩu không đúng.",
        "INVALID_IDENTIFIER" to "Email hoặc số điện thoại không hợp lệ.",
        "INVALID_OTP" to "Mã xác thực không đúng hoặc đã hết hạn.",
        "INVALID_VERIFICATION" to "Phiên xác thực đã hết hạn, vui lòng lấy mã mới.",
        "RATE_LIMITED" to "Bạn thao tác quá nhanh. Vui lòng thử lại sau ít phút.",
        "SLOT_UNAVAILABLE" to "Khung giờ này vừa có người đặt. Vui lòng chọn giờ khác.",
        "HOLD_EXPIRED" to "Đã hết thời gian giữ chỗ. Vui lòng chọn lại giờ.",
        "INVALID_SLOT" to "Khung giờ không hợp lệ. Vui lòng chọn lại.",
        "OUTSIDE_OPENING_HOURS" to "Khung giờ nằm ngoài giờ mở cửa của sân.",
        "TARGET_NOT_PAYABLE" to "Lịch đặt này không còn thanh toán được.",
        "CANCELLATION_WINDOW_CLOSED" to "Đã quá sát giờ chơi nên không thể huỷ lịch này.",
        "INVALID_BOOKING_TRANSITION" to "Lịch đặt này không thể huỷ.",
        "PAYMENT_PROVIDER_UNAVAILABLE" to "Cổng thanh toán đang gián đoạn, vui lòng thử lại.",
        "PAYMENT_PROVIDER_REJECTED" to "Cổng thanh toán từ chối giao dịch, vui lòng thử lại.",
        "LAST_ADMIN" to "Hãy chuyển quyền quản trị trước khi xoá tài khoản này.",
        "ACCOUNT_LINK_REQUIRED" to "Email này đã có tài khoản Anta. Hãy đăng nhập bằng mật khẩu.",
        "ACCOUNT_UNAVAILABLE" to "Tài khoản này đã bị khoá hoặc đang chờ xoá.",
        "INVALID_SOCIAL_TOKEN" to "Google chưa xác nhận được tài khoản. Vui lòng thử lại.",
        "UNSUPPORTED_SOCIAL_IDENTITY" to "Tài khoản Google này chưa xác minh email.",
        "REAUTH_REQUIRED" to "Hãy đăng nhập lại bằng Google rồi thử lần nữa.",
        "INVALID_REAUTH" to "Tài khoản Google vừa chọn không phải tài khoản đang đăng nhập.",
        "INTEGRATION_NOT_CONFIGURED" to "Máy chủ chưa bật dịch vụ này.",
        "DEPENDENCY_UNAVAILABLE" to "Máy chủ đang bận, vui lòng thử lại sau."
    )

    fun of(error: Throwable): String = when (error) {
        is OfflineException -> OFFLINE
        is ApiException -> BY_CODE[error.code] ?: if (error.isUnauthorized) SESSION_ENDED else GENERAL
        else -> GENERAL
    }
}
