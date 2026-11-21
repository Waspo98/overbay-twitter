package app.overbay.twitter.webview

import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterThemeMode
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import java.util.concurrent.atomic.AtomicInteger

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
    private var themeSyncGeneration = 0
    private var pendingFallbackRunnable: Runnable? = null
    private var pendingFallbackTargetDark: Boolean? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun reload() {
        val wv = webView ?: return
        if (wv.url.isNullOrBlank()) {
            wv.loadUrl(lastKnownUrl)
        } else {
            wv.reload()
        }
    }

    fun goBack() {
        webView?.goBack()
    }

    fun loadUrl(url: String) {
        if (isSafeInternalUrl(url)) {
            lastKnownUrl = url
        }
        webView?.loadUrl(url)
    }

    internal fun captureCurrentUrlIfInternal(): String {
        val candidate = webView?.url
        if (isSafeInternalUrl(candidate)) {
            lastKnownUrl = candidate!!
        } else if (!isSafeInternalUrl(lastKnownUrl)) {
            lastKnownUrl = DEFAULT_HOME_URL
        }
        return lastKnownUrl
    }

    fun clearCacheAndReload() {
        webView?.clearCache(true)
        webView?.reload()
    }

    fun performLongPressHaptic() {
        webView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    internal fun attach(webView: WebView, swipeRefresh: SwipeRefreshLayout) {
        this.webView = webView
        this.swipeRefresh = swipeRefresh
    }

    internal fun detachIfOwned(ownedWebView: WebView?, ownedSwipeRefresh: SwipeRefreshLayout?) {
        if (ownedWebView != null && webView === ownedWebView) {
            cancelPendingThemeFallback()
            webView = null
        }
        if (ownedSwipeRefresh != null && swipeRefresh === ownedSwipeRefresh) {
            swipeRefresh = null
        }
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

    internal fun syncStartupNightModeCookie(
        mode: TwitterThemeMode,
        isSystemDark: Boolean,
        onReady: () -> Unit
    ) {
        val cm = CookieManager.getInstance()
        val existing = extractNightModeCookie(cm.getCookie(DEFAULT_HOME_URL))
        val target = resolveTargetNightModeCookie(mode, isSystemDark)
        if (existing == target) {
            onReady()
        } else {
            syncNightModeCookies(cm, target, onReady)
        }
    }

    internal fun onObservedSurfaceTheme(observedSurface: TwitterSurfaceTheme) {
        val targetDark = pendingFallbackTargetDark ?: return
        if (observedSurface.isLight != targetDark) {
            cancelPendingThemeFallback()
        }
    }

    private fun cancelPendingThemeFallback() {
        pendingFallbackRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingFallbackRunnable = null
        pendingFallbackTargetDark = null
    }

    internal fun applyThemeMode(
        mode: TwitterThemeMode,
        autoDarkVariant: TwitterDarkVariant,
        isSystemDark: Boolean,
        currentSurfaceProvider: () -> TwitterSurfaceTheme,
        allowReloadFallback: Boolean = true,
        onPreviewColor: (Int) -> Unit = {}
    ) {
        cancelPendingThemeFallback()
        val generation = ++themeSyncGeneration
        val cm = CookieManager.getInstance()
        val existing = extractNightModeCookie(cm.getCookie(DEFAULT_HOME_URL))
        val target = resolveTargetNightModeCookie(mode, isSystemDark)
        val targetDark = isDarkMode(mode, isSystemDark)
        val cookieCommitted = java.util.concurrent.atomic.AtomicBoolean(existing == target)
        val reloadAfterCookie = java.util.concurrent.atomic.AtomicBoolean(false)

        if (existing != target) {
            syncNightModeCookies(cm, target) {
                cookieCommitted.set(true)
                if (reloadAfterCookie.get() && generation == themeSyncGeneration) {
                    reload()
                }
            }
        }

        val currentSurface = currentSurfaceProvider()
        if (!allowReloadFallback || currentSurface.isLight != targetDark) {
            return
        }

        // Wait briefly so X's live matchMedia('prefers-color-scheme') listener can transition
        // without reloading; reload only as fallback if X's page light/dark polarity didn't flip.
        pendingFallbackTargetDark = targetDark
        val fallback = Runnable {
            pendingFallbackRunnable = null
            pendingFallbackTargetDark = null
            if (generation != themeSyncGeneration) return@Runnable
            if (currentSurfaceProvider().isLight != targetDark) return@Runnable

            val previewSurface = resolveTargetSurfaceTheme(mode, isSystemDark, autoDarkVariant)
            applyThemeColor(previewSurface.backgroundColorInt)
            onPreviewColor(previewSurface.backgroundColorInt)
            if (cookieCommitted.get()) {
                reload()
            } else {
                reloadAfterCookie.set(true)
            }
        }
        pendingFallbackRunnable = fallback
        mainHandler.postDelayed(fallback, THEME_FALLBACK_RELOAD_DELAY_MS)
    }

    private fun syncNightModeCookies(
        cookieManager: CookieManager,
        nightModeValue: String,
        onCommitted: (() -> Unit)? = null
    ) {
        val writes = listOf(
            "https://x.com" to "night_mode=$nightModeValue; Path=/; Max-Age=31536000; Secure",
            "https://x.com" to "night_mode=$nightModeValue; Path=/; Domain=.x.com; Max-Age=31536000; Secure",
            "https://twitter.com" to "night_mode=$nightModeValue; Path=/; Domain=.twitter.com; Max-Age=31536000; Secure"
        )
        val remaining = AtomicInteger(writes.size)
        writes.forEach { (url, cookie) ->
            cookieManager.setCookie(url, cookie) {
                if (remaining.decrementAndGet() == 0) {
                    cookieManager.flush()
                    if (onCommitted != null) {
                        if (Looper.myLooper() == Looper.getMainLooper()) {
                            onCommitted()
                        } else {
                            mainHandler.post(onCommitted)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_HOME_URL = "https://x.com/home"
        private const val THEME_FALLBACK_RELOAD_DELAY_MS = 380L
        private val NIGHT_MODE_COOKIE_REGEX = Regex("""(?:^|;\s*)night_mode=([012])(?:\s*;|$)""")

        internal fun extractNightModeCookie(cookieHeader: String?): String? {
            if (cookieHeader.isNullOrBlank()) return null
            return NIGHT_MODE_COOKIE_REGEX.find(cookieHeader)?.groupValues?.getOrNull(1)
        }

        internal fun isDarkMode(mode: TwitterThemeMode, isSystemDark: Boolean): Boolean {
            return when (mode) {
                TwitterThemeMode.LIGHT -> false
                TwitterThemeMode.DIM, TwitterThemeMode.LIGHTS_OUT -> true
                TwitterThemeMode.AUTO -> isSystemDark
            }
        }

        internal fun resolveTargetNightModeCookie(
            mode: TwitterThemeMode,
            isSystemDark: Boolean
        ): String {
            return if (isDarkMode(mode, isSystemDark)) "2" else "0"
        }

        internal fun resolveTargetSurfaceTheme(
            mode: TwitterThemeMode,
            isSystemDark: Boolean,
            autoDarkVariant: TwitterDarkVariant = TwitterDarkVariant.LIGHTS_OUT
        ): TwitterSurfaceTheme {
            return when (mode) {
                TwitterThemeMode.LIGHT -> TwitterSurfaceTheme.LIGHT
                TwitterThemeMode.DIM -> TwitterSurfaceTheme.DIM
                TwitterThemeMode.LIGHTS_OUT -> TwitterSurfaceTheme.LIGHTS_OUT
                TwitterThemeMode.AUTO -> when {
                    !isSystemDark -> TwitterSurfaceTheme.LIGHT
                    autoDarkVariant == TwitterDarkVariant.DIM -> TwitterSurfaceTheme.DIM
                    else -> TwitterSurfaceTheme.LIGHTS_OUT
                }
            }
        }

        internal fun isSafeInternalUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            if (!url.startsWith("https://", ignoreCase = true)) return false
            val hostStart = 8
            var hostEnd = url.length
            for (i in hostStart until url.length) {
                val ch = url[i]
                if (ch == '/' || ch == ':' || ch == '?' || ch == '#') {
                    hostEnd = i
                    break
                }
            }
            if (hostEnd <= hostStart) return false
            val host = url.substring(hostStart, hostEnd)
            return TwitterRequestBlocker.isInternalTwitterHost(host)
        }

        internal fun shouldReloadForThemeMode(
            mode: TwitterThemeMode,
            isSystemDark: Boolean,
            currentSurface: TwitterSurfaceTheme
        ): Boolean {
            return currentSurface.isLight == isDarkMode(mode, isSystemDark)
        }
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
