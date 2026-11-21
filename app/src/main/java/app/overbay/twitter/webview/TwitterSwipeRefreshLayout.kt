package app.overbay.twitter.webview

import android.content.Context
import android.content.res.Configuration
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlin.math.abs

/**
 * [SwipeRefreshLayout] subclass that yields horizontal drags (e.g. feed tabs, media carousels)
 * to the child WebView instead of hijacking diagonal swipes into pull-to-refresh.
 */
internal class TwitterSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {

    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var declined = false

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                declined = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (declined) return false
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                if (dx > touchSlop && dx > dy) {
                    declined = true
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> declined = false
        }
        return super.onInterceptTouchEvent(ev)
    }
}
