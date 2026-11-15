package app.overbay.twitter.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * Native callback surface exposed to injected WebView scripts at `window.TwitterBridge`.
 */
class TwitterNativeBridge(
    private val context: Context,
    private val onOpenSettings: () -> Unit,
    private val onShowLinkActions: (String) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openSettings() {
        mainHandler.post { onOpenSettings() }
    }

    @JavascriptInterface
    fun downloadMedia(url: String, filenameHint: String) {
        if (url.isBlank()) {
            showToast("Could not resolve media stream")
            return
        }
        mainHandler.post {
            MediaDownloader.enqueue(context, url, filenameHint)
                .onSuccess {
                    Toast.makeText(context, "Saving to Downloads/Twitter...", Toast.LENGTH_SHORT).show()
                }
                .onFailure { err ->
                    Toast.makeText(
                        context,
                        err.message ?: "Media download failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
        }
    }

    @JavascriptInterface
    fun showLinkActions(url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        mainHandler.post { onShowLinkActions(url) }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        if (message.isBlank()) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
