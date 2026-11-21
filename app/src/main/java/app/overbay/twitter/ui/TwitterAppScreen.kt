package app.overbay.twitter.ui

import android.view.View
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.overbay.twitter.model.SessionFilterStats
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterThemeMode
import app.overbay.twitter.ui.settings.LinkActionSheet
import app.overbay.twitter.ui.settings.OfflineErrorOverlay
import app.overbay.twitter.ui.settings.TwitterModSettingsSheet
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.ui.theme.TwitterTheme
import app.overbay.twitter.ui.theme.resolveInitialColorInt
import app.overbay.twitter.webview.FoldableLayoutPolicy
import app.overbay.twitter.webview.TwitterWebController
import app.overbay.twitter.webview.TwitterWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Root Compose container hosting the hardened Twitter WebView, 3-mode chameleon system bars,
 * renderer crash recovery, offline overlay, immersive fullscreen video, and Mod Settings sheet.
 */
@Composable
fun TwitterAppScreen(
    repository: TwitterConfigRepository,
    deepLinkUrl: String?,
    onDeepLinkConsumed: () -> Unit,
    onFirstLoadComplete: () -> Unit,
    systemBarSyncToken: Int = 0,
    onLightSystemBarsChange: (Boolean) -> Unit = {}
) {
    val config by repository.config.collectAsStateWithLifecycle()
    val configJson = remember(config) { repository.toJsonString(config) }
    val controller = remember { TwitterWebController() }
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findComponentActivity() }
    val systemDark = isSystemInDarkTheme()
    val isActivityResumed = rememberActivityResumed()
    val latestScreenWidthDp by rememberUpdatedState(configuration.screenWidthDp)

    var committedLayoutState by remember {
        mutableStateOf(
            FoldableLayoutPolicy.resolve(
                screenWidthDp = configuration.screenWidthDp,
                smallestScreenWidthDp = configuration.smallestScreenWidthDp,
                densityDpi = configuration.densityDpi
            )
        )
    }
    val currentLayoutState = remember(
        configuration.screenWidthDp,
        configuration.smallestScreenWidthDp,
        configuration.densityDpi,
        committedLayoutState
    ) {
        FoldableLayoutPolicy.resolve(
            screenWidthDp = configuration.screenWidthDp,
            smallestScreenWidthDp = configuration.smallestScreenWidthDp,
            densityDpi = configuration.densityDpi,
            fallback = committedLayoutState
        )
    }

    var webThemeColorInt by remember {
        mutableIntStateOf(
            config.themeMode.resolveInitialColorInt(
                lastColorInt = repository.lastThemeColor.value,
                isSystemDark = systemDark,
                autoDarkVariant = config.autoDarkVariant
            )
        )
    }
    val surfaceTheme = remember(webThemeColorInt) { TwitterSurfaceTheme.fromColorInt(webThemeColorInt) }
    var lastObservedSystemDark by remember { mutableStateOf(systemDark) }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var appliedGraphQlConfig by remember { mutableStateOf(config) }
    val sessionStatsFlow = remember { MutableStateFlow(SessionFilterStats()) }
    var mainFrameError by remember { mutableStateOf<String?>(null) }
    var webViewEpoch by remember { mutableIntStateOf(0) }
    var preferDesktopUserAgent by remember { mutableStateOf(committedLayoutState.isExpanded) }
    var reflowTouchedWebView by remember { mutableStateOf(false) }

    val pendingReload = remember(config, appliedGraphQlConfig) {
        config.requiresReloadComparedTo(appliedGraphQlConfig)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val (webViewInsets, navInsetBottomDp) = rememberWebViewEdgeToEdgeState(isFullscreenVideo = customVideoView != null)
    val initialUrl = remember(webViewEpoch) {
        if (webViewEpoch == 0) deepLinkUrl ?: TwitterWebController.DEFAULT_HOME_URL
        else controller.lastKnownUrl
    }
    var skipInitialDeepLink by remember { mutableStateOf(deepLinkUrl != null) }
    var isWebViewAttached by remember { mutableStateOf(false) }

    SyncSystemBarsEffect(
        activity = activity,
        view = view,
        surfaceTheme = surfaceTheme,
        isFullscreenVideo = customVideoView != null,
        systemBarSyncToken = systemBarSyncToken,
        onLightSystemBarsChange = onLightSystemBarsChange
    )

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

    LaunchedEffect(systemDark, isWebViewAttached) {
        if (!isWebViewAttached || systemDark == lastObservedSystemDark) return@LaunchedEffect
        lastObservedSystemDark = systemDark
        val currentConfig = repository.config.value
        if (currentConfig.themeMode == TwitterThemeMode.AUTO) {
            controller.applyThemeMode(
                mode = TwitterThemeMode.AUTO,
                autoDarkVariant = currentConfig.autoDarkVariant,
                isSystemDark = systemDark,
                currentSurfaceProvider = { TwitterSurfaceTheme.fromColorInt(webThemeColorInt) },
                allowReloadFallback = false
            )
        }
    }

    fun recreateWebView(clearCustomView: Boolean) {
        controller.captureCurrentUrlIfInternal()
        if (clearCustomView) {
            controller.hideCustomView()
        } else {
            controller.customViewCallback = null
        }
        committedLayoutState = currentLayoutState
        preferDesktopUserAgent = currentLayoutState.isExpanded
        reflowTouchedWebView = false
        isWebViewAttached = false
        customVideoView = null
        canGoBack = false
        mainFrameError = null
        controller.updatePullToRefresh(modalOpen = false)
        appliedGraphQlConfig = repository.config.value
        webViewEpoch++
    }

    LaunchedEffect(currentLayoutState, isActivityResumed, isWebViewAttached) {
        if (!isActivityResumed || !isWebViewAttached) return@LaunchedEffect
        if (!FoldableLayoutPolicy.requiresLayoutTransition(committedLayoutState, currentLayoutState) &&
            !reflowTouchedWebView
        ) {
            return@LaunchedEffect
        }
        delay(FoldableLayoutPolicy.TRANSITION_SETTLE_DELAY_MS)
        if (!FoldableLayoutPolicy.requiresLayoutTransition(committedLayoutState, currentLayoutState) &&
            !reflowTouchedWebView
        ) {
            return@LaunchedEffect
        }
        reflowTouchedWebView = true
        val stayedLive = controller.attemptLiveLayoutTransition(
            expectedWidthDp = latestScreenWidthDp,
            expectedDensityDpi = currentLayoutState.densityDpi,
            fallbackExpanded = currentLayoutState.isExpanded
        )
        reflowTouchedWebView = false
        committedLayoutState = currentLayoutState
        preferDesktopUserAgent = currentLayoutState.isExpanded
        if (!stayedLive) {
            recreateWebView(clearCustomView = true)
        }
    }

    fun applyThemeSelection(
        nextMode: TwitterThemeMode = repository.config.value.themeMode,
        nextAutoVariant: TwitterDarkVariant = repository.config.value.autoDarkVariant
    ) {
        repository.updateConfig { it.copy(themeMode = nextMode, autoDarkVariant = nextAutoVariant) }
        mainFrameError = null
        controller.applyThemeMode(
            mode = nextMode,
            autoDarkVariant = nextAutoVariant,
            isSystemDark = systemDark,
            currentSurfaceProvider = { TwitterSurfaceTheme.fromColorInt(webThemeColorInt) },
            allowReloadFallback = true,
            onPreviewColor = { previewColor -> webThemeColorInt = previewColor }
        )
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
                    preferDesktopUserAgent = preferDesktopUserAgent,
                    isSystemDark = systemDark,
                    navInsetBottomDp = navInsetBottomDp,
                    controller = controller,
                    onOpenSettings = { showSettingsSheet = true },
                    onShowLinkActions = { url -> longPressedLinkUrl = url },
                    onReportHomeFeeds = repository::updateDetectedHomeFeedsFromJson,
                    onThemeColorChange = { colorInt ->
                        webThemeColorInt = colorInt
                        repository.updateLastThemeColor(colorInt)
                        controller.onObservedSurfaceTheme(TwitterSurfaceTheme.fromColorInt(colorInt))
                    },
                    onSetThemeMode = { rawMode ->
                        applyThemeSelection(nextMode = TwitterThemeMode.fromWireValue(rawMode))
                    },
                    onSetAutoDarkVariant = { rawVariant ->
                        applyThemeSelection(nextAutoVariant = TwitterDarkVariant.fromWireValue(rawVariant))
                    },
                    onStatsDelta = { adsDelta, clutterDelta ->
                        sessionStatsFlow.update {
                            SessionFilterStats(
                                adsBlocked = it.adsBlocked + adsDelta,
                                clutterBlocked = it.clutterBlocked + clutterDelta
                            )
                        }
                    },
                    onCanGoBackChange = { canGoBack = it },
                    onCustomViewChange = { v -> customVideoView = v },
                    onFirstLoadComplete = onFirstLoadComplete,
                    onMainFrameError = { err -> mainFrameError = err },
                    onRendererCrash = { recreateWebView(clearCustomView = false) },
                    onPullRefresh = { appliedGraphQlConfig = repository.config.value },
                    onWebViewAttached = { isWebViewAttached = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(webViewInsets)
                )
            }

            mainFrameError?.let { errorMessage ->
                OfflineErrorOverlay(
                    message = errorMessage,
                    onRetry = { triggerReload() },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(webViewInsets)
                )
            }

            customVideoView?.let { activeVideoView ->
                FullscreenVideoOverlay(activeVideoView = activeVideoView)
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding()
                    .imePadding()
                    .padding(
                        bottom = if (FoldableLayoutPolicy.expectsWideViewport(
                                configuration.screenWidthDp,
                                currentLayoutState.isExpanded
                            )
                        ) 16.dp else 58.dp
                    )
            )
        }

        if (showSettingsSheet) {
            TwitterModSettingsSheet(
                config = config,
                detectedHomeFeedsFlow = repository.detectedHomeFeeds,
                sessionStatsFlow = sessionStatsFlow,
                pendingReload = pendingReload,
                onSelectDefaultFeed = repository::setDefaultHomeFeed,
                onSelectThemeMode = { mode -> applyThemeSelection(nextMode = mode) },
                onSelectAutoDarkVariant = { variant -> applyThemeSelection(nextAutoVariant = variant) },
                onToggleFeedVisibility = repository::toggleHomeFeedVisibility,
                onMoveFeed = repository::moveHomeFeed,
                onUpdate = repository::updateConfig,
                onAddMutePattern = { pattern -> repository.addMutePattern(pattern) },
                onRemoveMutePattern = repository::removeMutePattern,
                onResetDefaults = {
                    val prev = repository.config.value
                    repository.resetDefaults()
                    if (prev.themeMode != TwitterThemeMode.AUTO || prev.autoDarkVariant != TwitterDarkVariant.LIGHTS_OUT) {
                        applyThemeSelection(
                            nextMode = TwitterThemeMode.AUTO,
                            nextAutoVariant = TwitterDarkVariant.LIGHTS_OUT
                        )
                    }
                },
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
