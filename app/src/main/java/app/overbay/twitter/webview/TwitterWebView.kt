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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ScriptHandler
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
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
    preferDesktopUserAgent: Boolean,
    isSystemDark: Boolean,
    navInsetTopDp: Int = 0,
    navInsetTopPx: Int = 0,
    navInsetBottomDp: Int = 0,
    controller: TwitterWebController,
    onOpenSettings: () -> Unit,
    onShowLinkActions: (String) -> Unit,
    onCanGoBackChange: (Boolean) -> Unit,
    onCustomViewChange: (View?) -> Unit,
    onFirstLoadComplete: () -> Unit,
    modifier: Modifier = Modifier,
    onReportHomeFeeds: (String) -> Unit = {},
    onThemeColorChange: (Int) -> Unit = {},
    onSetThemeMode: (String) -> Unit = {},
    onSetAutoDarkVariant: (String) -> Unit = {},
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
    val isSystemDarkRef = remember { AtomicReference(isSystemDark) }
    val themeColorRef = remember { AtomicInteger(initialThemeColorInt) }
    val navInsetTopRef = remember { AtomicInteger(navInsetTopDp) }
    val navInsetBottomRef = remember { AtomicInteger(navInsetBottomDp) }

    val latestOnOpenSettings by rememberUpdatedState(onOpenSettings)
    val latestOnShowLinkActions by rememberUpdatedState(onShowLinkActions)
    val latestOnCanGoBack by rememberUpdatedState(onCanGoBackChange)
    val latestOnCustomView by rememberUpdatedState(onCustomViewChange)
    val latestOnFirstLoad by rememberUpdatedState(onFirstLoadComplete)
    val latestOnReportHomeFeeds by rememberUpdatedState(onReportHomeFeeds)
    val latestOnThemeColorChange by rememberUpdatedState(onThemeColorChange)
    val latestOnSetThemeMode by rememberUpdatedState(onSetThemeMode)
    val latestOnSetAutoDarkVariant by rememberUpdatedState(onSetAutoDarkVariant)
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
    val navInsetScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val coreScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val restoreScriptHandlerRef = remember { AtomicReference<ScriptHandler?>(null) }
    val fallbackRestoreJsonRef = remember { AtomicReference<String?>(null) }
    val ownedWebViewRef = remember { AtomicReference<WebView?>(null) }
    val ownedSwipeRefreshRef = remember { AtomicReference<SwipeRefreshLayout?>(null) }
    val lastSeededStateRef = remember { AtomicReference<Pair<String, Boolean>?>(null) }
    val lastNavInsetsRef = remember { AtomicReference(navInsetTopDp to navInsetBottomDp) }
    val lastTopInsetPxRef = remember { AtomicInteger(-1) }
    var webViewAttached by remember { mutableStateOf(false) }

    fun registerStartScript(webView: WebView, ref: AtomicReference<ScriptHandler?>, script: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        ref.getAndSet(null)?.remove()
        val handler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, script, ORIGIN_ALLOWLIST)
        }.getOrNull()
        ref.set(handler)
    }

    // Keep atomic refs fresh for background IO-thread callbacks without invoking JNI on every recompose.
    SideEffect {
        configRef.set(config)
        configJsonRef.set(configJson)
        isSystemDarkRef.set(isSystemDark)
        themeColorRef.set(initialThemeColorInt)
        navInsetTopRef.set(navInsetTopDp)
        navInsetBottomRef.set(navInsetBottomDp)
    }

    LaunchedEffect(webViewAttached) {
        if (webViewAttached) {
            latestOnWebViewAttached()
        }
    }

    LaunchedEffect(webViewAttached, config.disableVideoAutoplay) {
        val wv = ownedWebViewRef.get() ?: return@LaunchedEffect
        if (wv.settings.mediaPlaybackRequiresUserGesture != config.disableVideoAutoplay) {
            wv.settings.mediaPlaybackRequiresUserGesture = config.disableVideoAutoplay
        }
    }

    LaunchedEffect(webViewAttached, configJson, isSystemDark) {
        val wv = ownedWebViewRef.get() ?: return@LaunchedEffect
        val stateKey = configJson to isSystemDark
        if (lastSeededStateRef.getAndSet(stateKey) != stateKey) {
            val script = assetBundle.buildConfigSeedScript(
                configJson, isSystemDark, navInsetBottomDp = navInsetBottomRef.get(), navInsetTopDp = navInsetTopRef.get()
            )
            registerStartScript(wv, configScriptHandlerRef, script)
            wv.evaluateJavascript(script, null)
        }
    }

    LaunchedEffect(webViewAttached, navInsetTopDp, navInsetBottomDp) {
        val wv = ownedWebViewRef.get() ?: return@LaunchedEffect
        val insetKey = navInsetTopDp to navInsetBottomDp
        if (lastNavInsetsRef.getAndSet(insetKey) != insetKey) {
            val script = assetBundle.buildNavInsetScript(navInsetBottomDp, navInsetTopDp)
            registerStartScript(wv, navInsetScriptHandlerRef, script)
            wv.evaluateJavascript(script, null)
        }
    }

    LaunchedEffect(webViewAttached, navInsetTopPx) {
        val sr = ownedSwipeRefreshRef.get() ?: return@LaunchedEffect
        if (lastTopInsetPxRef.getAndSet(navInsetTopPx) != navInsetTopPx) {
            val d = context.resources.displayMetrics.density
            sr.setProgressViewOffset(true, navInsetTopPx + (8 * d).toInt(), navInsetTopPx + (72 * d).toInt())
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ownedWebViewRef.get()?.onResume()
                Lifecycle.Event.ON_PAUSE -> {
                    ownedWebViewRef.get()?.onPause()
                    CookieManager.getInstance().flush()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            CookieManager.getInstance().flush()
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = null
            val handlers = listOf(configScriptHandlerRef, navInsetScriptHandlerRef, coreScriptHandlerRef, restoreScriptHandlerRef)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                handlers.forEach { it.getAndSet(null)?.remove() }
            } else {
                handlers.forEach { it.set(null) }
            }
            fallbackRestoreJsonRef.set(null)
            val wv = ownedWebViewRef.getAndSet(null)
            val sr = ownedSwipeRefreshRef.getAndSet(null)
            if (wv != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching { WebViewCompat.removeWebMessageListener(wv, TwitterAssetBundle.NATIVE_PORT_NAME) }
            }
            wv?.destroy()
            controller.detachIfOwned(wv, sr)
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
                setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

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
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                    }
                    userAgentString = TwitterRequestBlocker.buildEffectiveUserAgent(
                        userAgentString,
                        preferDesktopUserAgent
                    )
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                        runCatching {
                            val currentMeta = WebSettingsCompat.getUserAgentMetadata(this)
                            val updatedMeta = UserAgentMetadata.Builder(currentMeta)
                                .setMobile(!preferDesktopUserAgent)
                                .build()
                            WebSettingsCompat.setUserAgentMetadata(this, updatedMeta)
                        }
                    }
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
                    onSetThemeMode = { rawMode -> latestOnSetThemeMode(rawMode) },
                    onSetAutoDarkVariant = { rawVariant -> latestOnSetAutoDarkVariant(rawVariant) },
                    onStatsDelta = { ads, clutter -> latestOnStatsDelta(ads, clutter) },
                    onRequestTweetScreenshot = { controller.onRequestTweetScreenshot?.invoke() }
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
                val pendingRestoreJson = controller.consumePendingRestoreStateJson()
                if (docStartSupported) {
                    val initialJson = configJsonRef.get()
                    val initialDark = isSystemDarkRef.get()
                    val initialTopDp = navInsetTopRef.get()
                    val initialNavDp = navInsetBottomRef.get()
                    lastSeededStateRef.set(initialJson to initialDark)
                    lastNavInsetsRef.set(initialTopDp to initialNavDp)
                    if (!pendingRestoreJson.isNullOrBlank()) {
                        registerStartScript(this, restoreScriptHandlerRef, "window.__TWITTER_RESTORE_STATE__ = $pendingRestoreJson;")
                    }
                    registerStartScript(this, configScriptHandlerRef, assetBundle.buildConfigSeedScript(initialJson, initialDark, navInsetBottomDp = initialNavDp, navInsetTopDp = initialTopDp))
                    registerStartScript(this, navInsetScriptHandlerRef, assetBundle.buildNavInsetScript(initialNavDp, initialTopDp))
                    registerStartScript(this, coreScriptHandlerRef, assetBundle.coreEngineScript)
                } else {
                    fallbackRestoreJsonRef.set(pendingRestoreJson)
                }

                webViewClient = TwitterWebViewClient(
                    context = ctx,
                    assetBundle = assetBundle,
                    controller = controller,
                    docStartSupported = docStartSupported,
                    configRef = configRef,
                    configJsonRef = configJsonRef,
                    isSystemDarkRef = isSystemDarkRef,
                    themeColorRef = themeColorRef,
                    navInsetTopRef = navInsetTopRef,
                    navInsetBottomRef = navInsetBottomRef,
                    consumeFallbackRestoreJson = { fallbackRestoreJsonRef.getAndSet(null) },
                    onClearOneShotRestoreScript = {
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            restoreScriptHandlerRef.getAndSet(null)?.remove()
                        } else {
                            restoreScriptHandlerRef.set(null)
                        }
                    },
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

            ownedWebViewRef.set(webView)
            ownedSwipeRefreshRef.set(swipeRefresh)
            controller.attach(webView, swipeRefresh)
            controller.applyThemeColor(initialThemeColorInt)
            controller.updatePullToRefresh(pathBlocked = isPullRefreshBlockedPath(initialUrl))
            controller.lastKnownUrl = initialUrl
            controller.syncStartupNightModeCookie(configRef.get().themeMode, isSystemDarkRef.get()) {
                if (ownedWebViewRef.get() === webView) {
                    webView.loadUrl(initialUrl)
                }
            }
            webViewAttached = true
            swipeRefresh
        }
    )
}
