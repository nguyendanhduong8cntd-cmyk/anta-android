package vn.anta.booking.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Google sign-in, account deletion and favourites, against a local HTTP server standing in for the backend. */
class AccountAndFavoritesTest {
    private class Sessions(start: Session?) : SessionHolder {
        val state = MutableStateFlow(start)
        override val current: Session? get() = state.value
        override val deviceId = "device-1"
        override suspend fun save(session: Session) {
            state.value = session
        }

        override fun clear() {
            state.value = null
        }
    }

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val an = Session(User("u1", "an@example.com", "An"), Tokens("access-1", "refresh-1", Instant.now().plusSeconds(600)))
    private val courtA = venue("a1", "Sân A")
    private val courtB = venue("b2", "Sân B")

    /** What the backend currently holds as the user's favourites. */
    private val stored = CopyOnWriteArrayList(listOf("a1"))

    /** Set by a test to make the backend refuse or dawdle over a change to the favourites. */
    private var onChange: (() -> MockResponse)? = null

    /** How long the backend takes to send the list of favourites it has already put together. */
    private var listDelayMs = 0L

    private fun venue(id: String, name: String) = Venue(id, name, "1 Lê Lợi", 10.77, 106.69, null, 5, 23, emptyList(), 4.5, 3, 80000, emptyList())
    private fun ok(data: String) = MockResponse().setBody("""{"data":$data,"meta":{}}""")
    private fun refused(status: Int, code: String) = MockResponse().setResponseCode(status).setBody("""{"data":null,"error":{"code":"$code","message":"x"}}""")
    private fun listed(id: String) = """{"id":"$id","name":"Sân ${id.first().uppercase()}","address":"1 Lê Lợi","images":[],"rating":4.5,"review_count":3,"min_price":80000,"sports":[],"lat":10.77,"lng":106.69,"opening_hour":5,"closing_hour":23}"""

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requests += "${request.method} $path" to request.body.readUtf8()
                return when {
                    path == "/public/auth/social" -> ok("""{"accessToken":"access-g","refreshToken":"refresh-g","expiresIn":900,"user":{"id":"u9"}}""")
                    path == "/users/me" && request.method == "GET" -> ok("""{"id":"u9","identifier":"binh@gmail.com","displayName":"binh","passwordLogin":false}""")
                    path == "/users/me" && request.method == "DELETE" -> ok("""{"status":"DELETION_REQUESTED"}""")
                    path == "/users/me/favorites" -> ok(stored.joinToString(",", "[", "]") { listed(it) }).setBodyDelay(listDelayMs, TimeUnit.MILLISECONDS)
                    path.startsWith("/users/me/favorites/") -> onChange?.invoke() ?: ok("{}").also {
                        val id = path.substringAfterLast('/')
                        stored.remove(id)
                        if (request.method == "POST") stored.add(0, id)
                    }
                    else -> refused(404, "NOT_FOUND")
                }
            }
        }
        server.start()
    }

    @After
    fun stop() {
        scope.cancel()
        server.shutdown()
    }

    private fun repository(sessions: Sessions) =
        Repository(ApiClient(server.url("/").toString(), sessions, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()), sessions, development = null)

    private fun favorites(sessions: Sessions, near: GeoPoint? = null) = Favorites(repository(sessions), sessions.state, { near }, scope)

    private suspend fun Favorites.awaitIds(vararg ids: String) = withTimeout(3_000) {
        while (venues.value.map { it.id } != ids.toList()) delay(10)
    }

    // ---- Google sign-in and account deletion ----

    @Test
    fun `Google sign-in sends the proof with this device's name and opens a session for the account behind it`() = runBlocking {
        val sessions = Sessions(null)

        val session = repository(sessions).googleLogin("google-id-token")

        val sent = JSONObject(requests.first { it.first == "POST /public/auth/social" }.second)
        assertEquals("google-id-token" to "device-1", sent.getString("idToken") to sent.getString("deviceId"))
        assertEquals(User("u9", "binh@gmail.com", "binh", passwordLogin = false), session.user)
        assertEquals("refresh-g", sessions.current?.tokens?.refreshToken)
    }

    @Test
    fun `an account is deleted on a password or on a new Google proof, never both, and the session ends`() = runBlocking {
        val byPassword = Sessions(an)
        repository(byPassword).deleteAccount(AccountProof.Password("mat-khau-cua-an"))
        val byGoogle = Sessions(an)
        repository(byGoogle).deleteAccount(AccountProof.Google("google-id-token"))

        val bodies = requests.filter { it.first == "DELETE /users/me" }.map { JSONObject(it.second) }
        assertEquals(listOf("password"), bodies[0].keys().asSequence().toList())
        assertEquals(listOf("idToken"), bodies[1].keys().asSequence().toList())
        assertNull(byPassword.current)
        assertNull(byGoogle.current)
    }

    // ---- Favourites ----

    @Test
    fun `favourites load for whoever is signed in, with distances, and empty the moment they sign out`() = runBlocking {
        val sessions = Sessions(an)
        val favorites = favorites(sessions, near = GeoPoint(10.78, 106.69))

        favorites.awaitIds("a1")
        val distance = favorites.venues.value.single().distanceMeters!!
        assertTrue("about 1.1 km, was $distance", distance in 1_000.0..1_300.0)

        sessions.clear()
        assertTrue(favorites.venues.value.isEmpty())
    }

    @Test
    fun `nobody signed in means no favourites and no request for them`() = runBlocking {
        val favorites = favorites(Sessions(null))

        favorites.refresh()
        favorites.set(courtB, true)

        assertTrue(favorites.venues.value.isEmpty())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a new favourite goes to the top and the backend is told, and removing it tells the backend too`() = runBlocking {
        val favorites = favorites(Sessions(an))
        favorites.awaitIds("a1")

        favorites.set(courtB, true)
        assertEquals(listOf("b2", "a1"), favorites.venues.value.map { it.id })
        favorites.set(courtA, false)

        assertEquals(listOf("b2"), favorites.venues.value.map { it.id })
        assertEquals(listOf("POST /users/me/favorites/b2", "DELETE /users/me/favorites/a1"), requests.map { it.first }.filter { "/favorites/" in it })
        favorites.refresh()
        assertEquals(listOf("b2"), favorites.venues.value.map { it.id })
    }

    @Test
    fun `a change the backend refuses is taken back and reported`() = runBlocking {
        val favorites = favorites(Sessions(an))
        favorites.awaitIds("a1")
        onChange = { refused(503, "DEPENDENCY_UNAVAILABLE") }

        val added = runCatching { favorites.set(courtB, true) }.exceptionOrNull()
        val removed = runCatching { favorites.set(courtA, false) }.exceptionOrNull()
        // Taking back is not "do the opposite": a refused removal of something that was never there adds nothing.
        val removedAbsent = runCatching { favorites.set(courtB, false) }.exceptionOrNull()

        assertEquals("DEPENDENCY_UNAVAILABLE", (added as ApiException).code)
        assertTrue(removed is ApiException && removedAbsent is ApiException)
        assertEquals(listOf("a1"), favorites.venues.value.map { it.id })
    }

    @Test
    fun `a change made while the list is being read is still there when the older list arrives`() = runBlocking {
        val favorites = favorites(Sessions(an))
        favorites.awaitIds("a1")
        listDelayMs = 300
        val reads = requests.count { it.first == "GET /users/me/favorites" }

        val reading = launch(Dispatchers.Default) { favorites.refresh() }
        while (requests.count { it.first == "GET /users/me/favorites" } == reads) delay(10)
        favorites.set(courtB, true)
        reading.join()

        assertEquals(listOf("b2", "a1"), favorites.venues.value.map { it.id })
        listDelayMs = 0
        favorites.refresh()
        assertEquals(listOf("b2", "a1"), favorites.venues.value.map { it.id })
    }

    @Test
    fun `a change is seen through although the screen that asked for it has closed`() = runBlocking {
        val favorites = favorites(Sessions(an))
        favorites.awaitIds("a1")
        // The backend takes its time and then refuses: only a request that outlives its caller can take the heart back.
        onChange = { refused(503, "DEPENDENCY_UNAVAILABLE").setBodyDelay(300, TimeUnit.MILLISECONDS) }

        val screen = launch(Dispatchers.Default) {
            try {
                favorites.set(courtB, true)
            } catch (_: BackendException) {
                // The screen is gone by then: nobody is left to tell.
            }
        }
        while (requests.none { it.first == "POST /users/me/favorites/b2" }) delay(10)
        assertEquals(listOf("b2", "a1"), favorites.venues.value.map { it.id })
        screen.cancelAndJoin()

        assertEquals(listOf("a1"), favorites.venues.value.map { it.id })
        assertFalse(requests.any { it.first.startsWith("DELETE") })

        // And a change the backend grants after the screen has gone stays: giving up on the reply would have left
        // the backend with a favourite the app no longer shows.
        onChange = { stored.add(0, "b2"); ok("{}").setBodyDelay(300, TimeUnit.MILLISECONDS) }
        val again = launch(Dispatchers.Default) { favorites.set(courtB, true) }
        while (requests.count { it.first == "POST /users/me/favorites/b2" } < 2) delay(10)
        again.cancelAndJoin()

        assertEquals(listOf("b2", "a1"), favorites.venues.value.map { it.id })
    }
}
