package vn.anta.booking.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as MapPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import vn.anta.booking.AppContainer
import vn.anta.booking.BuildConfig
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.DeviceLocation
import vn.anta.booking.data.GeoPoint
import vn.anta.booking.data.Messages
import vn.anta.booking.data.Repository
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenueSort
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.SearchBar
import vn.anta.booking.ui.components.VenueRowCard
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.home.SportFilter
import vn.anta.booking.ui.theme.Brand

class MapViewModel(private val app: AppContainer) : ViewModel() {
    val sports = app.catalog.sports
    val position = app.location.position

    var sportId by mutableStateOf<String?>(null)
        private set
    var venues by mutableStateOf<Load<List<Venue>>>(Load.Loading)
        private set
    var selected by mutableStateOf<Venue?>(null)
        private set

    /** Goes up each time the map should move to the phone's position. */
    var locateRequests by mutableIntStateOf(0)
        private set
    private var job: Job? = null

    init {
        viewModelScope.launch {
            app.location.refresh()
            app.catalog.ensure()
        }
        load()
    }

    fun load() {
        job?.cancel()
        selected = null
        venues = Load.Loading
        job = viewModelScope.launch {
            venues = try {
                Load.Ready(app.repository.venues(position.value, sportId, sort = VenueSort.RATING, pageSize = MAX_PINS).items)
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }

    fun selectSport(id: String?) {
        if (sportId == id) return
        sportId = id
        load()
    }

    fun select(venue: Venue?) {
        selected = venue
    }

    val locationAllowed: Boolean get() = app.location.isAllowed

    fun locate() {
        viewModelScope.launch {
            if (app.location.refresh() != null) locateRequests += 1
        }
    }

    private companion object {
        /** The backend's largest page: every venue fits on the map at once. */
        const val MAX_PINS = 100
    }
}

@Composable
fun MapScreen(contentPadding: PaddingValues, onSearch: () -> Unit, onVenue: (String) -> Unit) {
    val model = appViewModel { MapViewModel(it) }
    val sports by model.sports.collectAsStateWithLifecycle()
    val position by model.position.collectAsStateWithLifecycle()
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.locate() }
    val venues = (model.venues as? Load.Ready)?.value

    Box(Modifier.fillMaxSize().padding(bottom = contentPadding.calculateBottomPadding())) {
        VenueMap(venues.orEmpty(), model.selected, position, model.locateRequests, model::select)

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 10.dp)) {
            SearchBar(onSearch, Modifier.padding(horizontal = 20.dp))
            SportFilter(sports, model.sportId, model::selectSport, Modifier.padding(top = 10.dp))
            when (val state = model.venues) {
                Load.Loading -> Notice { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Đang tải sân…", style = MaterialTheme.typography.labelLarge) }
                // A pill over the map has room for one short line; the full wording is on the other tabs.
                is Load.Failed -> Notice(onClick = model::load) { Text("Chưa tải được sân. Chạm để thử lại.", style = MaterialTheme.typography.labelLarge) }
                is Load.Ready -> if (state.value.isEmpty()) Notice { Text("Chưa có sân cho môn này.", style = MaterialTheme.typography.labelLarge) }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    "© OpenStreetMap", style = MaterialTheme.typography.labelSmall, color = Brand.Ink,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.8f)).padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                SmallFloatingActionButton(
                    onClick = { if (model.locationAllowed) model.locate() else askLocation.launch(DeviceLocation.PERMISSIONS) },
                    containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.primary
                ) { Icon(Icons.Rounded.MyLocation, contentDescription = "Vị trí của tôi") }
            }
            model.selected?.let { venue -> VenueRowCard(venue, { onVenue(venue.id) }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
        }
    }
}

