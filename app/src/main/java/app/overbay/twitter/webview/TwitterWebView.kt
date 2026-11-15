package app.overbay.twitter.webview

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
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
import androidx.compose.runtime.SideEffect
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TwitterWebView(
    initialUrl: String,
    config: TwitterModConfig,
    configJson: String,
    isFullyUnfolded: Boolean,
    controller: TwitterWebController,
    onOpenSettings: () -> Unit,
    onShowLinkActions: (String) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCustomViewChange: (View?) -> Unit,
    onFirstLoadComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onReportHomeFeeds: (String) -> Unit = {},
    onThemeColorChange: (Int) -> Unit = {},
    onWebViewAttached: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assetBundle = remember(context) { TwitterAssetBundle(context) }

    val configRef = remember { AtomicReference(config) }
    val configJsonRef = remember { AtomicReference(configJson) }
    val isUnfoldedRef = remember { AtomicReference(isFullyUnfolded) }

    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnShowLinkActions by rememberUpdatedState(onShowLinkActions)
    val latestOnCanGoBack by rememberUpdatedState(onCanGoBackChange)
    val latestOnCustomView by rememberUpdatedState(onCustomViewChange)
    val latestOnFirstLoad by rememberUpdatedState(onFirstLoadComplete)
    val latestOnReportHomeFeeds by rememberUpdatedState(onReportHomeFeeds)
    val latestOnThemeColorChange by rememberUpdatedState(onThemeColorChange)
    val latestOnWebViewAttached by rememberUpdatedState(onWebViewAttached)

    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        pendingFileCallback = null
    }

    val configScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val coreScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val lastSeededStateRef = remember { AtomicReference<Pair<String, Boolean>?>(null) }
    val baseUserAgentRef = remember { AtomicReference<String?>(null) }

    fun registerConfigStartScript(webView: WebView, json: String, unfolded: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        configScriptHandlerRef.getAndSet(null)?.remove()
        val handler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                assetBundle.buildConfigSeedScript(json, unfolded),
                ORIGIN_ALLOWLIST
            )
        }.getOrNull()
        configScriptHandlerRef.set(handler)
    }

    // Synchronous zero-latency config & posture sync upon committed composition.
    SideEffect {
        configRef.set(config)
        configJsonRef.set(configJson)
        isUnfoldedRef.set(isFullyUnfolded)

        val wv = controller.webView ?: return@SideEffect
        if (wv.settings.mediaPlaybackRequiresUserGesture != config.disableVideoAutoplay) {
            wv.settings.mediaPlaybackRequiresUserGesture = config.disableVideoAutoplay
        }
        baseUserAgentRef.get()?.let { baseUa ->
            val targetUa = TwitterRequestBlocker.buildEffectiveUserAgent(baseUa, isFullyUnfolded)
            if (wv.settings.userAgentString != targetUa) {
                wv.settings.userAgentString = targetUa
            }
        }
        val stateKey = configJson to isFullyUnfolded
        if (lastSeededStateRef.getAndSet(stateKey) != stateKey) {
            registerConfigStartScript(wv, configJson, isFullyUnfolded)
            wv.evaluateJavascript(assetBundle.buildConfigSeedScript(configJson, isFullyUnfolded), null)
        }
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
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                configScriptHandlerRef.getAndSet(null)?.remove()
                coreScriptHandlerRef.getAndSet(null)?.remove()
            } else {
                configScriptHandlerRef.set(null)
                coreScriptHandlerRef.set(null)
            }
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
                    allowFileAccess = false
                    allowContentAccess = false
                    setGeolocationEnabled(false)
                    mediaPlaybackRequiresUserGesture = configRef.get().disableVideoAutoplay
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    baseUserAgentRef.set(userAgentString)
                    userAgentString = TwitterRequestBlocker.buildEffectiveUserAgent(
                        userAgentString,
                        isUnfoldedRef.get()
                    )
                }

                val selfWebView = this
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(selfWebView, true)
                }

                addJavascriptInterface(
                    TwitterNativeBridge(
                        context = ctx.applicationContext,
                        onOpenSettings = { latestOnOpenSettings() },
                        onShowLinkActions = { url -> latestOnShowLinkActions(url) },
                        onPullToRefreshModalChange = { open -> controller.updatePullToRefresh(modalOpen = open) },
                        onReportHomeFeeds = { json -> latestOnReportHomeFeeds(json) },
                        onThemeColorChange = { colorInt ->
                            controller.applyThemeColor(colorInt)
                            latestOnThemeColorChange(colorInt)
                        }
                    ),
                    "TwitterBridge"
                )

                val docStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (docStartSupported) {
                    val initialJson = configJsonRef.get()
                    val initialUnfolded = isUnfoldedRef.get()
                    lastSeededStateRef.set(initialJson to initialUnfolded)
                    registerConfigStartScript(this, initialJson, initialUnfolded)
                    val coreHandler = runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            assetBundle.coreEngineScript,
                            ORIGIN_ALLOWLIST
                        )
                    }.getOrNull()
                    coreScriptHandlerRef.set(coreHandler)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (!docStartSupported) {
                            view.evaluateJavascript(
                                assetBundle.buildConfigSeedScript(configJsonRef.get(), isUnfoldedRef.get()),
                                null
                            )
                            view.evaluateJavascript(assetBundle.coreEngineScript, null)
                        }
                        controller.updatePullToRefresh(
                            pathBlocked = isPullRefreshBlockedPath(url),
                            modalOpen = false
                        )
                        latestOnCanGoBack(view.canGoBack())
                    }

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        super.onPageCommitVisible(view, url)
                        latestOnFirstLoad()
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        if (view != null) {
                            latestOnCanGoBack(view.canGoBack())
                        }
                        controller.updatePullToRefresh(pathBlocked = isPullRefreshBlockedPath(url))
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
                        if (!request.isForMainFrame) return false
                        val uri = request.url
                        val scheme = uri.scheme?.lowercase().orEmpty()
                        val host = uri.host.orEmpty()

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
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
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
                        val mimeTypes = TwitterRequestBlocker.normalizeAcceptTypes(
                            fileChooserParams?.acceptTypes
                        ) { ext ->
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                        }
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
                setProgressBackgroundColorSchemeColor(0xFF192734.toInt())
                setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || webView.canScrollVertically(-1) }
                setOnRefreshListener { webView.reload() }
                addView(webView)
            }

            controller.webView = webView
            controller.swipeRefresh = swipeRefresh
            controller.updatePullToRefresh(pathBlocked = isPullRefreshBlockedPath(initialUrl))
            webView.loadUrl(initialUrl)
            latestOnWebViewAttached()
            swipeRefresh
        }
    )
}
