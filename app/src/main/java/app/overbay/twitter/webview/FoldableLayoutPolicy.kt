package app.overbay.twitter.webview

/**
 * Display layout posture & density snapshot used to detect foldable/split-screen/DPI changes
 * that require a live viewport reflow (with WebView recreation as fallback).
 */
data class DisplayLayoutState(
    val isExpanded: Boolean,
    val densityDpi: Int
)

/**
 * Pure-Kotlin classifier matching X.com's 500px responsive breakpoint so zoomed unfolded inner
 * displays (550–599dp) and narrow split-screen panes (<500dp) classify accurately while simple
 * portrait/landscape rotations never trigger unnecessary layout transitions.
 */
object FoldableLayoutPolicy {
    const val EXPANDED_WIDTH_BREAKPOINT_DP = 500
    const val TRANSITION_SETTLE_DELAY_MS = 180L
    const val LIVE_REFLOW_VERIFY_DELAY_MS = 380L

    fun resolve(
        screenWidthDp: Int,
        smallestScreenWidthDp: Int,
        densityDpi: Int,
        fallback: DisplayLayoutState? = null
    ): DisplayLayoutState {
        if (screenWidthDp <= 0 || smallestScreenWidthDp <= 0) {
            return fallback ?: DisplayLayoutState(
                isExpanded = false,
                densityDpi = densityDpi.coerceAtLeast(0)
            )
        }
        val isExpanded = screenWidthDp >= EXPANDED_WIDTH_BREAKPOINT_DP &&
            smallestScreenWidthDp >= EXPANDED_WIDTH_BREAKPOINT_DP
        return DisplayLayoutState(
            isExpanded = isExpanded,
            densityDpi = densityDpi
        )
    }

    fun requiresLayoutTransition(
        committed: DisplayLayoutState,
        current: DisplayLayoutState
    ): Boolean = committed != current

    fun expectsWideViewport(
        screenWidthDp: Int,
        fallbackExpanded: Boolean = false
    ): Boolean = if (screenWidthDp > 0) screenWidthDp >= EXPANDED_WIDTH_BREAKPOINT_DP else fallbackExpanded

    fun expectedDevicePixelRatio(densityDpi: Int): Float =
        if (densityDpi > 0) densityDpi / 160f else 0f
}
