package vn.anta.booking

import android.app.Application
import org.json.JSONObject
import vn.anta.booking.data.ApiClient
import vn.anta.booking.data.ApiJson
import vn.anta.booking.data.DevelopmentBackend

/** Sets up, before the first screen, what this build needs to receive verification codes a local backend pushes. */
fun installDevelopmentTools(app: Application) = DevelopmentPush.install(app)

/** The development helpers of this build: present when local.properties gives the local backend's key (anta.devKey). */
fun developmentBackend(api: ApiClient): DevelopmentBackend? =
    BuildConfig.DEV_KEY.takeIf(String::isNotEmpty)?.let { LocalDevelopmentBackend(api, it) }

private class LocalDevelopmentBackend(private val api: ApiClient, key: String) : DevelopmentBackend {
    private val headers = mapOf("X-Dev-Key" to key)

    override suspend fun sendCode(body: JSONObject): String {
        // With the key and this phone's push token, a local backend also pushes the code here.
        val request = JSONObject(body.toString())
        DevelopmentPush.token()?.let { request.put("fcmToken", it) }
        return api.post("/public/auth/otp/send", request, headers = headers)
    }

    override suspend fun verificationCode(challengeId: String): String =
        ApiJson.developmentCode(api.get("/dev/otp/$challengeId", headers = headers))

    override suspend fun settle(intentId: String): Boolean =
        ApiJson.paymentOutcome(api.post("/dev/payment-intents/$intentId/pay", signedIn = true, headers = headers)) == "SUCCEEDED"
}
