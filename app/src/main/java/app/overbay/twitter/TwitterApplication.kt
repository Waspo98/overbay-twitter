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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process entry point that overlaps Chromium WebView startup, asset bundle loading,
 * and SharedPreferences XML warmup off the main UI thread before Activity creation.
 */
class TwitterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        TwitterConfigRepository.warmUpPrefs(this)

        val pendingWarmupTasks = AtomicInteger(2)
        val warmupExecutor = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "tw-startup-warmup").apply { isDaemon = true }
        }
        val markWarmupTaskDone = {
            if (pendingWarmupTasks.decrementAndGet() == 0) {
                warmupExecutor.shutdown()
            }
        }

        TwitterAssetBundle.preloadAsync(this, warmupExecutor, onComplete = markWarmupTaskDone)

        runCatching {
            val startUpConfig = WebViewStartUpConfig.Builder(warmupExecutor).build()
            WebViewCompat.startUpWebView(
                this,
                startUpConfig,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) = markWarmupTaskDone()
                    override fun onError(error: WebViewStartupException) = markWarmupTaskDone()
                }
            )
        }.onFailure {
            markWarmupTaskDone()
        }
    }
}
