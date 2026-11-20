package app.overbay.twitter.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject

/**
 * Native callback surface exposed to injected WebView scripts.
 *
 * Primarily driven via origin-scoped `WebViewCompat.addWebMessageListener` (`__twNativePort`)
 * so third-party iframes cannot access native hooks, with `@JavascriptInterface` fallback
 * retained only for older WebViews lacking `WEB_MESSAGE_LISTENER`.
 */
class TwitterNativeBridge(
    private val context: Context,
    private val onOpenSettings: (haptic: Boolean) -> Unit,
    private val onShowLinkActions: (String) -> Unit,
    private val onPullToRefreshModalChange: (Boolean) -> Unit = {},
    private val onReportHomeFeeds: (String) -> Unit = {},
    private val onThemeColorChange: (Int) -> Unit = {},
    private val onStatsDelta: (adsDelta: Int, clutterDelta: Int) -> Unit = { _, _ -> }
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Dispatches a JSON message posted over the origin-scoped `WebMessageListener` channel.
     */
    fun handleWebMessage(rawJson: String) {
        val obj = runCatching { JSONObject(rawJson) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "openSettings" -> dispatchOpenSettings(obj.optBoolean("haptic", false))
            "downloadMedia" -> downloadMedia(obj.optString("url", ""), obj.optString("filenameHint", ""))
            "showLinkActions" -> showLinkActions(obj.optString("url", ""))
            "showToast" -> showToast(obj.optString("message", ""))
            "setPullToRefreshModalOpen" -> setPullToRefreshModalOpen(obj.optBoolean("open", false))
            "reportHomeFeeds" -> reportHomeFeeds(obj.optString("feedsJson", ""))
            "onThemeColorChanged" -> onThemeColorChanged(obj.optString("hexColor", ""))
            "reportStatsDelta" -> reportStatsDelta(obj.optInt("ads", 0), obj.optInt("clutter", 0))
        }
    }

    @JavascriptInterface
    fun openSettings() {
        dispatchOpenSettings(haptic = false)
    }

    @JavascriptInterface
    fun openSettingsWithHaptic() {
        dispatchOpenSettings(haptic = true)
    }

    private fun dispatchOpenSettings(haptic: Boolean) {
        postToMain { onOpenSettings(haptic) }
    }

    @JavascriptInterface
    fun downloadMedia(url: String, filenameHint: String) {
        if (url.isBlank()) {
            showToast("Could not resolve media stream")
            return
        }
        postToMain {
            MediaDownloader.enqueue(context, url, filenameHint)
                .onSuccess {
                    Toast.makeText(context, MediaDownloader.successToastMessage, Toast.LENGTH_SHORT).show()
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
        postToMain { onShowLinkActions(url) }
    }

    @JavascriptInterface
    fun showToast(message: String) {
        if (message.isBlank()) return
        postToMain {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun setPullToRefreshModalOpen(open: Boolean) {
        postToMain { onPullToRefreshModalChange(open) }
    }

    @JavascriptInterface
    fun reportHomeFeeds(feedsJson: String) {
        if (feedsJson.isBlank()) return
        postToMain { onReportHomeFeeds(feedsJson) }
    }

    @JavascriptInterface
    fun onThemeColorChanged(hexColor: String) {
        val parsed = parseHexColor(hexColor) ?: return
        postToMain { onThemeColorChange(parsed) }
    }

    @JavascriptInterface
    fun reportStatsDelta(adsDelta: Int, clutterDelta: Int) {
        if (adsDelta <= 0 && clutterDelta <= 0) return
        postToMain { onStatsDelta(adsDelta.coerceAtLeast(0), clutterDelta.coerceAtLeast(0)) }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    companion object {
        internal fun parseHexColor(hexColor: String): Int? {
            if (hexColor.length != 7 || hexColor[0] != '#') return null
            val rgb = hexColor.substring(1).toIntOrNull(16) ?: return null
            return (0xFF shl 24) or rgb
        }
    }
}
