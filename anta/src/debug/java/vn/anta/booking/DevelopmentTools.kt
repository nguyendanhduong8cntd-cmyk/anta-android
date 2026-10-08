package vn.anta.booking

import vn.anta.booking.data.ApiClient
import vn.anta.booking.data.ApiJson
import vn.anta.booking.data.DevelopmentBackend

/** The development helpers of this build: present when local.properties gives the local backend's key (anta.devKey). */
fun developmentBackend(api: ApiClient): DevelopmentBackend? =
    BuildConfig.DEV_KEY.takeIf(String::isNotEmpty)?.let { LocalDevelopmentBackend(api, it) }

private class LocalDevelopmentBackend(private val api: ApiClient, key: String) : DevelopmentBackend {
    private val headers = mapOf("X-Dev-Key" to key)

    override suspend fun verificationCode(challengeId: String): String =
        ApiJson.developmentCode(api.get("/dev/otp/$challengeId", headers = headers))

    override suspend fun settle(intentId: String): Boolean =
        ApiJson.paymentOutcome(api.post("/dev/payment-intents/$intentId/pay", signedIn = true, headers = headers)) == "SUCCEEDED"
}
