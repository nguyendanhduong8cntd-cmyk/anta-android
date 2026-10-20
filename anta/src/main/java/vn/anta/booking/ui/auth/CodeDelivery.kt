package vn.anta.booking.ui.auth

/** The backend sends one code a minute to an address; asking sooner is refused. */
internal const val RESEND_WAIT_SECONDS = 60
private const val MILLIS_PER_SECOND = 1_000L

/** Whole seconds until another code may be asked for, counted from [sentAt] on the same clock as [now]. */
internal fun resendSecondsLeft(sentAt: Long, now: Long): Int {
    val elapsed = (now - sentAt).coerceAtLeast(0)
    val left = RESEND_WAIT_SECONDS * MILLIS_PER_SECOND - elapsed
    return ((left + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt().coerceAtLeast(0)
}

/** How much of the wait has passed, from 0 just after a send to 1 once a new code may be asked for. */
internal fun resendProgress(sentAt: Long, now: Long): Float =
    ((now - sentAt).coerceAtLeast(0).toFloat() / (RESEND_WAIT_SECONDS * MILLIS_PER_SECOND)).coerceAtMost(1f)

/** Where a code went, and so where the user should look for it. */
internal enum class CodeChannel {
    EMAIL, SMS;

    companion object {
        /** [identifier] is already normalized: an email address or an E.164 phone number. */
        fun of(identifier: String) = if ('@' in identifier) EMAIL else SMS
    }
}
