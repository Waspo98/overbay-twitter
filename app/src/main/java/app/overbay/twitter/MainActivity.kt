package app.overbay.twitter

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.ui.TwitterAppScreen
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.webview.TwitterRequestBlocker

/**
 * Main entry point hosting the hardened Twitter WebView shell and native Mod Settings UI.
 */
class MainActivity : ComponentActivity() {

    private var deepLinkUrl by mutableStateOf<String?>(null)

    @Volatile
    private var isWebViewReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !isWebViewReady }
        splashScreen.setOnExitAnimationListener { splashView ->
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
        val repository = TwitterConfigRepository.getInstance(this)
        val persistedLight = repository.lastThemeColor.value?.let {
            TwitterSurfaceTheme.fromColorInt(it).isLight
        } ?: false
        val initialBarStyle = if (persistedLight) {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        } else {
            SystemBarStyle.dark(Color.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = initialBarStyle,
            navigationBarStyle = initialBarStyle
        )

        handleIncomingIntent(intent)

        setContent {
            TwitterAppScreen(
                repository = repository,
                deepLinkUrl = deepLinkUrl,
                onDeepLinkConsumed = { deepLinkUrl = null },
                onFirstLoadComplete = { isWebViewReady = true }
            )
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
