package app.overbay.twitter.webview

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.overbay.twitter.bridge.TwitterNativeBridge
import app.overbay.twitter.model.TwitterModConfig
import java.util.concurrent.atomic.AtomicInteger
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
    initialThemeColorInt: Int,
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
    onStatsDelta: (Int, Int) -> Unit = { _, _ -> },
    onMainFrameError: (String?) -> Unit = {},
    onRendererCrash: () -> Unit = {},
    onPullRefresh: () -> Unit = {},
    onWebViewAttached: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val assetBundle = remember(context) { TwitterAssetBundle.getInstance(context) }

    val configRef = remember { AtomicReference(config) }
    val configJsonRef = remember { AtomicReference(configJson) }
    val isUnfoldedRef = remember { AtomicReference(isFullyUnfolded) }
    val themeColorRef = remember { AtomicInteger(initialThemeColorInt) }

    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnShowLinkActions by rememberUpdatedState(onShowLinkActions)
    val latestOnCanGoBack by rememberUpdatedState(onCanGoBackChange)
    val latestOnCustomView by rememberUpdatedState(onCustomViewChange)
    val latestOnFirstLoad by rememberUpdatedState(onFirstLoadComplete)
    val latestOnReportHomeFeeds by rememberUpdatedState(onReportHomeFeeds)
    val latestOnThemeColorChange by rememberUpdatedState(onThemeColorChange)
    val latestOnStatsDelta by rememberUpdatedState(onStatsDelta)
    val latestOnMainFrameError by rememberUpdatedState(onMainFrameError)
    val latestOnRendererCrash by rememberUpdatedState(onRendererCrash)
    val latestOnPullRefresh by rememberUpdatedState(onPullRefresh)
    val latestOnWebViewAttached by rememberUpdatedState(onWebViewAttached)

    var pendingFileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val multiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        pendingFileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        pendingFileCallback = null
    }
    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingFileCallback?.onReceiveValue(if (uri == null) null else arrayOf(uri))
        pendingFileCallback = null
    }

    val configScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val coreScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val lastSeededStateRef = remember { AtomicReference<Pair<String, Boolean>?>(null) }
    val baseUserAgentRef = remember { AtomicReference<String?>(null) }
    var webViewAttached by remember { mutableStateOf(false) }

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

    // Keep atomic refs fresh for background IO-thread callbacks without invoking JNI on every recompose.
    SideEffect {
        configRef.set(config)
        configJsonRef.set(configJson)
        isUnfoldedRef.set(isFullyUnfolded)
        themeColorRef.set(initialThemeColorInt)
    }

    LaunchedEffect(webViewAttached, config.disableVideoAutoplay) {
        val wv = controller.webView ?: return@LaunchedEffect
        if (wv.settings.mediaPlaybackRequiresUserGesture != config.disableVideoAutoplay) {
            wv.settings.mediaPlaybackRequiresUserGesture = config.disableVideoAutoplay
        }
    }

    LaunchedEffect(webViewAttached, isFullyUnfolded) {
        val wv = controller.webView ?: return@LaunchedEffect
        baseUserAgentRef.get()?.let { baseUa ->
            val targetUa = TwitterRequestBlocker.buildEffectiveUserAgent(baseUa, isFullyUnfolded)
            if (wv.settings.userAgentString != targetUa) {
                wv.settings.userAgentString = targetUa
            }
        }
    }

    LaunchedEffect(webViewAttached, configJson, isFullyUnfolded) {
        val wv = controller.webView ?: return@LaunchedEffect
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
            val wv = controller.webView
            if (wv != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(wv, TwitterAssetBundle.NATIVE_PORT_NAME) }
            }
            wv?.destroy()
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
                setBackgroundColor(initialThemeColorInt)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
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

                val bridge = TwitterNativeBridge(
                    context = ctx.applicationContext,
                    onOpenSettings = { haptic ->
                        if (haptic) controller.performLongPressHaptic()
                        latestOnOpenSettings()
                    },
                    onShowLinkActions = { url ->
                        controller.performLongPressHaptic()
                        latestOnShowLinkActions(url)
                    },
                    onPullToRefreshModalChange = { open -> controller.updatePullToRefresh(modalOpen = open) },
                    onReportHomeFeeds = { json -> latestOnReportHomeFeeds(json) },
                    onThemeColorChange = { colorInt ->
                        themeColorRef.set(colorInt)
                        controller.applyThemeColor(colorInt)
                        latestOnThemeColorChange(colorInt)
                    },
                    onStatsDelta = { ads, clutter -> latestOnStatsDelta(ads, clutter) }
                )

                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(
                        this,
                        TwitterAssetBundle.NATIVE_PORT_NAME,
                        ORIGIN_ALLOWLIST
                    ) { _, message, sourceOrigin, isMainFrame, _ ->
                        if (!isMainFrame) return@addWebMessageListener
                        if (!TwitterRequestBlocker.isInternalTwitterHost(sourceOrigin.host.orEmpty())) return@addWebMessageListener
                        message.data?.let { bridge.handleWebMessage(it) }
                    }
                } else {
                    addJavascriptInterface(bridge, "TwitterBridge")
                }

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

                webViewClient = TwitterWebViewClient(
                    context = ctx,
                    assetBundle = assetBundle,
                    controller = controller,
                    docStartSupported = docStartSupported,
                    configRef = configRef,
                    configJsonRef = configJsonRef,
                    isUnfoldedRef = isUnfoldedRef,
                    themeColorRef = themeColorRef,
                    onCanGoBackChange = { latestOnCanGoBack(it) },
                    onFirstLoadComplete = { latestOnFirstLoad() },
                    onMainFrameError = { latestOnMainFrameError(it) },
                    onRendererCrash = { latestOnRendererCrash() }
                )

                webChromeClient = TwitterWebChromeClient(
                    controller = controller,
                    onLaunchFileChooser = { callback, mimeTypes, allowMultiple ->
                        pendingFileCallback?.onReceiveValue(null)
                        pendingFileCallback = callback
                        val launched = runCatching {
                            if (allowMultiple) multiFileLauncher.launch(mimeTypes)
                            else singleFileLauncher.launch(mimeTypes)
                        }
                        if (launched.isFailure) {
                            pendingFileCallback?.onReceiveValue(null)
                            pendingFileCallback = null
                        }
                    },
                    onCustomViewChange = { latestOnCustomView(it) }
                )
            }

            val swipeRefresh = TwitterSwipeRefreshLayout(ctx).apply {
                setColorSchemeColors(0xFF1DA1F2.toInt())
                setOnChildScrollUpCallback { _, _ -> webView.scrollY > 0 || webView.canScrollVertically(-1) }
                setOnRefreshListener {
                    latestOnPullRefresh()
                    webView.reload()
                }
                addView(webView)
            }

            controller.webView = webView
            controller.swipeRefresh = swipeRefresh
            controller.applyThemeColor(initialThemeColorInt)
            controller.updatePullToRefresh(pathBlocked = isPullRefreshBlockedPath(initialUrl))
            controller.lastKnownUrl = initialUrl
            webView.loadUrl(initialUrl)
            webViewAttached = true
            latestOnWebViewAttached()
            swipeRefresh
        }
    )
}
