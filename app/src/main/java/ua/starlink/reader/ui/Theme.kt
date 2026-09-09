package ua.starlink.reader.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палітра «Є Starlink»: майже чорне тло, холодне світло, один синій акцент.
 * Тема навмисно лише темна — так само виглядає офіційний застосунок Starlink,
 * і на вулиці вдень темний екран з високим контрастом читається краще.
 */
object Brand {
    val Ink = Color(0xFF06080D)
    val Surface = Color(0xFF0E1119)
    val SurfaceRaised = Color(0xFF151A24)
    val Hairline = Color(0xFF222937)

    val TextPrimary = Color(0xFFF3F6FB)
    val TextMuted = Color(0xFF8A93A6)

    val Accent = Color(0xFF4C8DFF)
    val AccentSoft = Color(0xFF13233F)
    val Online = Color(0xFF3DDC97)
    val Waiting = Color(0xFFFFB443)
    val Alert = Color(0xFFFF6B6B)
}

private val StarlinkColors = darkColorScheme(
    primary = Brand.Accent,
    onPrimary = Color(0xFF04070E),
    primaryContainer = Brand.AccentSoft,
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFF93A5C2),
    onSecondary = Color(0xFF08111F),
    background = Brand.Ink,
    onBackground = Brand.TextPrimary,
    surface = Brand.Surface,
    onSurface = Brand.TextPrimary,
    surfaceVariant = Brand.SurfaceRaised,
    onSurfaceVariant = Brand.TextMuted,
    outline = Brand.Hairline,
    outlineVariant = Brand.Hairline,
    error = Brand.Alert,
    onError = Color(0xFF2A0407),
    errorContainer = Color(0xFF2B1015),
    onErrorContainer = Color(0xFFFFCBCB),
)

/** Технічна, щільна типографіка: вузькі трекінги в заголовках, широкі — у мітках. */
private val StarlinkTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.4).sp,
        ),
        titleLarge = titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.3).sp,
        ),
        titleMedium = titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        ),
        labelSmall = labelSmall.copy(
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
        ),
    )
}

private val StarlinkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Моноширинний стиль для ідентифікаторів — щоб символи не «пливли». */
val MonoValue = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 14.sp,
    letterSpacing = 0.2.sp,
)

@Composable
fun StarlinkReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = StarlinkColors,
        typography = StarlinkTypography,
        shapes = StarlinkShapes,
        content = content,
    )
}
