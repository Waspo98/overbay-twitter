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
import app.overbay.twitter.ui.theme.TwitterTheme
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
                ?.scaleX(1.1f)
                ?.scaleY(1.1f)
                ?.setDuration(180L)
                ?.setInterpolator(interpolator)
                ?.start()
            splashView.view.animate()
                .alpha(0f)
                .setDuration(180L)
                .setInterpolator(interpolator)
                .withEndAction { splashView.remove() }
                .start()
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        handleIncomingIntent(intent)
        val repository = TwitterConfigRepository(this)

        setContent {
            TwitterTheme {
                TwitterAppScreen(
                    repository = repository,
                    deepLinkUrl = deepLinkUrl,
                    onDeepLinkConsumed = { deepLinkUrl = null },
                    onFirstLoadComplete = { isWebViewReady = true }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme == "https" && TwitterRequestBlocker.isInternalTwitterHost(host)) {
            deepLinkUrl = uri.toString().replace(
                Regex("^https://(?:www\\.|mobile\\.)?twitter\\.com", RegexOption.IGNORE_CASE),
                "https://x.com"
            )
        }
    }
}
