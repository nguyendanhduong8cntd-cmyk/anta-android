package vn.anta.booking.data

/**
 * What only a debug build beside a local backend can do. That backend has no way to deliver a verification code
 * and no payment provider, so it lets a developer read the code and settle a sandbox payment with a development key.
 *
 * The implementation lives in the debug source set. A release build has none: its code never names these routes.
 */
interface DevelopmentBackend {
    /** The verification code the backend generated for a challenge but could not send. */
    suspend fun verificationCode(challengeId: String): String

    /** Settles a sandbox payment. True when the thing paid for is now confirmed. */
    suspend fun settle(intentId: String): Boolean
}
