package vn.anta.booking.ui.venue

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import vn.anta.booking.AppContainer
import vn.anta.booking.data.BackendException
import vn.anta.booking.data.Formats
import vn.anta.booking.data.Messages
import vn.anta.booking.data.PriceList
import vn.anta.booking.data.Venue
import vn.anta.booking.data.VenueDetail
import vn.anta.booking.ui.components.FailedState
import vn.anta.booking.ui.components.Load
import vn.anta.booking.ui.components.LocalFavoriteIds
import vn.anta.booking.ui.components.Pill
import vn.anta.booking.ui.components.PrimaryButton
import vn.anta.booking.ui.components.RatingBadge
import vn.anta.booking.ui.components.RemoteImage
import vn.anta.booking.ui.components.SkeletonRows
import vn.anta.booking.ui.components.SportTag
import vn.anta.booking.ui.components.appViewModel
import vn.anta.booking.ui.theme.Brand

class VenueViewModel(private val app: AppContainer, private val id: String) : ViewModel() {
    var state by mutableStateOf<Load<VenueDetail>>(Load.Loading)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    val signedIn: Boolean get() = app.sessions.current != null

    init {
        load()
    }

    fun setFavorite(venue: Venue, favorite: Boolean) {
        viewModelScope.launch {
            try {
                app.favorites.set(venue, favorite)
            } catch (error: BackendException) {
                notice = Messages.of(error)
            }
        }
    }

    fun noticeShown() {
        notice = null
    }

    fun load() {
        state = Load.Loading
        viewModelScope.launch {
            state = try {
                Load.Ready(app.repository.venue(id, app.location.position.value))
            } catch (error: BackendException) {
                Load.Failed(Messages.of(error))
            }
        }
    }
}

private enum class VenueTab(val title: String) { PRICES("Bảng giá"), PHOTOS("Hình ảnh"), SERVICES("Dịch vụ"), RULES("Quy định") }

@Composable
fun VenueScreen(venueId: String, onBack: () -> Unit, onBook: () -> Unit, onLogin: () -> Unit) {
    val model = appViewModel { VenueViewModel(it, venueId) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(model.notice) { model.notice?.let { snackbar.showSnackbar(it); model.noticeShown() } }
    when (val state = model.state) {
        Load.Loading -> Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) { BackButton(onBack) }
            SkeletonRows(3, Modifier.padding(top = 20.dp))
        }
        is Load.Failed -> Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BackButton(onBack, onImage = false)
            FailedState(state.message, model::load)
        }
        is Load.Ready -> VenueContent(state.value, snackbar, onBack, onBook) { favorite ->
            // A favourite belongs to an account: without one, the heart leads to the sign-in screen.
            if (model.signedIn) model.setFavorite(state.value.venue, favorite) else onLogin()
        }
    }
}

