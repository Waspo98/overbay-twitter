package app.overbay.twitter.ui

import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import kotlin.math.ceil

internal tailrec fun Context.findComponentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

@Composable
internal fun rememberActivityResumed(): Boolean {
    val lifecycleOwner = LocalLifecycleOwner.current
    var isResumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycleOwner) {
        isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, _ ->
            isResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return isResumed
}

@Composable
internal fun SyncSystemBarsEffect(
    activity: ComponentActivity?,
    view: View,
    surfaceTheme: TwitterSurfaceTheme,
    isFullscreenVideo: Boolean,
    systemBarSyncToken: Int = 0,
    onLightSystemBarsChange: (Boolean) -> Unit = {}
) {
    val latestOnLightSystemBarsChange by rememberUpdatedState(onLightSystemBarsChange)
    val lightBars = surfaceTheme.isLight && !isFullscreenVideo
    SideEffect {
        latestOnLightSystemBarsChange(lightBars)
    }
    DisposableEffect(activity, view, lightBars, isFullscreenVideo, systemBarSyncToken) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            val insetsController = WindowCompat.getInsetsController(window, view)
            insetsController.isAppearanceLightStatusBars = lightBars
            insetsController.isAppearanceLightNavigationBars = lightBars
            if (isFullscreenVideo) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }
}

internal data class WebViewEdgeToEdgeState(
    val webViewInsets: WindowInsets,
    val topInsetDp: Int,
    val bottomInsetDp: Int,
    val topInsetPx: Int
)

internal fun computeUncoveredTopInsetDp(
    statusTopPx: Int,
    cutoutTopPx: Int,
    density: Float,
    isFullscreenVideo: Boolean
): Int {
    if (isFullscreenVideo || density <= 0f) return 0
    val uncoveredPx = maxOf(statusTopPx, cutoutTopPx).coerceAtLeast(0)
    return if (uncoveredPx == 0) 0 else ceil(uncoveredPx / density).toInt()
}

internal fun computeUncoveredNavBottomDp(
    navBottomPx: Int,
    imeBottomPx: Int,
    cutoutBottomPx: Int,
    density: Float,
    isFullscreenVideo: Boolean
): Int {
    if (isFullscreenVideo || density <= 0f) return 0
    val coveredPx = maxOf(imeBottomPx, cutoutBottomPx).coerceAtLeast(0)
    val uncoveredPx = (navBottomPx - coveredPx).coerceAtLeast(0)
    return if (uncoveredPx == 0) 0 else ceil(uncoveredPx / density).toInt()
}

@Composable
internal fun rememberWebViewEdgeToEdgeState(isFullscreenVideo: Boolean): WebViewEdgeToEdgeState {
    val density = LocalDensity.current
    val safeHorizontal = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val statusInsets = WindowInsets.statusBars
    val navInsets = WindowInsets.navigationBars
    val imeInsets = WindowInsets.ime
    val cutoutInsets = WindowInsets.displayCutout
    val bottomCutout = cutoutInsets.only(WindowInsetsSides.Bottom)
    val webViewInsets = remember(safeHorizontal, imeInsets, bottomCutout) {
        safeHorizontal.union(imeInsets).union(bottomCutout)
    }
    val latestFullscreen by rememberUpdatedState(isFullscreenVideo)
    val topInsetPx by remember(density, statusInsets, cutoutInsets) {
        derivedStateOf {
            if (latestFullscreen) 0
            else maxOf(statusInsets.getTop(density), cutoutInsets.getTop(density)).coerceAtLeast(0)
        }
    }
    val topInsetDp by remember(density, statusInsets, cutoutInsets) {
        derivedStateOf {
            computeUncoveredTopInsetDp(
                statusTopPx = statusInsets.getTop(density),
                cutoutTopPx = cutoutInsets.getTop(density),
                density = density.density,
                isFullscreenVideo = latestFullscreen
            )
        }
    }
    val bottomInsetDp by remember(density, navInsets, imeInsets, bottomCutout) {
        derivedStateOf {
            computeUncoveredNavBottomDp(
                navBottomPx = navInsets.getBottom(density),
                imeBottomPx = imeInsets.getBottom(density),
                cutoutBottomPx = bottomCutout.getBottom(density),
                density = density.density,
                isFullscreenVideo = latestFullscreen
            )
        }
    }
    return WebViewEdgeToEdgeState(
        webViewInsets = webViewInsets,
        topInsetDp = topInsetDp,
        bottomInsetDp = bottomInsetDp,
        topInsetPx = topInsetPx
    )
}

@Composable
internal fun FullscreenVideoOverlay(activeVideoView: View) {
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
