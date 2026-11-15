package app.overbay.twitter.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.ui.settings.LinkActionSheet
import app.overbay.twitter.ui.settings.TwitterModSettingsSheet
import app.overbay.twitter.webview.TwitterWebController
import app.overbay.twitter.webview.TwitterWebView
import kotlinx.coroutines.launch

/**
 * Root Compose container hosting the hardened Twitter WebView, fullscreen video overlay,
 * link action bottom sheet, and the native Twitter Mod Settings sheet.
 */
@Composable
fun TwitterAppScreen(
    repository: TwitterConfigRepository,
    deepLinkUrl: String?,
    onDeepLinkConsumed: () -> Unit,
    onFirstLoadComplete: () -> Unit
) {
    val config by repository.config.collectAsState()
    val configJson = remember(config) { repository.toJsonString(config) }
    val controller = remember { TwitterWebController() }

    var showSettingsSheet by remember { mutableStateOf(false) }
    var longPressedLinkUrl by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var pendingReloadPrompt by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val startUrl = remember {
        deepLinkUrl?.also { onDeepLinkConsumed() } ?: "https://x.com/home"
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(650L)
        onFirstLoadComplete()
    }

    LaunchedEffect(deepLinkUrl) {
        if (!deepLinkUrl.isNullOrBlank() && controller.webView != null) {
            controller.loadUrl(deepLinkUrl)
            onDeepLinkConsumed()
        }
    }

    LaunchedEffect(showSettingsSheet, pendingReloadPrompt) {
        if (!showSettingsSheet && pendingReloadPrompt) {
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

    BackHandler(enabled = customVideoView != null || canGoBack || showSettingsSheet) {
        when {
            customVideoView != null -> controller.hideCustomView()
            showSettingsSheet -> showSettingsSheet = false
            canGoBack -> controller.goBack()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        containerColor = Color.Black,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .systemBarsPadding()
                    .imePadding()
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .systemBarsPadding()
                .imePadding()
        ) {
            TwitterWebView(
                initialUrl = startUrl,
                config = config,
                configJson = configJson,
                controller = controller,
                onOpenSettings = { showSettingsSheet = true },
                onShowLinkActions = { url -> longPressedLinkUrl = url },
                onCanGoBackChange = { canGoBack = it },
                onCustomViewChange = { view -> customVideoView = view },
                onFirstLoadComplete = onFirstLoadComplete,
                modifier = Modifier.fillMaxSize()
            )

            if (customVideoView != null) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            val target = customVideoView ?: return@apply
                            (target.parent as? ViewGroup)?.removeView(target)
                            addView(
                                target,
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
    }

    if (showSettingsSheet) {
        TwitterModSettingsSheet(
            config = config,
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
                repository.resetDefaults()
                pendingReloadPrompt = true
            },
            onReloadTimeline = { controller.reload() },
            onClearCacheAndReload = { controller.clearCacheAndReload() },
            onDismiss = { showSettingsSheet = false }
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
