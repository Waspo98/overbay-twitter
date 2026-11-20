package app.overbay.twitter.webview

import android.view.HapticFeedbackConstants
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme

/**
 * Controller exposed to [app.overbay.twitter.ui.TwitterAppScreen] for navigation, reload,
 * dynamic theme sync, pull-to-refresh gating, haptic feedback, and cache clearing.
 */
class TwitterWebController {
    internal var webView: WebView? = null
    internal var swipeRefresh: SwipeRefreshLayout? = null
    internal var customViewCallback: WebChromeClient.CustomViewCallback? = null
    internal var onForceClearCustomView: (() -> Unit)? = null
    var lastKnownUrl: String = DEFAULT_HOME_URL
        internal set
    private var pullBlockedByPath = false
    private var pullBlockedByModal = false

    fun reload() {
        webView?.reload()
    }

    fun goBack() {
        webView?.goBack()
    }

    fun loadUrl(url: String) {
        lastKnownUrl = url
        webView?.loadUrl(url)
    }

    fun clearCacheAndReload() {
        webView?.clearCache(true)
        webView?.reload()
    }

    fun performLongPressHaptic() {
        webView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    internal fun applyThemeColor(colorInt: Int) {
        webView?.setBackgroundColor(colorInt)
        val surfaceTheme = TwitterSurfaceTheme.fromColorInt(colorInt)
        swipeRefresh?.setProgressBackgroundColorSchemeColor(
            when (surfaceTheme) {
                TwitterSurfaceTheme.LIGHT -> 0xFFF7F9F9.toInt()
                TwitterSurfaceTheme.DIM -> 0xFF192734.toInt()
                TwitterSurfaceTheme.LIGHTS_OUT -> 0xFF16181C.toInt()
            }
        )
    }

    internal fun updatePullToRefresh(pathBlocked: Boolean? = null, modalOpen: Boolean? = null) {
        pathBlocked?.let { pullBlockedByPath = it }
        modalOpen?.let { pullBlockedByModal = it }
        swipeRefresh?.isEnabled = !pullBlockedByPath && !pullBlockedByModal
    }

    fun hideCustomView() {
        val cb = customViewCallback
        customViewCallback = null
        if (cb != null) {
            runCatching { cb.onCustomViewHidden() }
        }
        onForceClearCustomView?.invoke()
    }

    companion object {
        const val DEFAULT_HOME_URL = "https://x.com/home"
    }
}

internal fun isPullRefreshBlockedPath(url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val schemeSep = url.indexOf("://")
    val pathStart = if (schemeSep > 0) url.indexOf('/', schemeSep + 3) else url.indexOf('/')
    if (pathStart < 0) return false
    return url.startsWith("/messages", pathStart) ||
        url.startsWith("/i/chat", pathStart) ||
        url.startsWith("/compose", pathStart)
}