@Composable
private fun VenueContent(detail: VenueDetail, snackbar: SnackbarHostState, onBack: () -> Unit, onBook: () -> Unit, onFavorite: (Boolean) -> Unit) {
    val venue = detail.venue
    val favorite = venue.id in LocalFavoriteIds.current
    var tab by rememberSaveable { mutableStateOf(VenueTab.PRICES) }
    var viewing by rememberSaveable { mutableStateOf<Int?>(null) }

    val listState = rememberLazyListState()
    // The photo counts as gone once the sheet below it has reached the place of the pinned bar.
    val barBottom = WindowInsets.statusBars.getTop(LocalDensity.current) + with(LocalDensity.current) { (PINNED_BAR + SHEET_OVERLAP).roundToPx() }
    val photoGone by remember(barBottom) {
        derivedStateOf {
            val photo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            // Before the first layout there are no visible items at all: the photo is not gone, it is not there yet.
            if (photo == null) listState.firstVisibleItemIndex > 0 else photo.offset + photo.size <= barBottom
        }
    }

    Scaffold(bottomBar = { BookingBar(detail, onBook) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp), modifier = Modifier.fillMaxSize()) {
            item(key = "photos") { Hero(venue, favorite, onBack, { onFavorite(!favorite) }) { viewing = it } }
            item(key = "summary") { Summary(venue) }
            item(key = "tabs") {
                TabRow(selectedTabIndex = tab.ordinal, containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.padding(top = 8.dp)) {
                    VenueTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1) })
                    }
                }
            }
            when (tab) {
                VenueTab.PRICES -> prices(detail)
                VenueTab.PHOTOS -> photos(venue.images) { viewing = it }
                VenueTab.SERVICES -> if (detail.services.isEmpty()) note("Sân chưa đăng dịch vụ đi kèm.") else {
                    items(detail.services.size, key = { "service-$it" }) { index -> PriceRow(detail.services[index].name, Formats.price(detail.services[index].price)) }
                }
                VenueTab.RULES -> if (detail.rules.isEmpty()) note("Sân chưa đăng quy định riêng.") else {
                    items(detail.rules.size, key = { "rule-$it" }) { index ->
                        Row(Modifier.padding(horizontal = 20.dp, vertical = 7.dp)) {
                            Text("${index + 1}.", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(28.dp))
                            Text(detail.rules[index], style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(photoGone, enter = fadeIn(), exit = fadeOut()) { PinnedBar(venue.name, favorite, onBack) { onFavorite(!favorite) } }
    }
    viewing?.let { start -> PhotoViewer(venue.images, start) { viewing = null } }
}

@Composable
private fun Hero(venue: Venue, favorite: Boolean, onBack: () -> Unit, onFavorite: () -> Unit, onOpen: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
        if (venue.images.isEmpty()) RemoteImage(null, Modifier.fillMaxSize()) else {
            val pager = rememberPagerState { venue.images.size }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page -> RemoteImage(venue.images[page], Modifier.fillMaxSize().clickable { onOpen(page) }) }
            Pill("${pager.currentPage + 1}/${venue.images.size}", Color.Black.copy(alpha = 0.55f), Color.White, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 36.dp))
        }
        BackButton(onBack)
        if (favorite) RoundButton(Icons.Rounded.Favorite, REMOVE_FAVORITE, onFavorite, Modifier.align(Alignment.TopEnd).statusBarsPadding(), background = Color.White, tint = Brand.Heart)
        else RoundButton(Icons.Rounded.FavoriteBorder, ADD_FAVORITE, onFavorite, Modifier.align(Alignment.TopEnd).statusBarsPadding())
    }
}

/** Takes over from the buttons on the photo once the photo has scrolled away: the way back, the name, the heart. */
@Composable
private fun PinnedBar(title: String, favorite: Boolean, onBack: () -> Unit, onFavorite: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(PINNED_BAR).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Quay lại") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
            IconButton(onClick = onFavorite) {
                if (favorite) Icon(Icons.Rounded.Favorite, contentDescription = REMOVE_FAVORITE, tint = Brand.Heart)
                else Icon(Icons.Rounded.FavoriteBorder, contentDescription = ADD_FAVORITE)
            }
        }
    }
}

/** A round button that floats over a photo. */
@Composable
private fun RoundButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, background: Color = Color.Black.copy(alpha = 0.45f), tint: Color = Color.White) {
    Box(
        modifier.padding(12.dp).size(42.dp).clip(CircleShape).background(background).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = tint) }
}

