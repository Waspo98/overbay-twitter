package app.overbay.twitter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterThemeMode

val TwitterBlue = Color(0xFF1DA1F2)
val TwitterBlueDark = Color(0xFF1A8CD8)

/**
 * Mirrors X's three built-in visual surfaces: Default (Light), Dim (#15202B), and Lights Out (#000000).
 */
enum class TwitterSurfaceTheme(val backgroundColorInt: Int) {
    LIGHT(0xFFFFFFFF.toInt()),
    DIM(0xFF15202B.toInt()),
    LIGHTS_OUT(0xFF000000.toInt());

    val isLight: Boolean get() = this == LIGHT

    companion object {
        fun fromColorInt(colorInt: Int): TwitterSurfaceTheme {
            val r = (colorInt shr 16) and 0xFF
            val g = (colorInt shr 8) and 0xFF
            val b = colorInt and 0xFF
            val luma = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return when {
                luma > 128.0 -> LIGHT
                b - r >= 12 -> DIM
                else -> LIGHTS_OUT
            }
        }
    }
}

/**
 * Resolves the startup shell/WebView background color for [TwitterThemeMode], avoiding stale
 * light/dark mismatches when following the device theme across day/night transitions.
 */
fun TwitterThemeMode.resolveInitialColorInt(
    lastColorInt: Int?,
    isSystemDark: Boolean,
    autoDarkVariant: TwitterDarkVariant = TwitterDarkVariant.LIGHTS_OUT
): Int {
    return when (this) {
        TwitterThemeMode.LIGHT -> TwitterSurfaceTheme.LIGHT.backgroundColorInt
        TwitterThemeMode.DIM -> TwitterSurfaceTheme.DIM.backgroundColorInt
        TwitterThemeMode.LIGHTS_OUT -> TwitterSurfaceTheme.LIGHTS_OUT.backgroundColorInt
        TwitterThemeMode.AUTO -> {
            if (!isSystemDark) {
                TwitterSurfaceTheme.LIGHT.backgroundColorInt
            } else if (autoDarkVariant == TwitterDarkVariant.DIM) {
                TwitterSurfaceTheme.DIM.backgroundColorInt
            } else {
                TwitterSurfaceTheme.LIGHTS_OUT.backgroundColorInt
            }
        }
    }
}

private val LightsOutColors = darkColorScheme(
    primary = TwitterBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0E3A5D),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = Color(0xFF8ECDF8),
    onSecondary = Color(0xFF00344F),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE7E9EA),
    surface = Color(0xFF0F1419),
    onSurface = Color(0xFFE7E9EA),
    surfaceVariant = Color(0xFF16181C),
    onSurfaceVariant = Color(0xFF8B98A5),
    surfaceContainerLow = Color(0xFF16181C),
    surfaceContainer = Color(0xFF1D1F23),
    surfaceContainerHigh = Color(0xFF272A30),
    outline = Color(0xFF38444D),
    outlineVariant = Color(0xFF2F3336)
)

private val DimColors = darkColorScheme(
    primary = TwitterBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF124168),
    onPrimaryContainer = Color(0xFFD6EEFF),
    secondary = Color(0xFF8ECDF8),
    onSecondary = Color(0xFF00344F),
    background = Color(0xFF15202B),
    onBackground = Color(0xFFF7F9F9),
    surface = Color(0xFF15202B),
    onSurface = Color(0xFFF7F9F9),
    surfaceVariant = Color(0xFF1E2732),
    onSurfaceVariant = Color(0xFF8B98A5),
    surfaceContainerLow = Color(0xFF192734),
    surfaceContainer = Color(0xFF1E2D3D),
    surfaceContainerHigh = Color(0xFF253545),
    outline = Color(0xFF38444D),
    outlineVariant = Color(0xFF38444D)
)

private val LightColors = lightColorScheme(
    primary = TwitterBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9F0FF),
    onPrimaryContainer = Color(0xFF001E30),
    secondary = TwitterBlueDark,
    onSecondary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F1419),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F1419),
    surfaceVariant = Color(0xFFF7F9F9),
    onSurfaceVariant = Color(0xFF536471),
    surfaceContainerLow = Color(0xFFF7F9F9),
    surfaceContainer = Color(0xFFEFF3F4),
    surfaceContainerHigh = Color(0xFFE6ECF0),
    outline = Color(0xFFCFD9DE),
    outlineVariant = Color(0xFFEFF3F4)
)

@Composable
fun TwitterTheme(
    surfaceTheme: TwitterSurfaceTheme = if (isSystemInDarkTheme()) TwitterSurfaceTheme.LIGHTS_OUT else TwitterSurfaceTheme.LIGHT,
    content: @Composable () -> Unit
) {
    val scheme = when (surfaceTheme) {
        TwitterSurfaceTheme.LIGHT -> LightColors
        TwitterSurfaceTheme.DIM -> DimColors
        TwitterSurfaceTheme.LIGHTS_OUT -> LightsOutColors
    }
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
