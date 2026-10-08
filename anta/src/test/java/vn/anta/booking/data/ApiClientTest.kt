package vn.anta.booking.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The backend ends every session of an account when a refresh token is used twice, so the rules for renewing one
 * are tested against a real HTTP server rather than trusted.
 */
class ApiClientTest {
    private class Sessions(start: Session?) : SessionHolder {
        @Volatile override var current: Session? = start
        override val deviceId = "device-1"
        val saved = CopyOnWriteArrayList<Session>()
        override suspend fun save(session: Session) {
            current = session
            saved += session
        }

        override fun clear() {
            current = null
        }
    }

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<String>()
    private val user = User("u1", "an@example.com", "An")
    private val live = Instant.now().plusSeconds(600)
    private val expired = Instant.now().minusSeconds(5)

    /** What the backend answers to a refresh; a test replaces it to make the backend refuse or dawdle. */
    private var onRefresh: () -> MockResponse = { ok("""{"accessToken":"access-2","refreshToken":"refresh-2","expiresIn":900}""") }

    /** The access token the backend currently honours. */
    private var honoured = "access-2"

    private fun ok(data: String) = MockResponse().setBody("""{"data":$data,"meta":{}}""")
    private fun refused(status: Int, code: String) = MockResponse().setResponseCode(status).setBody("""{"data":null,"error":{"code":"$code","message":"x"}}""")

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += "${request.method} ${request.path}"
                return when {
                    request.path == "/public/auth/refresh" -> onRefresh()
                    request.path == "/public/sports" -> ok("[]")
                    request.getHeader("Authorization") != "Bearer $honoured" -> refused(401, "UNAUTHORIZED")
                    request.method == "DELETE" -> refused(401, "INVALID_CREDENTIALS")
                    else -> ok("""{"id":"u1"}""")
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.shutdown()

    private fun client(sessions: SessionHolder) =
        ApiClient(server.url("/").toString(), sessions, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build())

    private fun session(access: String, expiresAt: Instant) = Session(user, Tokens(access, "refresh-1", expiresAt))
    private val refreshes get() = requests.count { it == "POST /public/auth/refresh" }

    @Test
    fun `a public request carries no token and a signed-in one carries the live token without renewing it`() = runBlocking {
        honoured = "access-1"
        val api = client(Sessions(session("access-1", live)))

        api.get("/public/sports")
        api.get("/users/me", signedIn = true)

        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer access-1", server.takeRequest().getHeader("Authorization"))
        assertEquals(0, refreshes)
    }

    @Test
    fun `requests that find the token expiring renew it once between them and the new tokens are stored`() = runBlocking {
        val sessions = Sessions(session("access-1", expired))
        val api = client(sessions)

        List(6) { async { api.get("/users/me", signedIn = true) } }.awaitAll()

        assertEquals(1, refreshes)
        assertEquals(listOf("refresh-2"), sessions.saved.map { it.tokens.refreshToken })
        assertEquals(user, sessions.current?.user)
        assertTrue(sessions.current!!.tokens.accessExpiresAt.isAfter(Instant.now().plusSeconds(800)))
    }

    @Test
    fun `a token the backend refuses is renewed and the request repeated with the new one`() = runBlocking {
        val sessions = Sessions(session("access-1", live))

        client(sessions).get("/users/me", signedIn = true)

        assertEquals(listOf("GET /users/me", "POST /public/auth/refresh", "GET /users/me"), requests.toList())
        assertEquals("access-2", sessions.current?.tokens?.accessToken)
    }

    @Test
    fun `a wrong password is the answer, not a reason to renew the token`() = runBlocking {
        val sessions = Sessions(session("access-2", live))

        val error = failure { client(sessions).delete("/users/me", JSONObject().put("password", "wrong")) }

        assertEquals("INVALID_CREDENTIALS", (error as ApiException).code)
        assertEquals(listOf("DELETE /users/me"), requests.toList())
        assertEquals("refresh-1", sessions.current?.tokens?.refreshToken)
    }

    @Test
    fun `a session the backend no longer honours is ended`() = runBlocking {
        onRefresh = { refused(401, "INVALID_REFRESH_TOKEN") }
        val sessions = Sessions(session("access-1", expired))

        val error = failure { client(sessions).get("/users/me", signedIn = true) }

        assertTrue((error as ApiException).isUnauthorized)
        assertNull(sessions.current)
    }

    @Test
    fun `a backend that cannot be reached, or answers nonsense, leaves the session alone`() = runBlocking {
        onRefresh = { MockResponse().setBody("<html>bad gateway</html>") }
        val garbled = Sessions(session("access-1", expired))
        assertEquals("BAD_RESPONSE", (failure { client(garbled).get("/users/me", signedIn = true) } as ApiException).code)
        assertEquals("refresh-1", garbled.current?.tokens?.refreshToken)

        val unreachable = Sessions(session("access-1", expired))
        val api = client(unreachable)
        server.shutdown()
        assertTrue(failure { api.get("/users/me", signedIn = true) } is OfflineException)
        assertEquals("refresh-1", unreachable.current?.tokens?.refreshToken)
    }

    @Test
    fun `nobody signed in means no request at all`() = runBlocking {
        val error = failure { client(Sessions(null)).get("/users/me", signedIn = true) }

        assertTrue((error as ApiException).isUnauthorized)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a renewal already sent is finished and stored although its caller was cancelled`() = runBlocking {
        onRefresh = { ok("""{"accessToken":"access-2","refreshToken":"refresh-2","expiresIn":900}""").setBodyDelay(400, TimeUnit.MILLISECONDS) }
        val sessions = Sessions(session("access-1", expired))
        val api = client(sessions)

        val caller = launch { api.get("/users/me", signedIn = true) }
        while (refreshes == 0) delay(10)
        caller.cancelAndJoin()

        // The backend has swapped the refresh token by now: losing its reply would end the session at the next renewal.
        assertEquals("refresh-2", sessions.current?.tokens?.refreshToken)
        assertEquals(listOf("POST /public/auth/refresh"), requests.toList())
    }

    @Test
    fun `a failed reply becomes the backend's error and an oversized one is refused`() = runBlocking {
        val api = client(Sessions(session("access-2", live)))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.path == "/big") MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)) else refused(409, "SLOT_UNAVAILABLE")
        }

        val conflict = failure { api.post("/booking-holds", JSONObject(), signedIn = true) } as ApiException
        assertEquals(409 to "SLOT_UNAVAILABLE", conflict.status to conflict.code)
        assertTrue(failure { api.get("/big") } is OfflineException)
    }

    private suspend fun failure(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (error: BackendException) {
            return error
        }
        fail("Expected the request to fail")
        throw IllegalStateException()
    }
}
