package vn.anta.booking.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import vn.anta.booking.R

/** The logo's colours: a teal-to-blue field, a green tick and an orange ball. */
object Brand {
    val Teal = Color(0xFF12B5A6)
    val Blue = Color(0xFF1B7BB8)
    val Green = Color(0xFF3FAE3A)
    val Orange = Color(0xFFF58A1F)
    val Ink = Color(0xFF0F2533)
    val Star = Color(0xFFFFB300)
    val Heart = Color(0xFFE5484D)

    /** The header and primary buttons. White text reads on every part of it. */
    val Gradient = Brush.linearGradient(listOf(Teal, Blue))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A93A3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F4F6),
    onPrimaryContainer = Color(0xFF00363D),
    secondary = Brand.Green,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F6DC),
    onSecondaryContainer = Color(0xFF103D0C),
    tertiary = Color(0xFFE07A10),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9D1),
    onTertiaryContainer = Color(0xFF4A2800),
    background = Color(0xFFF4FAFB),
    onBackground = Brand.Ink,
    surface = Color.White,
    onSurface = Brand.Ink,
    surfaceVariant = Color(0xFFE8F1F3),
    onSurfaceVariant = Color(0xFF566B76),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7FBFC),
    surfaceContainer = Color(0xFFF0F6F8),
    surfaceContainerHigh = Color(0xFFE8F1F3),
    surfaceContainerHighest = Color(0xFFDFEAED),
    outline = Color(0xFFA9BDC4),
    outlineVariant = Color(0xFFDCE7EA),
    error = Color(0xFFD23B3B),
    onError = Color.White,
    errorContainer = Color(0xFFFFE3E0),
    onErrorContainer = Color(0xFF5C0F0F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF55D4DE),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF0B4F58),
    onPrimaryContainer = Color(0xFFC5F3F7),
    secondary = Color(0xFF8ADB80),
    onSecondary = Color(0xFF0B3908),
    secondaryContainer = Color(0xFF1E4F1A),
    onSecondaryContainer = Color(0xFFD3F5CD),
    tertiary = Color(0xFFFFB868),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFE2C2),
    background = Color(0xFF0B161B),
    onBackground = Color(0xFFE3F0F3),
    surface = Color(0xFF122128),
    onSurface = Color(0xFFE3F0F3),
    surfaceVariant = Color(0xFF1C3039),
    onSurfaceVariant = Color(0xFF9EB5BE),
    surfaceContainerLowest = Color(0xFF08121A),
    surfaceContainerLow = Color(0xFF101D24),
    surfaceContainer = Color(0xFF15262E),
    surfaceContainerHigh = Color(0xFF1C3039),
    surfaceContainerHighest = Color(0xFF253C46),
    outline = Color(0xFF4A636E),
    outlineVariant = Color(0xFF263A43),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0F0F),
    errorContainer = Color(0xFF7A1E1E),
    onErrorContainer = Color(0xFFFFDAD6)
)

/** Be Vietnam Pro: drawn for Vietnamese, so stacked accents keep their shape at every weight. */
private val BeVietnam = FontFamily(
    Font(R.font.be_vietnam_pro_regular, FontWeight.Normal),
    Font(R.font.be_vietnam_pro_medium, FontWeight.Medium),
    Font(R.font.be_vietnam_pro_semibold, FontWeight.SemiBold),
    Font(R.font.be_vietnam_pro_bold, FontWeight.Bold)
)

private fun style(size: Int, line: Int, weight: FontWeight) =
    TextStyle(fontFamily = BeVietnam, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val AntaTypography = Typography(
    displaySmall = style(32, 40, FontWeight.Bold),
    headlineLarge = style(28, 36, FontWeight.Bold),
    headlineMedium = style(24, 32, FontWeight.Bold),
    headlineSmall = style(20, 28, FontWeight.Bold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 24, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 24, FontWeight.Normal),
    bodyMedium = style(14, 21, FontWeight.Normal),
    bodySmall = style(12, 17, FontWeight.Normal),
    labelLarge = style(14, 20, FontWeight.SemiBold),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 15, FontWeight.Medium)
)

private val AntaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun AntaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AntaTypography,
        shapes = AntaShapes,
        content = content
    )
}
