package app.overbay.twitter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val TwitterBlue = Color(0xFF1DA1F2)
val TwitterBlueDark = Color(0xFF1A8CD8)

private val DarkColors = darkColorScheme(
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
    surfaceContainerLow = Color(0xFF15202B),
    surfaceContainer = Color(0xFF192734),
    surfaceContainerHigh = Color(0xFF22303C),
    outline = Color(0xFF38444D),
    outlineVariant = Color(0xFF2F3336)
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
