package app.overbay.twitter.screenshot

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.PixelCopy
import android.view.Window
import android.webkit.WebView
import app.overbay.twitter.bridge.TwitterNativeBridge
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Raw captured tweet pixels paired with clean-crop boundary and theme metadata.
 * Keeping both full and clean heights lets the preview sheet toggle metrics in memory
 * with zero re-capture.
 */
data class CapturedTweetScreenshot(
    val fullBitmap: Bitmap,
    val cleanHeightPx: Int,
    val surfaceTheme: TwitterSurfaceTheme,
    val surfaceColorInt: Int,
    val tweetId: String,
    val authorHandle: String
)

internal data class TweetCaptureMetrics(
    val rectLeft: Double,
    val rectTop: Double,
    val rectWidth: Double,
    val rectHeight: Double,
    val docTop: Double,
    val scrollY: Double,
    val scrollHeight: Double,
    val viewportWidth: Double,
    val viewportHeight: Double,
    val cleanHeightCss: Double,
    val fullHeightCss: Double,
    val surfaceColorHex: String,
    val tweetId: String,
    val authorHandle: String
)

internal data class SliceCaptureBounds(
    val leftInView: Int,
    val topInView: Int,
    val widthPx: Int,
    val heightPx: Int
)

/**
 * Orchestrates pixel-perfect, chrome-free tweet capture from the live hardware-accelerated
 * WebView using Android's [PixelCopy] API, automatically scrolling and stitching tweets
 * taller than the viewport and restoring the user's exact scroll position afterward.
 */
object TweetScreenshotCapturer {

    internal const val MAX_CAPTURE_HEIGHT_PX = 9000
    private const val MAX_SLICES = 16
    private const val JS_TIMEOUT_MS = 1200L
    private const val VISUAL_STATE_TIMEOUT_MS = 220L
    private const val PIXEL_COPY_TIMEOUT_MS = 800L
    private const val FRAME_SETTLE_DELAY_MS = 32L

