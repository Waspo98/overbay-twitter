package app.overbay.twitter

import android.app.Application
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.webview.TwitterAssetBundle
import java.util.concurrent.Executors

/**
 * Process entry point that overlaps Chromium WebView startup, asset bundle loading,
 * and SharedPreferences XML warmup off the main UI thread before Activity creation.
 */
class TwitterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        TwitterConfigRepository.warmUpPrefs(this)

        val warmupExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "tw-startup-warmup").apply { isDaemon = true }
        }
        TwitterAssetBundle.preloadAsync(this, warmupExecutor)

        runCatching {
            val startUpConfig = WebViewStartUpConfig.Builder(warmupExecutor).build()
            WebViewCompat.startUpWebView(
                this,
                startUpConfig,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = Unit
                    override fun onError(error: WebViewStartupException) = Unit
                }
            )
        }
    }
}