@Composable
private fun Notice(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
        Surface(onClick = onClick ?: {}, enabled = onClick != null, shape = RoundedCornerShape(50), shadowElevation = 4.dp) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

/** The map itself: an OpenStreetMap view with one pin per venue. */
@Composable
private fun VenueMap(venues: List<Venue>, selected: Venue?, position: GeoPoint?, locateRequests: Int, onSelect: (Venue?) -> Unit) {
    val context = LocalContext.current
    val pinColor = MaterialTheme.colorScheme.primary.toArgb()
    val pin = remember(pinColor) { pin(context, pinColor, PIN_DP) }
    val selectedPin = remember { pin(context, Brand.Orange.toArgb(), SELECTED_PIN_DP) }
    val here = remember { dot(context, Brand.Blue.toArgb()) }
    val map = remember {
        Configuration.getInstance().apply {
            // OpenStreetMap asks every app to identify itself; tiles are cached with the app's other disposable files.
            userAgentValue = BuildConfig.APPLICATION_ID
            osmdroidBasePath = File(context.cacheDir, "map")
            osmdroidTileCache = File(context.cacheDir, "map/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 4.0
            controller.setZoom(DEFAULT_ZOOM)
            controller.setCenter(MapPoint(DEFAULT_CENTER.lat, DEFAULT_CENTER.lng))
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, map) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) map.onResume() else if (event == Lifecycle.Event.ON_PAUSE) map.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            map.onDetach()
        }
    }

    // Pins are rebuilt when the venues, the selection or the phone's position change.
    LaunchedEffect(venues, selected, position) {
        map.overlays.clear()
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(point: MapPoint?): Boolean { onSelect(null); return true }
            override fun longPressHelper(point: MapPoint?): Boolean = false
        }))
        position?.let { map.overlays.add(marker(map, it.lat, it.lng, here, centered = true)) }
        // The selected pin is added last so it is drawn on top of its neighbours.
        for (venue in venues.sortedBy { it.id == selected?.id }) {
            val chosen = venue.id == selected?.id
            map.overlays.add(marker(map, venue.lat, venue.lng, if (chosen) selectedPin else pin, centered = false).apply {
                title = venue.name
                setOnMarkerClickListener { _, _ -> onSelect(venue); true }
            })
        }
        map.invalidate()
    }

    // A new set of venues moves the map to them, and so does the phone's position when it is first known. A venue
    // the user has picked stays where it is: after a rotation the map opens on it, not on the whole set.
    LaunchedEffect(venues, position != null) {
        if (venues.isNotEmpty() && selected == null) map.post { map.show(venues, position) }
    }
    LaunchedEffect(selected) { selected?.let { map.controller.animateTo(MapPoint(it.lat, it.lng)) } }
    LaunchedEffect(locateRequests) {
        if (locateRequests > 0) position?.let { map.controller.animateTo(MapPoint(it.lat, it.lng), SINGLE_ZOOM, MOVE_MS) }
    }

    AndroidView(factory = { map }, modifier = Modifier.fillMaxSize())
}

/**
 * Moves the map to a set of venues: to the user when some are near them, to all of the venues when they fit one
 * city, and otherwise to where most of them are, since a view of the whole country shows pins but no streets.
 */
private fun MapView.show(venues: List<Venue>, position: GeoPoint?) {
    val near = position?.takeIf { from -> venues.any { Repository.distanceMeters(from, GeoPoint(it.lat, it.lng)) < NEARBY_METERS } }
    val box = BoundingBox.fromGeoPointsSafe(venues.map { MapPoint(it.lat, it.lng) })
    when {
        near != null -> { controller.setZoom(DEFAULT_ZOOM); controller.setCenter(MapPoint(near.lat, near.lng)) }
        venues.size == 1 -> { controller.setZoom(SINGLE_ZOOM); controller.setCenter(MapPoint(venues[0].lat, venues[0].lng)) }
        box.diagonalLengthInMeters < ONE_CITY_METERS -> zoomToBoundingBox(box.increaseByScale(1.3f), false)
        else -> {
            // The middle venue by latitude and by longitude sits in the densest area, whatever the outliers.
            val middle = venues.size / 2
            controller.setZoom(CITY_ZOOM)
            controller.setCenter(MapPoint(venues.map(Venue::lat).sorted()[middle], venues.map(Venue::lng).sorted()[middle]))
        }
    }
}

private fun marker(map: MapView, lat: Double, lng: Double, icon: Drawable, centered: Boolean) = Marker(map).apply {
    position = MapPoint(lat, lng)
    this.icon = icon
    setAnchor(Marker.ANCHOR_CENTER, if (centered) Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
    // Tapping a pin shows the venue's card instead of osmdroid's own bubble.
    infoWindow = null
}

/** A teardrop pin with a white centre, drawn here so it can take any colour and size. */
private fun pin(context: Context, color: Int, heightDp: Int): Drawable {
    val density = context.resources.displayMetrics.density
    val height = heightDp * density
    val radius = height * 0.36f
    val bitmap = Bitmap.createBitmap((radius * 2 + 4 * density).toInt(), (height + 2 * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val centreX = bitmap.width / 2f
    val centreY = radius + 2 * density
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; setShadowLayer(2 * density, 0f, density, 0x55000000) }
    val tail = Path().apply {
        moveTo(centreX - radius * 0.72f, centreY + radius * 0.62f)
        lineTo(centreX, height)
        lineTo(centreX + radius * 0.72f, centreY + radius * 0.62f)
        close()
    }
    canvas.drawPath(tail, paint)
    canvas.drawCircle(centreX, centreY, radius, paint)
    canvas.drawCircle(centreX, centreY, radius * 0.42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
    return BitmapDrawable(context.resources, bitmap)
}

/** The phone's own position: a ringed dot, unlike any venue pin. */
private fun dot(context: Context, color: Int): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (22 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; setShadowLayer(2 * density, 0f, 0f, 0x55000000) })
    canvas.drawCircle(size / 2f, size / 2f, size / 2f - 4 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    return BitmapDrawable(context.resources, bitmap)
}

/** Ho Chi Minh City, where most venues are, until the first venues load. */
private val DEFAULT_CENTER = GeoPoint(10.7769, 106.7009)
private const val DEFAULT_ZOOM = 12.5
private const val SINGLE_ZOOM = 15.0
private const val CITY_ZOOM = 11.5
private const val ONE_CITY_METERS = 60_000.0
private const val NEARBY_METERS = 30_000.0
private const val MOVE_MS = 600L
private const val PIN_DP = 34
private const val SELECTED_PIN_DP = 46
