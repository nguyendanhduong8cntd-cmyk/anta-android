package vn.anta.booking.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import coil3.compose.AsyncImage
import vn.anta.booking.AntaApplication
import vn.anta.booking.AppContainer
import vn.anta.booking.data.Formats
import vn.anta.booking.data.Venue
import vn.anta.booking.ui.theme.Brand

/** What a screen is showing while it waits for, fails to get, or has its content. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as AntaApplication).container

/** A view model for the current screen, built from the app's shared objects. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** The ids of the signed-in user's favourite venues, so any venue card can mark itself without being told. */
val LocalFavoriteIds = compositionLocalOf<Set<String>> { emptySet() }

// ---- Page furniture ----

/** The coloured band at the top of a tab. Its content is white. */
@Composable
fun HeaderBand(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 30.dp, bottomEnd = 30.dp)).background(Brand.Gradient)
            .statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 22.dp)
    ) { content() }
}

/**
 * Covers the status bar once a page's header has scrolled away. Pages draw up to the top edge of the screen, and
 * without this the clock and the battery would sit on top of whatever scrolls past.
 */
@Composable
fun StatusBarScrim(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(MaterialTheme.colorScheme.background))
    }
}

/** A tab's title in the header band, with room for one round button beside it. */
@Composable
fun TabHeader(title: String, subtitle: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    HeaderBand(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.92f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            action()
        }
    }
}

/** A round button for the header band. */
@Composable
fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, contentDescription = label, tint = Color.White) }
}

/** Looks like a search field and opens the search screen, where the typing happens. */
@Composable
fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Tìm sân, khu vực, môn thể thao…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---- Sports ----

/** A sport's emoji: recognisable at a glance and in colour without shipping a picture for each. */
fun sportEmoji(slug: String): String = when (slug) {
    "pickleball" -> "🏓"
    "badminton" -> "🏸"
    "football" -> "⚽"
    "basketball" -> "🏀"
    "tennis", "padel" -> "🎾"
    "volleyball" -> "🏐"
    "golf" -> "⛳"
    "wakeboard" -> "🏄"
    "multi-sport" -> "🏟️"
    else -> "🏅"
}

/** A sport's own colour, used as a soft tint behind its emoji. */
fun sportColor(slug: String): Color = when (slug) {
    "pickleball" -> Color(0xFF12B5A6)
    "badminton" -> Color(0xFF5B8DEF)
    "football" -> Color(0xFF45B649)
    "basketball" -> Color(0xFFF7931E)
    "tennis" -> Color(0xFF9CCB3B)
    "padel" -> Color(0xFF26C6DA)
    "volleyball" -> Color(0xFFFFB74D)
    "golf" -> Color(0xFF66BB6A)
    "wakeboard" -> Color(0xFF4FC3F7)
    else -> Color(0xFF7E8CE0)
}

// ---- Pieces ----

/** A photo from the network over a tinted placeholder, which stays visible while loading and when there is none. */
@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, emoji: String = "🏟️") {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = 28.sp, modifier = Modifier.alpha(0.55f))
        if (url != null) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** The main action of a screen. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val active = enabled && !loading
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (enabled) Brand.Gradient else Brush.linearGradient(List(2) { MaterialTheme.colorScheme.surfaceContainerHighest }))
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.5.dp)
        else Text(text, style = MaterialTheme.typography.titleMedium, color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) } else Spacer(Modifier.height(48.dp))
    }
}

@Composable
fun RatingBadge(rating: Double, reviewCount: Int, modifier: Modifier = Modifier, onImage: Boolean = false) {
    val background = if (onImage) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (onImage) Color.White else MaterialTheme.colorScheme.onTertiaryContainer
    Row(modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Star, contentDescription = null, tint = Brand.Star, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(Formats.rating(rating) + if (reviewCount > 0) " ($reviewCount)" else "", style = MaterialTheme.typography.labelMedium, color = content)
    }
}

