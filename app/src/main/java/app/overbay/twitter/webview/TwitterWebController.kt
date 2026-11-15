package app.overbay.twitter.webview

import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.core.graphics.ColorUtils
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Controller exposed to [app.overbay.twitter.ui.TwitterAppScreen] for navigation, reload,
 * dynamic theme sync, pull-to-refresh gating, and cache clearing.
 */
class TwitterWebController {
    internal var webView: WebView? = null
    internal var swipeRefresh: SwipeRefreshLayout? = null
    internal var customViewCallback: WebChromeClient.CustomViewCallback? = null
    internal var onForceClearCustomView: (() -> Unit)? = null
    private var pullBlockedByPath = false
    private var pullBlockedByModal = false

    fun reload() {
        webView?.reload()
    }

    fun goBack() {
        webView?.goBack()
    }

    fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }

    fun clearCacheAndReload() {
        webView?.clearCache(true)
        webView?.reload()
    }

    internal fun applyThemeColor(colorInt: Int) {
        webView?.setBackgroundColor(colorInt)
        val isLight = ColorUtils.calculateLuminance(colorInt) > 0.5
        swipeRefresh?.setProgressBackgroundColorSchemeColor(
            if (isLight) 0xFFF7F9F9.toInt() else 0xFF192734.toInt()
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
