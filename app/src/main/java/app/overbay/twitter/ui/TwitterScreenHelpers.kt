package app.overbay.twitter.ui

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme

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
    isFullscreenVideo: Boolean
) {
    DisposableEffect(activity, view, surfaceTheme, isFullscreenVideo) {
        val window = activity?.window
        if (window != null) {
            val insetsController = WindowCompat.getInsetsController(window, view)
            val lightBars = surfaceTheme.isLight && !isFullscreenVideo
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