    private val visualStateSeq = AtomicLong(1L)
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    suspend fun capture(
        activity: Activity,
        webView: WebView,
        fallbackSurfaceColorInt: Int
    ): Result<CapturedTweetScreenshot> {
        if (webView.width <= 0 || webView.height <= 0) {
            return Result.failure(IllegalStateException("WebView is not laid out yet"))
        }
        val window = activity.window
            ?: return Result.failure(IllegalStateException("Activity window unavailable"))

        return try {
            val initialRaw = evaluateJs(webView, "window.__TW_SCREENSHOT__ ? window.__TW_SCREENSHOT__.begin() : null")
            var current = parseMetrics(initialRaw)
                ?: return Result.failure(IllegalStateException("Could not measure tweet"))

            val scale = computePixelScale(webView.width, current.viewportWidth)
            if (scale <= 0.0) {
                return Result.failure(IllegalStateException("Invalid viewport scale"))
            }

            val surfaceColorInt = TwitterNativeBridge.parseHexColor(current.surfaceColorHex)
                ?: fallbackSurfaceColorInt
            val surfaceTheme = TwitterSurfaceTheme.fromColorInt(surfaceColorInt)

            val targetWidthPx = (current.rectWidth * scale).roundToInt().coerceIn(1, webView.width)
            val targetFullHeightPx = (current.fullHeightCss * scale).roundToInt()
                .coerceIn(1, MAX_CAPTURE_HEIGHT_PX)
            val targetCleanHeightPx = (current.cleanHeightCss * scale).roundToInt()
                .coerceIn(1, targetFullHeightPx)

            val stitched = Bitmap.createBitmap(targetWidthPx, targetFullHeightPx, Bitmap.Config.ARGB_8888)
            val stitchCanvas = Canvas(stitched)
            stitchCanvas.drawColor(surfaceColorInt)

            val viewLoc = IntArray(2)
            webView.getLocationInWindow(viewLoc)
            val decor = window.decorView
            val windowWidth = decor.width.coerceAtLeast(viewLoc[0] + webView.width)
            val windowHeight = decor.height.coerceAtLeast(viewLoc[1] + webView.height)

            val fitsWithoutScroll = current.rectTop >= 0.0 &&
                (current.rectTop + current.fullHeightCss) <= current.viewportHeight

            var capturedPxY = 0
            var sliceCount = 0
            var lastScrollY = Double.NaN

            if (fitsWithoutScroll) {
                // Wait for data-tw-capturing CSS to commit so carets/buttons/chrome are hidden.
                awaitWebViewVisualCommit(webView)
                current = parseMetrics(
                    evaluateJs(webView, "window.__TW_SCREENSHOT__ ? window.__TW_SCREENSHOT__.measure() : null")
                ) ?: current
                val bounds = computeSliceBounds(
                    metrics = current,
                    scale = scale,
                    capturedPxY = 0,
                    targetWidthPx = targetWidthPx,
                    targetFullHeightPx = targetFullHeightPx,
                    webViewWidthPx = webView.width,
                    webViewHeightPx = webView.height
                )
                if (bounds != null) {
                    val sliceBmp = copySlice(window, webView, viewLoc, windowWidth, windowHeight, bounds)
                    if (sliceBmp != null) {
                        stitchCanvas.drawBitmap(sliceBmp, 0f, 0f, null)
                        capturedPxY = sliceBmp.height
                        sliceBmp.recycle()
                    }
                }
            }

            while (capturedPxY < targetFullHeightPx && sliceCount < MAX_SLICES) {
                sliceCount++
                val desiredScrollY = computeDesiredScrollY(
                    docTopCss = current.docTop,
                    capturedPxY = capturedPxY,
                    scale = scale
                )
                val scrolledRaw = evaluateJs(
                    webView,
                    "window.__TW_SCREENSHOT__ ? window.__TW_SCREENSHOT__.scrollToAndMeasure($desiredScrollY) : null"
                )
                current = parseMetrics(scrolledRaw) ?: break
                awaitWebViewVisualCommit(webView)
                current = parseMetrics(
                    evaluateJs(webView, "window.__TW_SCREENSHOT__ ? window.__TW_SCREENSHOT__.measure() : null")
                ) ?: current

                val bounds = computeSliceBounds(
                    metrics = current,
                    scale = scale,
                    capturedPxY = capturedPxY,
                    targetWidthPx = targetWidthPx,
                    targetFullHeightPx = targetFullHeightPx,
                    webViewWidthPx = webView.width,
                    webViewHeightPx = webView.height
                ) ?: break

                val sliceBmp = copySlice(window, webView, viewLoc, windowWidth, windowHeight, bounds)
                    ?: break
                stitchCanvas.drawBitmap(sliceBmp, 0f, capturedPxY.toFloat(), null)
                val copiedH = sliceBmp.height
                sliceBmp.recycle()
                if (copiedH <= 0) break
                capturedPxY += copiedH

                val scrollStuck = !lastScrollY.isNaN() && kotlin.math.abs(current.scrollY - lastScrollY) < 1.0
                lastScrollY = current.scrollY
                if (scrollStuck && bounds.topInView + bounds.heightPx >= webView.height - 2) {
                    break
                }
            }

            if (capturedPxY <= 0) {
                stitched.recycle()
                return Result.failure(IllegalStateException("Screenshot capture produced an empty image"))
            }

            val finalBitmap = if (capturedPxY < stitched.height) {
                Bitmap.createBitmap(stitched, 0, 0, stitched.width, capturedPxY).also {
                    stitched.recycle()
                }
            } else {
                stitched
            }

            Result.success(
                CapturedTweetScreenshot(
                    fullBitmap = finalBitmap,
                    cleanHeightPx = targetCleanHeightPx.coerceIn(1, finalBitmap.height),
                    surfaceTheme = surfaceTheme,
                    surfaceColorInt = surfaceColorInt,
                    tweetId = current.tweetId,
                    authorHandle = current.authorHandle
                )
            )
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            Result.failure(err)
        } finally {
            runCatching { webView.evaluateJavascript("window.__TW_SCREENSHOT__ && window.__TW_SCREENSHOT__.end();", null) }
        }
    }

    private suspend fun copySlice(
        window: Window,
        webView: WebView,
        viewLoc: IntArray,
        windowWidth: Int,
        windowHeight: Int,
        bounds: SliceCaptureBounds
    ): Bitmap? {
        val left = (viewLoc[0] + bounds.leftInView).coerceIn(0, (windowWidth - 1).coerceAtLeast(0))
        val top = (viewLoc[1] + bounds.topInView).coerceIn(0, (windowHeight - 1).coerceAtLeast(0))
        val right = (left + bounds.widthPx).coerceIn(left + 1, windowWidth.coerceAtLeast(left + 1))
        val bottom = (top + bounds.heightPx).coerceIn(top + 1, windowHeight.coerceAtLeast(top + 1))
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) return null

        val dest = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val srcRect = Rect(left, top, right, bottom)
        val copied = requestPixelCopy(window, srcRect, dest)
        if (copied) return dest