@Composable
private fun BackButton(onBack: () -> Unit, onImage: Boolean = true) {
    if (onImage) RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Quay lại", onBack, Modifier.statusBarsPadding())
    else RoundButton(Icons.AutoMirrored.Rounded.ArrowBack, "Quay lại", onBack, background = MaterialTheme.colorScheme.surfaceContainerHigh, tint = MaterialTheme.colorScheme.onSurface)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Summary(venue: Venue) {
    val context = LocalContext.current
    // The sheet rides up over the photo, so the page reads as one piece rather than a picture above a form.
    Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth().offset(y = -SHEET_OVERLAP)) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) {
            Text(venue.name, style = MaterialTheme.typography.headlineSmall)
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                venue.rating?.let { RatingBadge(it, venue.reviewCount) }
                venue.sports.forEach { SportTag(it.slug, it.name) }
            }
            Surface(shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(venue.address.ifBlank { "Chưa có địa chỉ" }, style = MaterialTheme.typography.bodyMedium)
                            Formats.distance(venue.distanceMeters)?.let { Text("Cách bạn $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { openDirections(context, venue) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Icon(Icons.Rounded.Directions, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Chỉ đường")
                        }
                    }
                    Formats.hours(venue.openingHour, venue.closingHour)?.let { hours ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("Mở cửa $hours", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.prices(detail: VenueDetail) {
    if (detail.yards.isEmpty() && detail.priceLists.isEmpty()) return note("Sân chưa công bố bảng giá.")
    if (detail.yards.isNotEmpty()) {
        item(key = "yards-title") { Subtitle("Đặt trực tuyến") }
        items(detail.yards, key = { "yard-${it.id}" }) { yard -> PriceRow(yard.name, "${Formats.price(yard.hourlyPrice)}/giờ") }
    }
    if (detail.priceLists.isNotEmpty()) item(key = "lists-title") { Subtitle("Bảng giá của sân") }
    items(detail.priceLists.size, key = { "list-$it" }) { index -> PriceListCard(detail.priceLists[index]) }
}

@Composable
private fun PriceListCard(list: PriceList) {
    Surface(shape = MaterialTheme.shapes.large, shadowElevation = 1.dp, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(list.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            list.slots.forEach { slot ->
                // "T2 - CN 6h - 17h: 1.000.000 ₫" reads better as a time band on the left and its price on the right.
                val split = slot.lastIndexOf(": ")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (split > 0) slot.substring(0, split) else slot, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (split > 0) Text(slot.substring(split + 2), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun LazyListScope.photos(images: List<String>, onOpen: (Int) -> Unit) {
    if (images.isEmpty()) return note("Sân chưa đăng hình ảnh.")
    items(images.indices.chunked(PHOTO_COLUMNS), key = { "photos-${it.first()}" }) { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { index -> RemoteImage(images[index], Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium).clickable { onOpen(index) }) }
            repeat(PHOTO_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

private fun LazyListScope.note(text: String) {
    item(key = "note") {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun PriceRow(name: String, price: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(price, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun BookingBar(detail: VenueDetail, onBook: () -> Unit) {
    Surface(shadowElevation = 12.dp, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val price = detail.venue.minPrice
            if (detail.yards.isEmpty() || price == null) {
                Text("Sân này chưa mở đặt lịch trực tuyến.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(vertical = 12.dp))
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Giá từ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Formats.price(price)}/giờ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.tertiary)
                }
                PrimaryButton("Đặt lịch", onBook, Modifier.width(150.dp))
            }
        }
    }
}

@Composable
private fun PhotoViewer(images: List<String>, start: Int, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pager = rememberPagerState(initialPage = start.coerceIn(0, images.lastIndex)) { images.size }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                AsyncImage(model = images[page], contentDescription = "Ảnh ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Pill("${pager.currentPage + 1}/${images.size}", Color.White.copy(alpha = 0.18f), Color.White, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
            Box(
                Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
                    .clickable(role = Role.Button, onClickLabel = "Đóng", onClick = onClose),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Close, contentDescription = "Đóng", tint = Color.White) }
        }
    }
}

/** Opens the phone's map app at the venue; without one, the same place on the web. */
private fun openDirections(context: Context, venue: Venue) {
    val label = Uri.encode(venue.name)
    val targets = listOf("geo:0,0?q=${venue.lat},${venue.lng}($label)", "https://www.google.com/maps/search/?api=1&query=${venue.lat},${venue.lng}")
    for (target in targets) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next way of showing the place.
        }
    }
}

private const val PHOTO_COLUMNS = 3
private const val ADD_FAVORITE = "Thêm vào sân yêu thích"
private const val REMOVE_FAVORITE = "Bỏ khỏi sân yêu thích"
private val PINNED_BAR = 56.dp

/** How far the sheet with the venue's name rides up over the photo. */
private val SHEET_OVERLAP = 24.dp
