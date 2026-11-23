package app.scrcpy.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Scrcpy's own palette: deep lagoon teal with the Android-head green. */
object ScrcpyPalette {
    val Background = Color(0xFF05211C)
    val Surface = Color(0xFF0A332B)
    val SurfaceHigh = Color(0xFF0F453A)
    val Accent = Color(0xFF30DD81)
    val AccentDeep = Color(0xFF077062)
    val OnAccent = Color(0xFF032117)
    val TextPrimary = Color(0xFFEAFBF2)
    val TextSecondary = Color(0xFF9ED3BC)
    val Danger = Color(0xFFFF6B5E)
    val Outline = Color(0xFF1D5C4E)
}

private val ScrcpyColorScheme = darkColorScheme(
    primary = ScrcpyPalette.Accent,
    onPrimary = ScrcpyPalette.OnAccent,
    primaryContainer = ScrcpyPalette.AccentDeep,
    onPrimaryContainer = ScrcpyPalette.TextPrimary,
    secondary = ScrcpyPalette.AccentDeep,
    onSecondary = ScrcpyPalette.TextPrimary,
    background = ScrcpyPalette.Background,
    onBackground = ScrcpyPalette.TextPrimary,
    surface = ScrcpyPalette.Surface,
    onSurface = ScrcpyPalette.TextPrimary,
    surfaceVariant = ScrcpyPalette.SurfaceHigh,
    onSurfaceVariant = ScrcpyPalette.TextSecondary,
    outline = ScrcpyPalette.Outline,
    error = ScrcpyPalette.Danger,
    onError = Color.White
)

@Composable
fun ScrcpyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ScrcpyColorScheme, content = content)
}
