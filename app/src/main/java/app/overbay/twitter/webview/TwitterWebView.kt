package app.overbay.twitter.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.overbay.twitter.bridge.TwitterNativeBridge
import app.overbay.twitter.model.TwitterModConfig
import java.util.concurrent.atomic.AtomicReference

private val ORIGIN_ALLOWLIST = setOf(
    "https://x.com",
    "https://*.x.com",
    "https://twitter.com",
    "https://*.twitter.com"
)

/**
 * Controller exposed to [TwitterAppScreen] for navigation, reload, and cache clearing.
 */
class TwitterWebController {
    internal var webView: WebView? = null
    internal var swipeRefresh: SwipeRefreshLayout? = null
    internal var customViewCallback: WebChromeClient.CustomViewCallback? = null

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

    fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitterWebView(
    initialUrl: String,
    config: TwitterModConfig,
    configJson: String,
    controller: TwitterWebController,
    onOpenSettings: () -> Unit,
    onShowLinkActions: (String) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCustomViewChange: (View?) -> Unit,
    onFirstLoadComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assetBundle = remember(context) { TwitterAssetBundle(context) }

    val configRef = remember { AtomicReference(config) }
    val configJsonRef = remember { AtomicReference(configJson) }
    configRef.set(config)
    configJsonRef.set(configJson)

    val latestOnCanGoBack by rememberUpdatedState(onCanGoBackChange)
    val latestOnCustomView by rememberUpdatedState(onCustomViewChange)
    val latestOnFirstLoad by rememberUpdatedState(onFirstLoadComplete)

    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        pendingFileCallback = null
    }

    val configScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val supportsDocStart = remember { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }

    fun registerConfigStartScript(webView: WebView, json: String) {
        if (!supportsDocStart) return
        configScriptHandlerRef.getAndSet(null)?.remove()
        val handler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                assetBundle.buildConfigSeedScript(json),
                ORIGIN_ALLOWLIST
            )
        }.getOrNull()
        configScriptHandlerRef.set(handler)
    }

    // Live-push config edits to the running page and update DOCUMENT_START seed for future navigations.
    LaunchedEffect(config, configJson) {
        val wv = controller.webView ?: return@LaunchedEffect
        wv.settings.mediaPlaybackRequiresUserGesture = config.disableVideoAutoplay
        registerConfigStartScript(wv, configJson)
        val seedScript = assetBundle.buildConfigSeedScript(configJson)
        wv.evaluateJavascript(seedScript, null)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    controller.webView?.onPause()
                    CookieManager.getInstance().flush()
                }
                Lifecycle.Event.ON_STOP -> CookieManager.getInstance().flush()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CookieManager.getInstance().flush()
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            configScriptHandlerRef.getAndSet(null)?.remove()
            controller.webView?.destroy()
            controller.webView = null
            controller.swipeRefresh = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val isDebuggable = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            WebView.setWebContentsDebuggingEnabled(isDebuggable)

            val webView = WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xFF000000.toInt())

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mediaPlaybackRequiresUserGesture = configRef.get().disableVideoAutoplay
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    userAgentString = userAgentString
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                }

                val selfWebView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(selfWebView, true)
                }

                addJavascriptInterface(
                    TwitterNativeBridge(
                        context = ctx.applicationContext,
                        onOpenSettings = onOpenSettings,
                        onShowLinkActions = onShowLinkActions
                    ),
                    "TwitterBridge"
                )

                if (supportsDocStart) {
                    registerConfigStartScript(this, configJsonRef.get())
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            assetBundle.coreEngineScript,
                            ORIGIN_ALLOWLIST
                        )
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view.evaluateJavascript(assetBundle.buildConfigSeedScript(configJsonRef.get()), null)
                        if (!supportsDocStart) {
                            view.evaluateJavascript(assetBundle.coreEngineScript, null)
                        }
                        latestOnCanGoBack(view.canGoBack())
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        controller.swipeRefresh?.isRefreshing = false
                        CookieManager.getInstance().flush()
                        latestOnCanGoBack(view.canGoBack())
                        latestOnFirstLoad()
                    }

                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        if (configRef.get().blockAds && TwitterRequestBlocker.shouldBlockTelemetryOrAd(request.url.toString())) {
                            return TwitterRequestBlocker.emptyJsonResponse()
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host?.lowercase().orEmpty()

                        if (scheme == "https" && TwitterRequestBlocker.isInternalTwitterHost(host)) {
                            return false
                        }
                        if (scheme == "http" || scheme == "https") {
                            TwitterRequestBlocker.openExternalUrl(
                                context = ctx,
                                rawUrl = uri.toString(),
                                useCustomTab = configRef.get().openLinksInCustomTab
                            )
                            return true
                        }
                        if (scheme == "mailto" || scheme == "tel") {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, uri)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            return true
                        }
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = filePathCallback
                        val mimeTypes = fileChooserParams?.acceptTypes
                            ?.filter { it.isNotBlank() }
                            ?.toTypedArray()
                            ?.takeIf { it.isNotEmpty() }
                            ?: arrayOf("image/*", "video/*")
                        runCatching { fileChooserLauncher.launch(mimeTypes) }
                            .onFailure {
                                pendingFileCallback?.onReceiveValue(null)
                                pendingFileCallback = null
                            }
                        return true
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (view == null) return
                        controller.customViewCallback = callback
                        latestOnCustomView(view)
                    }

                    override fun onHideCustomView() {
                        controller.customViewCallback = null
                        latestOnCustomView(null)
                    }
                }
            }

            val swipeRefresh = SwipeRefreshLayout(ctx).apply {
                setColorSchemeColors(0xFF1DA1F2.toInt())
                setProgressBackgroundColorSchemeColor(0xFF15202B.toInt())
                setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 }
                setOnRefreshListener { webView.reload() }
                addView(webView)
            }

            controller.webView = webView
            controller.swipeRefresh = swipeRefresh
            webView.loadUrl(initialUrl)
            swipeRefresh
        }
    )
}
