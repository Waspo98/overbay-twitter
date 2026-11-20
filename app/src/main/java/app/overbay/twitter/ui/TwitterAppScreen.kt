package app.overbay.twitter.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.MultiWindowModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overbay.twitter.model.SessionFilterStats
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.ui.settings.LinkActionSheet
import app.overbay.twitter.ui.settings.OfflineErrorOverlay
import app.overbay.twitter.ui.settings.TwitterModSettingsSheet
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.ui.theme.TwitterTheme
import app.overbay.twitter.webview.TwitterWebController
import app.overbay.twitter.webview.TwitterWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/**
 * Root Compose container hosting the hardened Twitter WebView, 3-mode chameleon system bars,
 * renderer crash recovery, offline overlay, immersive fullscreen video, and Mod Settings sheet.
 */
@Composable
fun TwitterAppScreen(
    repository: TwitterConfigRepository,
    deepLinkUrl: String?,
    onDeepLinkConsumed: () -> Unit,
    onFirstLoadComplete: () -> Unit
) {
    val config by repository.config.collectAsStateWithLifecycle()
    val detectedHomeFeeds by repository.detectedHomeFeeds.collectAsStateWithLifecycle()
    val configJson = remember(config) { repository.toJsonString(config) }
    val controller = remember { TwitterWebController() }
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findComponentActivity() }
    val systemDark = isSystemInDarkTheme()

    var isInMultiWindowMode by remember(activity) {
        mutableStateOf(activity?.isInMultiWindowMode ?: false)
    }

    DisposableEffect(activity) {
        isInMultiWindowMode = activity?.isInMultiWindowMode ?: false
        val listener = Consumer<MultiWindowModeChangedInfo> { info ->
            isInMultiWindowMode = info.isInMultiWindowMode
        }
        activity?.addOnMultiWindowModeChangedListener(listener)
        onDispose { activity?.removeOnMultiWindowModeChangedListener(listener) }
    }

    val isFullyUnfolded = !isInMultiWindowMode &&
        configuration.screenWidthDp >= 600 &&
        configuration.smallestScreenWidthDp >= 600

    var webThemeColorInt by remember {
        mutableIntStateOf(
            repository.lastThemeColor.value ?: if (systemDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        )
    }
    val surfaceTheme = remember(webThemeColorInt) { TwitterSurfaceTheme.fromColorInt(webThemeColorInt) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var appliedGraphQlConfig by remember { mutableStateOf(config) }
    var sessionStats by remember { mutableStateOf(SessionFilterStats()) }
    var mainFrameError by remember { mutableStateOf<String?>(null) }
    var webViewEpoch by remember { mutableIntStateOf(0) }

    val pendingReload = remember(config, appliedGraphQlConfig) {
        config.requiresReloadComparedTo(appliedGraphQlConfig)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val initialUrl = remember(webViewEpoch) {
        if (webViewEpoch == 0) deepLinkUrl ?: TwitterWebController.DEFAULT_HOME_URL
        else controller.lastKnownUrl
    }
    var skipInitialDeepLink by remember { mutableStateOf(deepLinkUrl != null) }
    var isWebViewAttached by remember { mutableStateOf(false) }

    // Keep system bar icon contrast & immersive fullscreen video state in lockstep with WebView.
    DisposableEffect(activity, view, surfaceTheme, customVideoView != null) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            val lightBars = surfaceTheme.isLight && customVideoView == null
            insetsController.isAppearanceLightStatusBars = lightBars
            insetsController.isAppearanceLightNavigationBars = lightBars
            if (customVideoView != null) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    DisposableEffect(controller) {
        controller.onForceClearCustomView = { customVideoView = null }
        onDispose { controller.onForceClearCustomView = null }
    }

    // Safety cap so splash never hangs on slow/offline networks before onPageCommitVisible fires.
    LaunchedEffect(Unit) {
        delay(800L)
        onFirstLoadComplete()
    }

    LaunchedEffect(deepLinkUrl, isWebViewAttached) {
        if (!isWebViewAttached || deepLinkUrl.isNullOrBlank()) return@LaunchedEffect
        if (skipInitialDeepLink) {
            skipInitialDeepLink = false
        } else {
            controller.loadUrl(deepLinkUrl)
        }
        onDeepLinkConsumed()
    }

    fun triggerReload(clearCache: Boolean = false) {
        appliedGraphQlConfig = repository.config.value
        mainFrameError = null
        if (clearCache) controller.clearCacheAndReload() else controller.reload()
    }

    fun dismissSettingsSheet() {
        showSettingsSheet = false
        if (repository.config.value.requiresReloadComparedTo(appliedGraphQlConfig)) {
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                val res = snackbarHostState.showSnackbar(
                    message = "Timeline filter updated",
                    actionLabel = "Reload",
                    duration = SnackbarDuration.Short
                )
                if (res == SnackbarResult.ActionPerformed) {
                    triggerReload()
                }
            }
        }
    }

    BackHandler(enabled = customVideoView != null || canGoBack || showSettingsSheet) {
        when {
            customVideoView != null -> controller.hideCustomView()
            showSettingsSheet -> dismissSettingsSheet()
            canGoBack -> controller.goBack()
        }
    }

    TwitterTheme(surfaceTheme = surfaceTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(webThemeColorInt))
        ) {
            key(webViewEpoch) {
                TwitterWebView(
                    initialUrl = initialUrl,
                    initialThemeColorInt = webThemeColorInt,
                    config = config,
                    configJson = configJson,
                    isFullyUnfolded = isFullyUnfolded,
                    controller = controller,
                    onOpenSettings = { showSettingsSheet = true },
                    onShowLinkActions = { url -> longPressedLinkUrl = url },
                    onReportHomeFeeds = repository::updateDetectedHomeFeedsFromJson,
                    onThemeColorChange = { colorInt ->
                        webThemeColorInt = colorInt
                        repository.updateLastThemeColor(colorInt)
                    },
                    onStatsDelta = { adsDelta, clutterDelta ->
                        sessionStats = SessionFilterStats(
                            adsBlocked = sessionStats.adsBlocked + adsDelta,
                            clutterBlocked = sessionStats.clutterBlocked + clutterDelta
                        )
                    },
                    onCanGoBackChange = { canGoBack = it },
                    onCustomViewChange = { v -> customVideoView = v },
                    onFirstLoadComplete = onFirstLoadComplete,
                    onMainFrameError = { err -> mainFrameError = err },
                    onRendererCrash = {
                        isWebViewAttached = false
                        customVideoView = null
                        canGoBack = false
                        mainFrameError = null
                        controller.customViewCallback = null
                        controller.updatePullToRefresh(modalOpen = false)
                        appliedGraphQlConfig = repository.config.value
                        webViewEpoch++
                    },
                    onPullRefresh = { appliedGraphQlConfig = repository.config.value },
                    onWebViewAttached = { isWebViewAttached = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                )
            }

            mainFrameError?.let { errorMessage ->
                OfflineErrorOverlay(
                    message = errorMessage,
                    onRetry = { triggerReload() },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                )
            }

            customVideoView?.let { activeVideoView ->
                key(activeVideoView) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                (activeVideoView.parent as? ViewGroup)?.removeView(activeVideoView)
                                addView(
                                    activeVideoView,
                                    FrameLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )
                                )
                            }
                        }
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .imePadding()
                    .padding(bottom = if (isFullyUnfolded) 16.dp else 58.dp)
            )
        }

        if (showSettingsSheet) {
            TwitterModSettingsSheet(
                config = config,
                detectedHomeFeeds = detectedHomeFeeds,
                sessionStats = sessionStats,
                pendingReload = pendingReload,
                onSelectDefaultFeed = repository::setDefaultHomeFeed,
                onToggleFeedVisibility = repository::toggleHomeFeedVisibility,
                onMoveFeed = repository::moveHomeFeed,
                onUpdate = repository::updateConfig,
                onAddMutePattern = { pattern -> repository.addMutePattern(pattern) },
                onRemoveMutePattern = repository::removeMutePattern,
                onResetDefaults = repository::resetDefaults,
                onReloadTimeline = { triggerReload(clearCache = false) },
                onClearCacheAndReload = { triggerReload(clearCache = true) },
                onDismiss = ::dismissSettingsSheet
            )
        }

        longPressedLinkUrl?.let { url ->
            LinkActionSheet(
                url = url,
                useCustomTab = config.openLinksInCustomTab,
                toolbarColor = webThemeColorInt,
                onDismiss = { longPressedLinkUrl = null }
            )
        }
    }
}
