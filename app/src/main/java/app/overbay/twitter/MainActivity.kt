package app.overbay.twitter

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.ui.TwitterAppScreen
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.ui.theme.resolveInitialColorInt
import app.overbay.twitter.webview.TwitterRequestBlocker

/**
 * Main entry point hosting the hardened Twitter WebView shell and native Mod Settings UI.
 */
class MainActivity : ComponentActivity() {

    private var deepLinkUrl by mutableStateOf<String?>(null)
    private var lastNightModeMask: Int = 0
    private var systemBarSyncToken by mutableIntStateOf(0)
    private var useLightSystemBars = false

    @Volatile
    private var isWebViewReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isWebViewReady }
        splashScreen.setOnExitAnimationListener { splashView ->
            // SplashScreen$Impl31 resets bar appearance from XML right before invoking this callback;
            // restore the active app bar style synchronously so the fade-out reveals crisp icons.
            applySystemBarEdgeToEdge(useLightSystemBars)
            systemBarSyncToken++

            val interpolator = DecelerateInterpolator()
            runCatching { splashView.iconView }.getOrNull()?.animate()
                ?.scaleX(1.08f)
                ?.scaleY(1.08f)
                ?.setDuration(160L)
                ?.setInterpolator(interpolator)
                ?.start()
            splashView.view.animate()
                .alpha(0f)
                .setDuration(160L)
                .setInterpolator(interpolator)
                .withEndAction { splashView.remove() }
                .start()
        }

        super.onCreate(savedInstanceState)
        lastNightModeMask = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isSystemDark = lastNightModeMask == Configuration.UI_MODE_NIGHT_YES
        val repository = TwitterConfigRepository.getInstance(this)
        val initialColorInt = repository.config.value.themeMode.resolveInitialColorInt(
            lastColorInt = repository.lastThemeColor.value,
            isSystemDark = isSystemDark,
            autoDarkVariant = repository.config.value.autoDarkVariant
        )
        val persistedLight = TwitterSurfaceTheme.fromColorInt(initialColorInt).isLight
        useLightSystemBars = persistedLight
        applySystemBarEdgeToEdge(persistedLight)

        handleIncomingIntent(intent)

        setContent {
            TwitterAppScreen(
                repository = repository,
                deepLinkUrl = deepLinkUrl,
                onDeepLinkConsumed = { deepLinkUrl = null },
                onFirstLoadComplete = { isWebViewReady = true },
                systemBarSyncToken = systemBarSyncToken,
                onLightSystemBarsChange = { useLightSystemBars = it }
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            systemBarSyncToken++
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val nextNightMask = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nextNightMask != lastNightModeMask) {
            lastNightModeMask = nextNightMask
            // Refresh Activity theme attributes (including android:isLightTheme) for WebView prefers-color-scheme.
            val refreshedTheme = resources.newTheme()
            refreshedTheme.applyStyle(R.style.Theme_Twitter, true)
            theme.setTo(refreshedTheme)
            systemBarSyncToken++
        }
        super.onConfigurationChanged(newConfig)
    }

    private fun applySystemBarEdgeToEdge(lightBars: Boolean) {
        val barStyle = if (lightBars) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val host = uri.host.orEmpty()
        if (uri.scheme == "https" && TwitterRequestBlocker.isInternalTwitterHost(host)) {
            deepLinkUrl = uri.toString().replace(TWITTER_HOST_PREFIX_REGEX, "https://x.com")
        }
    }

    companion object {
        private val TWITTER_HOST_PREFIX_REGEX =
            Regex("^https://(?:www\\.|mobile\\.)?twitter\\.com", RegexOption.IGNORE_CASE)
    }
}