@Composable
fun SportTag(slug: String, name: String, modifier: Modifier = Modifier) {
    Text(
        "${sportEmoji(slug)} $name",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
fun IconLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "từ 80K/giờ" */
@Composable
fun PriceFrom(amount: Long, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text("từ ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Formats.shortPrice(amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
        Text("/giờ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The heart a venue's photo wears once the user has made it a favourite. */
@Composable
fun FavoriteMark(venueId: String, modifier: Modifier = Modifier) {
    if (venueId !in LocalFavoriteIds.current) return
    Box(modifier.padding(6.dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Favorite, contentDescription = "Sân yêu thích", tint = Brand.Heart, modifier = Modifier.size(16.dp))
    }
}

/** What stands in for a venue's photo: the emoji of its first sport. */
fun Venue.emoji(): String = sports.firstOrNull()?.let { sportEmoji(it.slug) } ?: "🏟️"

// ---- Venue cards ----

/** A venue in a list: photo on the left, the facts a user chooses by on the right. */
@Composable
fun VenueRowCard(venue: Venue, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp)) {
            Box {
                RemoteImage(venue.cover, Modifier.size(104.dp).clip(MaterialTheme.shapes.medium), emoji = venue.emoji())
                // On the photo, as in the carousel cards: the row below is too narrow for a rating, a sport and a price.
                venue.rating?.let { RatingBadge(it, venue.reviewCount, Modifier.align(Alignment.TopStart).padding(6.dp), onImage = true) }
                FavoriteMark(venue.id, Modifier.align(Alignment.BottomEnd))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).heightIn(min = 104.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(venue.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    IconLine(Icons.Rounded.LocationOn, listOfNotNull(Formats.distance(venue.distanceMeters), venue.address.ifBlank { null }).joinToString(" · "))
                    Formats.hours(venue.openingHour, venue.closingHour)?.let { IconLine(Icons.Rounded.Schedule, it, Modifier.padding(top = 3.dp)) }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        venue.sports.firstOrNull()?.let { SportTag(it.slug, it.name, Modifier.weight(1f, fill = false)) }
                        if (venue.sports.size > 1) Text("+${venue.sports.size - 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    venue.minPrice?.let { PriceFrom(it, Modifier.padding(start = 6.dp)) }
                }
            }
        }
    }
}

/** A venue in a carousel: the photo leads. */
@Composable
fun VenueTallCard(venue: Venue, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, shadowElevation = 3.dp, modifier = modifier.width(264.dp)) {
        Column {
            Box {
                RemoteImage(venue.cover, Modifier.fillMaxWidth().aspectRatio(16f / 10f), emoji = venue.emoji())
                venue.rating?.let { RatingBadge(it, venue.reviewCount, Modifier.align(Alignment.TopStart).padding(10.dp), onImage = true) }
                FavoriteMark(venue.id, Modifier.align(Alignment.BottomEnd).padding(4.dp))
                Formats.distance(venue.distanceMeters)?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(venue.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                IconLine(Icons.Rounded.LocationOn, venue.address.ifBlank { "Chưa có địa chỉ" })
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        venue.sports.take(2).forEach { SportTag(it.slug, it.name, Modifier.weight(1f, fill = false)) }
                    }
                    venue.minPrice?.let { PriceFrom(it, Modifier.padding(start = 6.dp)) }
                }
            }
        }
    }
}

/** A venue in a narrow carousel: photo, name and the one fact the carousel is about. */
@Composable
fun VenueSmallCard(venue: Venue, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large, shadowElevation = 2.dp, modifier = modifier.width(172.dp)) {
        Column {
            Box {
                RemoteImage(venue.cover, Modifier.fillMaxWidth().aspectRatio(4f / 3f), emoji = venue.emoji())
                venue.rating?.let { RatingBadge(it, 0, Modifier.align(Alignment.TopStart).padding(8.dp), onImage = true) }
                FavoriteMark(venue.id, Modifier.align(Alignment.BottomEnd).padding(2.dp))
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(venue.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                // One line whatever the venue has: a price when it can be booked, otherwise what is played there.
                val price = venue.minPrice
                if (price != null) PriceFrom(price) else Text(
                    venue.sports.firstOrNull()?.name ?: "Sân thể thao", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.heightIn(min = 24.dp)
                )
            }
        }
    }
}

// ---- States ----

@Composable
fun StateMessage(emoji: String, title: String, message: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 38.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(action, onAction)
        }
    }
}

@Composable
fun FailedState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    StateMessage("📡", "Chưa tải được", message, modifier, action = "Thử lại", onAction = onRetry)

/** Grey blocks in the shape of list rows, pulsing while the real ones load. */
@Composable
fun SkeletonRows(count: Int, modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(horizontal = 20.dp)) {
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(0.45f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha")
    Column(modifier.padding(padding).alpha(pulse), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).padding(10.dp)) {
                Box(Modifier.size(104.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.fillMaxWidth(0.85f).height(16.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    Box(Modifier.fillMaxWidth(0.6f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest))
                }
            }
        }
    }
}

@Composable
fun Pill(text: String, background: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = content, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 10.dp, vertical = 5.dp)
    )
}
