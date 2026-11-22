package app.overbay.twitter.screenshot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import app.overbay.twitter.model.TweetScreenshotStyle
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import kotlin.math.roundToInt

internal data class ScreenshotCardLayout(
    val outputWidth: Int,
    val outputHeight: Int,
    val outerPadX: Int,
    val outerPadY: Int,
    val cardPadX: Int,
    val cardPadTop: Int,
    val cardPadBottom: Int,
    val cornerRadius: Float,
    val strokeWidth: Float
)

/**
 * Formats a raw captured tweet [Bitmap] into a clean, share-ready PNG matching the user's
 * selected [TweetScreenshotStyle] and active [TwitterSurfaceTheme], with zero watermarks.
 */
object TweetScreenshotFormatter {

    fun format(
        captured: CapturedTweetScreenshot,
        style: TweetScreenshotStyle,
        includeMetrics: Boolean
    ): Bitmap {
        val srcWidth = captured.fullBitmap.width.coerceAtLeast(1)
        val cropHeight = if (includeMetrics) {
            captured.fullBitmap.height
        } else {
            captured.cleanHeightPx.coerceIn(1, captured.fullBitmap.height)
        }
        val layout = computeLayout(srcWidth, cropHeight, style)
        val out = Bitmap.createBitmap(layout.outputWidth, layout.outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val srcRect = Rect(0, 0, srcWidth, cropHeight)

        if (style == TweetScreenshotStyle.TIGHT) {
            canvas.drawColor(captured.surfaceColorInt)
            val dstRect = Rect(
                layout.cardPadX,
                layout.cardPadTop,
                layout.cardPadX + srcWidth,
                layout.cardPadTop + cropHeight
            )
            canvas.drawBitmap(captured.fullBitmap, srcRect, dstRect, bitmapPaint())
            return out
        }

        drawBackdrop(canvas, layout, style, captured.surfaceTheme)

        val cardRect = RectF(
            layout.outerPadX.toFloat(),
            layout.outerPadY.toFloat(),
            (layout.outputWidth - layout.outerPadX).toFloat(),
            (layout.outputHeight - layout.outerPadY).toFloat()
        )

        // Draw subtle elevation shadow beneath the floating card.
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = captured.surfaceColorInt
            val shadowRadius = (srcWidth * if (style == TweetScreenshotStyle.FRAMED) 0.032f else 0.016f)
            val shadowDy = (srcWidth * if (style == TweetScreenshotStyle.FRAMED) 0.012f else 0.005f)
            val shadowColor = when {
                style == TweetScreenshotStyle.FRAMED -> Color.argb(92, 0, 12, 28)
                captured.surfaceTheme == TwitterSurfaceTheme.LIGHT -> Color.argb(30, 15, 20, 25)
                else -> Color.argb(72, 0, 0, 0)
            }
            setShadowLayer(shadowRadius, 0f, shadowDy, shadowColor)
        }
        canvas.drawRoundRect(cardRect, layout.cornerRadius, layout.cornerRadius, shadowPaint)

        // Clip and paint the card interior + cropped tweet bitmap at crisp 1:1 resolution.
        val clipPath = Path().apply {
            addRoundRect(cardRect, layout.cornerRadius, layout.cornerRadius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawColor(captured.surfaceColorInt)
        val tweetLeft = layout.outerPadX + layout.cardPadX
        val tweetTop = layout.outerPadY + layout.cardPadTop
        val dstRect = Rect(tweetLeft, tweetTop, tweetLeft + srcWidth, tweetTop + cropHeight)
        canvas.drawBitmap(captured.fullBitmap, srcRect, dstRect, bitmapPaint())
        canvas.restore()

        // Crisp hairline border around the card edge.
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = layout.strokeWidth
            color = resolveCardBorderColor(style, captured.surfaceTheme)
        }
        val inset = layout.strokeWidth / 2f
        val borderRect = RectF(
            cardRect.left + inset,
            cardRect.top + inset,
            cardRect.right - inset,
            cardRect.bottom - inset
        )
        canvas.drawRoundRect(borderRect, layout.cornerRadius, layout.cornerRadius, borderPaint)

        return out
    }

    private fun drawBackdrop(
        canvas: Canvas,
        layout: ScreenshotCardLayout,
        style: TweetScreenshotStyle,
        surfaceTheme: TwitterSurfaceTheme
    ) {
        if (style == TweetScreenshotStyle.CLEAN_CARD) {
            val matteColor = when (surfaceTheme) {
                TwitterSurfaceTheme.LIGHT -> Color.rgb(239, 243, 244)      // #EFF3F4
                TwitterSurfaceTheme.DIM -> Color.rgb(15, 23, 32)           // #0F1720
                TwitterSurfaceTheme.LIGHTS_OUT -> Color.rgb(22, 24, 28)    // #16181C
            }
            canvas.drawColor(matteColor)
            return
        }

        val gradientColors = when (surfaceTheme) {
            TwitterSurfaceTheme.LIGHT -> intArrayOf(
                Color.rgb(76, 195, 255),
                Color.rgb(29, 161, 242),
                Color.rgb(15, 122, 209)
            )
            TwitterSurfaceTheme.DIM -> intArrayOf(
                Color.rgb(29, 161, 242),
                Color.rgb(18, 105, 181),
                Color.rgb(13, 59, 102)
            )
            TwitterSurfaceTheme.LIGHTS_OUT -> intArrayOf(
                Color.rgb(26, 140, 216),
                Color.rgb(14, 77, 138),
                Color.rgb(9, 34, 59)
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                layout.outputWidth.toFloat(),
                layout.outputHeight.toFloat(),
                gradientColors,
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, layout.outputWidth.toFloat(), layout.outputHeight.toFloat(), paint)
    }

    private fun resolveCardBorderColor(
        style: TweetScreenshotStyle,
        surfaceTheme: TwitterSurfaceTheme
    ): Int = when {
        style == TweetScreenshotStyle.FRAMED && surfaceTheme == TwitterSurfaceTheme.LIGHT ->
            Color.argb(28, 15, 20, 25)
        style == TweetScreenshotStyle.FRAMED ->
            Color.argb(46, 255, 255, 255)
        surfaceTheme == TwitterSurfaceTheme.LIGHT ->
            Color.rgb(207, 217, 222) // #CFD9DE
        surfaceTheme == TwitterSurfaceTheme.DIM ->
            Color.rgb(56, 68, 77)    // #38444D
        else ->
            Color.rgb(47, 51, 54)    // #2F3336
    }

    private fun bitmapPaint(): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    internal fun computeLayout(
        tweetWidthPx: Int,
        tweetHeightPx: Int,
        style: TweetScreenshotStyle
    ): ScreenshotCardLayout {
        val w = tweetWidthPx.coerceAtLeast(1)
        val h = tweetHeightPx.coerceAtLeast(1)
        return when (style) {
            TweetScreenshotStyle.TIGHT -> {
                val padY = (w * 0.014f).roundToInt().coerceAtLeast(4)
                ScreenshotCardLayout(
                    outputWidth = w,
                    outputHeight = h + padY * 2,
                    outerPadX = 0,
                    outerPadY = 0,
                    cardPadX = 0,
                    cardPadTop = padY,
                    cardPadBottom = padY,
                    cornerRadius = 0f,
                    strokeWidth = 0f
                )
            }
            TweetScreenshotStyle.CLEAN_CARD -> {
                val outerPad = (w * 0.036f).roundToInt().coerceAtLeast(12)
                val cardPadX = (w * 0.014f).roundToInt().coerceAtLeast(4)
                val cardPadTop = (w * 0.016f).roundToInt().coerceAtLeast(6)
                val cardPadBottom = (w * 0.022f).roundToInt().coerceAtLeast(8)
                ScreenshotCardLayout(
                    outputWidth = w + (outerPad + cardPadX) * 2,
                    outputHeight = h + outerPad * 2 + cardPadTop + cardPadBottom,
                    outerPadX = outerPad,
                    outerPadY = outerPad,
                    cardPadX = cardPadX,
                    cardPadTop = cardPadTop,
                    cardPadBottom = cardPadBottom,
                    cornerRadius = (w * 0.042f).coerceAtLeast(14f),
                    strokeWidth = (w * 0.0022f).coerceAtLeast(1.5f)
                )
            }
            TweetScreenshotStyle.FRAMED -> {
                val outerPad = (w * 0.082f).roundToInt().coerceAtLeast(24)
                val cardPadX = (w * 0.016f).roundToInt().coerceAtLeast(6)
                val cardPadTop = (w * 0.018f).roundToInt().coerceAtLeast(8)
                val cardPadBottom = (w * 0.026f).roundToInt().coerceAtLeast(10)
                ScreenshotCardLayout(
                    outputWidth = w + (outerPad + cardPadX) * 2,
                    outputHeight = h + outerPad * 2 + cardPadTop + cardPadBottom,
                    outerPadX = outerPad,
                    outerPadY = outerPad,
                    cardPadX = cardPadX,
                    cardPadTop = cardPadTop,
                    cardPadBottom = cardPadBottom,
                    cornerRadius = (w * 0.048f).coerceAtLeast(16f),
                    strokeWidth = (w * 0.0022f).coerceAtLeast(1.5f)
                )
            }
        }
    }
}
