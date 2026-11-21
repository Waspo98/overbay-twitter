package app.overbay.twitter.webview

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import app.overbay.twitter.model.TwitterModConfig
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Hardened [WebViewClient] handling GraphQL/telemetry interception, external link routing,
 * renderer crash recovery, and friendly main-frame offline error reporting.
 */
internal class TwitterWebViewClient(
    private val context: Context,
    private val assetBundle: TwitterAssetBundle,
    private val controller: TwitterWebController,
    private val docStartSupported: Boolean,
    private val configRef: AtomicReference<TwitterModConfig>,
    private val configJsonRef: AtomicReference<String>,
    private val isSystemDarkRef: AtomicReference<Boolean>,
    private val themeColorRef: AtomicInteger,
    private val consumeFallbackRestoreJson: () -> String? = { null },
    private val onClearOneShotRestoreScript: () -> Unit = {},
    private val onCanGoBackChange: (Boolean) -> Unit,
    private val onFirstLoadComplete: () -> Unit,
    private val onMainFrameError: (String?) -> Unit,
    private val onRendererCrash: () -> Unit
) : WebViewClient() {

    private var lastCookieFlushElapsedMs = 0L

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (TwitterWebController.isSafeInternalUrl(url)) controller.lastKnownUrl = url!!
        onMainFrameError(null)
        if (!docStartSupported) {
            view.evaluateJavascript(
                assetBundle.buildConfigSeedScript(
                    configJson = configJsonRef.get(),
                    isSystemDark = isSystemDarkRef.get(),
                    restoreStateJson = consumeFallbackRestoreJson()
                ),
                null
            )
            view.evaluateJavascript(assetBundle.coreEngineScript, null)
        }
        controller.updatePullToRefresh(
            pathBlocked = isPullRefreshBlockedPath(url),
            modalOpen = false
        )
        onCanGoBackChange(view.canGoBack())
    }

    override fun onPageCommitVisible(view: WebView, url: String?) {
        super.onPageCommitVisible(view, url)
        onFirstLoadComplete()
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        if (TwitterWebController.isSafeInternalUrl(url)) controller.lastKnownUrl = url!!
        if (view != null) onCanGoBackChange(view.canGoBack())
        controller.updatePullToRefresh(pathBlocked = isPullRefreshBlockedPath(url))
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onClearOneShotRestoreScript()
        controller.swipeRefresh?.isRefreshing = false
        val now = SystemClock.elapsedRealtime()
        val isAuthRoute = url?.contains("/i/flow/login", ignoreCase = true) == true
        if (lastCookieFlushElapsedMs == 0L || isAuthRoute || now - lastCookieFlushElapsedMs >= 15_000L) {
            lastCookieFlushElapsedMs = now
            CookieManager.getInstance().flush()
        }
        onCanGoBackChange(view.canGoBack())
        onFirstLoadComplete()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        val code = error.errorCode
        if (code == ERROR_UNSUPPORTED_SCHEME || code == ERROR_REDIRECT_LOOP) return
        controller.swipeRefresh?.isRefreshing = false
        onFirstLoadComplete()
        val message = when (code) {
            ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_IO -> "No internet connection. Check your network and try again."
            ERROR_TIMEOUT -> "Connection timed out while loading Twitter."
            else -> error.description?.toString()?.takeIf { it.isNotBlank() } ?: "Unable to load Twitter right now."
        }
        onMainFrameError(message)
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        controller.swipeRefresh?.isRefreshing = false
        onRendererCrash()
        return true
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val cfg = configRef.get()
        if (!cfg.blockAds && !cfg.removeGrok) return super.shouldInterceptRequest(view, request)
        val urlString = request.url.toString()
        val blockTelemetry = cfg.blockAds && TwitterRequestBlocker.shouldBlockTelemetryOrAd(urlString)
        val blockGrok = !blockTelemetry && cfg.removeGrok && TwitterRequestBlocker.shouldBlockGrokApi(urlString)
        if (blockTelemetry || blockGrok) {
            val origin = request.requestHeaders?.entries
                ?.firstOrNull { it.key.equals("Origin", ignoreCase = true) }
                ?.value
            return TwitterRequestBlocker.emptyJsonResponse(
                origin = origin,
                isJsonEndpoint = blockGrok || TwitterRequestBlocker.isJsonTelemetryUrl(urlString)
            )
        }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        if (!request.isForMainFrame) return false
        val uri = request.url
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host.orEmpty()

        if (scheme == "https" && TwitterRequestBlocker.isInternalTwitterHost(host)) {
            return false
        }
        if (scheme == "http" || scheme == "https") {
            TwitterRequestBlocker.openExternalUrl(
                context = context,
                rawUrl = uri.toString(),
                useCustomTab = configRef.get().openLinksInCustomTab,
                toolbarColor = themeColorRef.get()
            )
            return true
        }
        if (scheme == "mailto" || scheme == "tel") {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return true
        }
        return true
    }
}

/**
 * [WebChromeClient] supporting single/multiple media uploads and immersive fullscreen video playback.
 */
internal class TwitterWebChromeClient(
    private val controller: TwitterWebController,
    private val onLaunchFileChooser: (
        callback: ValueCallback<Array<Uri>>?,
        mimeTypes: Array<String>,
        allowMultiple: Boolean
    ) -> Unit,
    private val onCustomViewChange: (View?) -> Unit
) : WebChromeClient() {

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        val mimeTypes = TwitterRequestBlocker.normalizeAcceptTypes(
            fileChooserParams?.acceptTypes
        ) { ext ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        }
        val allowMultiple = fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        onLaunchFileChooser(filePathCallback, mimeTypes, allowMultiple)
        return true
    }

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) return
        controller.customViewCallback = callback
        onCustomViewChange(view)
    }

    override fun onHideCustomView() {
        controller.customViewCallback = null
        onCustomViewChange(null)
    }
}
