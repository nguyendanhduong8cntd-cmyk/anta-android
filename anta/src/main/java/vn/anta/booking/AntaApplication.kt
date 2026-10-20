package vn.anta.booking

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import vn.anta.booking.data.ApiClient
import vn.anta.booking.data.BackendConfig
import vn.anta.booking.data.Catalog
import vn.anta.booking.data.DeviceLocation
import vn.anta.booking.data.Favorites
import vn.anta.booking.data.RecentSearches
import vn.anta.booking.data.Repository
import vn.anta.booking.data.SessionStore

/** The app's long-lived objects, built once and handed to the screens' view models. */
class AppContainer(context: Context) {
    // The demo backend sleeps when idle and takes about a minute to answer its first request after that.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(80, TimeUnit.SECONDS)
        .build()

    /** For work that belongs to the app rather than to a screen, such as following who is signed in. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val sessions = SessionStore(context)
    private val api = ApiClient(BuildConfig.API_BASE_URL, sessions, http)
    val repository = Repository(api, sessions, developmentBackend(api))
    val config = BackendConfig(repository)
    val catalog = Catalog(repository)
    val location = DeviceLocation(context)
    val favorites = Favorites(repository, sessions.session, { location.position.value }, scope)
    val recentSearches = RecentSearches(context)

    /** Photos come from other hosts than the backend, so they get a client without the API's settings or headers. */
    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build() })) }
        .crossfade(true)
        .build()
}

class AntaApplication : Application(), SingletonImageLoader.Factory {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        installDevelopmentTools(this)
        // Reading the stored session asks the Keystore, which can be slow. Started here on another thread, it is
        // normally done by the time the first screen asks for the container; if not, that screen waits for the rest.
        Thread({ container }, "anta-start").start()
    }

    override fun newImageLoader(context: Context): ImageLoader = container.imageLoader
}
