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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.graphics.ColorUtils
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.ui.settings.LinkActionSheet
import app.overbay.twitter.ui.settings.TwitterModSettingsSheet
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
 * Root Compose container hosting the hardened Twitter WebView, chameleon system bars,
 * immersive fullscreen video overlay, link action sheet, and Material 3 Mod Settings sheet.
 */
@Composable
fun TwitterAppScreen(
    repository: TwitterConfigRepository,
    deepLinkUrl: String?,
    onDeepLinkConsumed: () -> Unit,
    onFirstLoadComplete: () -> Unit
) {
    val config by repository.config.collectAsState()
    val detectedHomeFeeds by repository.detectedHomeFeeds.collectAsState()
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
        mutableIntStateOf(if (systemDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    }
    val isWebLightTheme = ColorUtils.calculateLuminance(webThemeColorInt) > 0.5

    var showSettingsSheet by remember { mutableStateOf(false) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var pendingReloadPrompt by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startUrl = remember { deepLinkUrl ?: "https://x.com/home" }
    var skipInitialDeepLink by remember { mutableStateOf(deepLinkUrl != null) }
    var isWebViewAttached by remember { mutableStateOf(false) }

    // Keep system bar icon contrast & immersive fullscreen video state in lockstep with WebView.
    SideEffect {
        val window = activity?.window ?: return@SideEffect
        val insetsController = WindowCompat.getInsetsController(window, view)
        val lightBars = isWebLightTheme && customVideoView == null
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

    BackHandler(enabled = customVideoView != null || canGoBack || showSettingsSheet) {
        when {
            customVideoView != null -> controller.hideCustomView()
            showSettingsSheet -> showSettingsSheet = false
            canGoBack -> controller.goBack()
        }
    }

    TwitterTheme(darkTheme = !isWebLightTheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(webThemeColorInt))
        ) {
            TwitterWebView(
                initialUrl = startUrl,
                config = config,
                configJson = configJson,
                isFullyUnfolded = isFullyUnfolded,
                controller = controller,
                onOpenSettings = { showSettingsSheet = true },
                onShowLinkActions = { url -> longPressedLinkUrl = url },
                onReportHomeFeeds = repository::updateDetectedHomeFeedsFromJson,
                onThemeColorChange = { colorInt -> webThemeColorInt = colorInt },
                onCanGoBackChange = { canGoBack = it },
                onCustomViewChange = { v -> customVideoView = v },
                onFirstLoadComplete = onFirstLoadComplete,
                onWebViewAttached = { isWebViewAttached = true },
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .imePadding()
            )

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
                    .padding(bottom = 58.dp)
            )
        }

        if (showSettingsSheet) {
            TwitterModSettingsSheet(
                config = config,
                detectedHomeFeeds = detectedHomeFeeds,
                pendingReload = pendingReloadPrompt,
                onSelectDefaultFeed = repository::setDefaultHomeFeed,
                onToggleFeedVisibility = repository::toggleHomeFeedVisibility,
                onMoveFeed = repository::moveHomeFeed,
                onUpdate = { transform ->
                    val previous = repository.config.value
                    repository.updateConfig(transform)
                    val updated = repository.config.value
                    if (updated.requiresReloadComparedTo(previous)) {
                        pendingReloadPrompt = true
                    }
                },
                onAddMutePattern = { pattern ->
                    if (repository.addMutePattern(pattern)) {
                        pendingReloadPrompt = true
                    }
                },
                onRemoveMutePattern = { pattern ->
                    repository.removeMutePattern(pattern)
                    pendingReloadPrompt = true
                },
                onResetDefaults = {
                    val previous = repository.config.value
                    repository.resetDefaults()
                    if (repository.config.value.requiresReloadComparedTo(previous)) {
                        pendingReloadPrompt = true
                    }
                },
                onReloadTimeline = {
                    pendingReloadPrompt = false
                    controller.reload()
                },
                onClearCacheAndReload = {
                    pendingReloadPrompt = false
                    controller.clearCacheAndReload()
                },
                onDismiss = {
                    showSettingsSheet = false
                    if (pendingReloadPrompt) {
                        pendingReloadPrompt = false
                        scope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            val res = snackbarHostState.showSnackbar(
                                message = "Timeline filter updated",
                                actionLabel = "Reload",
                                duration = SnackbarDuration.Short
                            )
                            if (res == SnackbarResult.ActionPerformed) {
                                controller.reload()
                            }
                        }
                    }
                }
            )
        }

        longPressedLinkUrl?.let { url ->
            LinkActionSheet(
                url = url,
                useCustomTab = config.openLinksInCustomTab,
                onDismiss = { longPressedLinkUrl = null }
            )
        }
    }
}