        // Software fallback if PixelCopy fails on an unusual surface state.
        return runCatching {
            val canvas = Canvas(dest)
            canvas.translate(-(webView.scrollX + bounds.leftInView).toFloat(), -(webView.scrollY + bounds.topInView).toFloat())
            webView.draw(canvas)
            dest
        }.getOrElse {
            dest.recycle()
            null
        }
    }

    private suspend fun requestPixelCopy(window: Window, srcRect: Rect, dest: Bitmap): Boolean {
        val result = withTimeoutOrNull(PIXEL_COPY_TIMEOUT_MS) {
            suspendCancellableCoroutine<Int> { cont ->
                runCatching {
                    PixelCopy.request(window, srcRect, dest, { copyResult ->
                        if (cont.isActive) cont.resume(copyResult)
                    }, mainHandler)
                }.onFailure {
                    if (cont.isActive) cont.resume(PixelCopy.ERROR_UNKNOWN)
                }
            }
        }
        return result == PixelCopy.SUCCESS
    }

    private suspend fun awaitWebViewVisualCommit(webView: WebView) {
        webView.invalidate()
        withTimeoutOrNull(VISUAL_STATE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                val reqId = visualStateSeq.incrementAndGet()
                runCatching {
                    webView.postVisualStateCallback(reqId, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    })
                }.onFailure {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
        }
        delay(FRAME_SETTLE_DELAY_MS)
        awaitChoreographerFrame()
    }

    private suspend fun awaitChoreographerFrame() {
        withTimeoutOrNull(64L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val cb = Choreographer.FrameCallback {
                    if (cont.isActive) cont.resume(Unit)
                }
                Choreographer.getInstance().postFrameCallback(cb)
                cont.invokeOnCancellation {
                    Choreographer.getInstance().removeFrameCallback(cb)
                }
            }
        }
    }

    private suspend fun evaluateJs(webView: WebView, script: String): String? =
        withTimeoutOrNull(JS_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                webView.evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            }
        }

    internal fun computePixelScale(webViewWidthPx: Int, viewportWidthCss: Double): Double {
        if (webViewWidthPx <= 0 || viewportWidthCss <= 0.0) return 0.0
        return webViewWidthPx.toDouble() / viewportWidthCss
    }

    internal fun computeDesiredScrollY(docTopCss: Double, capturedPxY: Int, scale: Double): Int {
        if (scale <= 0.0) return 0
        val marginCss = if (capturedPxY == 0) 12.0 else 8.0
        val neededOffsetCss = capturedPxY.toDouble() / scale
        return (docTopCss + neededOffsetCss - marginCss).roundToInt().coerceAtLeast(0)
    }

    internal fun computeSliceBounds(
        metrics: TweetCaptureMetrics,
        scale: Double,
        capturedPxY: Int,
        targetWidthPx: Int,
        targetFullHeightPx: Int,
        webViewWidthPx: Int,
        webViewHeightPx: Int
    ): SliceCaptureBounds? {
        if (scale <= 0.0 || webViewWidthPx <= 0 || webViewHeightPx <= 0) return null
        if (capturedPxY >= targetFullHeightPx) return null
        val articleLeftPx = (metrics.rectLeft * scale).roundToInt()
        val articleTopPx = (metrics.rectTop * scale).roundToInt()
        val leftInView = articleLeftPx.coerceIn(0, (webViewWidthPx - 1).coerceAtLeast(0))
        val rightInView = (leftInView + targetWidthPx).coerceIn(leftInView + 1, webViewWidthPx)
        val topInView = (articleTopPx + capturedPxY).coerceIn(0, webViewHeightPx)
        val bottomInView = (articleTopPx + targetFullHeightPx).coerceIn(topInView, webViewHeightPx)
        val w = rightInView - leftInView
        val h = bottomInView - topInView
        if (w <= 0 || h <= 0) return null
        return SliceCaptureBounds(leftInView, topInView, w, h)
    }

    internal fun parseMetrics(rawEvalResult: String?): TweetCaptureMetrics? {
        val trimmed = rawEvalResult?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
        val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        val obj = when (element) {
            is JsonObject -> element
            is JsonPrimitive -> {
                if (!element.isString) return null
                runCatching { Json.parseToJsonElement(element.content) as? JsonObject }.getOrNull()
            }
            else -> null
        } ?: return null

        fun num(key: String, default: Double = 0.0): Double =
            (obj[key] as? JsonPrimitive)?.doubleOrNull ?: default

        fun str(key: String): String =
            (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

        val rectW = num("rectWidth")
        val rectH = num("rectHeight")
        val vw = num("viewportWidth")
        val vh = num("viewportHeight")
        if (rectW <= 0.0 || rectH <= 0.0 || vw <= 0.0 || vh <= 0.0) return null
        val fullH = num("fullHeightCss", rectH).coerceAtLeast(1.0)
        val cleanH = num("cleanHeightCss", fullH).coerceIn(1.0, fullH)
        return TweetCaptureMetrics(
            rectLeft = num("rectLeft"),
            rectTop = num("rectTop"),
            rectWidth = rectW,
            rectHeight = rectH,
            docTop = num("docTop"),
            scrollY = num("scrollY"),
            scrollHeight = num("scrollHeight", vh),
            viewportWidth = vw,
            viewportHeight = vh,
            cleanHeightCss = cleanH,
            fullHeightCss = fullH,
            surfaceColorHex = str("surfaceColorHex"),
            tweetId = str("tweetId"),
            authorHandle = str("authorHandle")
        )
    }
}
