package vn.anta.booking.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** How the app asks for a verification code, with and without a debug build's development helpers. */
class CodeRequestTest {
    private object NoSession : SessionHolder {
        override val current: Session? = null
        override val deviceId = "device-1"
        override suspend fun save(session: Session) = Unit
        override fun clear() = Unit
    }

    private class Helpers : DevelopmentBackend {
        val sent = mutableListOf<String>()
        override suspend fun sendCode(body: JSONObject): String {
            sent += body.toString()
            return """{"data":{"challengeId":"c-dev","expiresAt":"2026-10-08T10:05:00Z"},"meta":{}}"""
        }

        override suspend fun verificationCode(challengeId: String) = error("not used")
        override suspend fun settle(intentId: String) = error("not used")
    }

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun repository(development: DevelopmentBackend?) =
        Repository(ApiClient(server.url("/").toString(), NoSession, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()), NoSession, development)

    @Test
    fun `a release build asks the backend for a code with the identifier and what it is for`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":{"challengeId":"c1","expiresAt":"2026-10-08T10:05:00Z"},"meta":{}}"""))

        val challenge = repository(development = null).sendCode("+84912345678", OtpPurpose.REGISTER)

        val request = server.takeRequest()
        assertEquals("/public/auth/otp/send", request.path)
        assertEquals("""{"identifier":"+84912345678","purpose":"register"}""", request.body.readUtf8())
        assertEquals("c1", challenge.id)
    }

    @Test
    fun `a debug build asks through its development helpers, which can have the code pushed to the phone`() = runBlocking {
        val helpers = Helpers()

        val challenge = repository(helpers).sendCode("an@example.com", OtpPurpose.RESET_PASSWORD)

        assertEquals(listOf("""{"identifier":"an@example.com","purpose":"reset-password"}"""), helpers.sent)
        assertEquals("c-dev", challenge.id)
        assertEquals(0, server.requestCount)
    }
}
