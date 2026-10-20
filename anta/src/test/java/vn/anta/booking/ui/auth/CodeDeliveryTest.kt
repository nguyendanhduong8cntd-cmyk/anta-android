package vn.anta.booking.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/** When a new code may be asked for, and where the user is told to look for the one sent. */
class CodeDeliveryTest {
    @Test
    fun `a new code can be asked for one minute after the last one`() {
        assertEquals(60, resendSecondsLeft(sentAt = 1_000, now = 1_000))
        assertEquals(1, resendSecondsLeft(sentAt = 1_000, now = 60_999))
        assertEquals(0, resendSecondsLeft(sentAt = 1_000, now = 61_000))
        assertEquals(0, resendSecondsLeft(sentAt = 1_000, now = 500_000))
        // A clock that reads earlier than the send never extends the wait past one minute.
        assertEquals(60, resendSecondsLeft(sentAt = 5_000, now = 1_000))
    }

    @Test
    fun `the ring fills as the wait runs out`() {
        assertEquals(0f, resendProgress(sentAt = 1_000, now = 1_000), 0f)
        assertEquals(0.5f, resendProgress(sentAt = 1_000, now = 31_000), 0.001f)
        assertEquals(1f, resendProgress(sentAt = 1_000, now = 90_000), 0f)
        assertEquals(0f, resendProgress(sentAt = 5_000, now = 1_000), 0f)
    }

    @Test
    fun `an emailed code is looked for in the mailbox and a texted one in the messages`() {
        assertEquals(CodeChannel.EMAIL, CodeChannel.of("an@example.com"))
        assertEquals(CodeChannel.SMS, CodeChannel.of("+84912345678"))
    }
}
