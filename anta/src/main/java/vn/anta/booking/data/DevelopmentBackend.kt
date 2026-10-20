package vn.anta.booking.data

import org.json.JSONObject

/**
 * What only a debug build beside a local backend can do. That backend may have no way to deliver a verification code
 * and has no payment provider, so it lets a developer read the code and settle a sandbox payment with a development key.
 *
 * The implementation lives in the debug source set. A release build has none: its code never names these routes.
 */
interface DevelopmentBackend {
    /**
     * Asks for a verification code ([body] as `POST public/auth/otp/send` takes it) with the development key, so that
     * the backend can also push the code to this phone. Returns the backend's reply.
     */
    suspend fun sendCode(body: JSONObject): String

    /** The verification code the backend generated for a challenge but could not send. */
    suspend fun verificationCode(challengeId: String): String

    /** Settles a sandbox payment. True when the thing paid for is now confirmed. */
    suspend fun settle(intentId: String): Boolean
}
