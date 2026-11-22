package app.overbay.twitter

import app.overbay.twitter.model.TweetScreenshotStyle
import app.overbay.twitter.screenshot.SliceCaptureBounds
import app.overbay.twitter.screenshot.TweetCaptureMetrics
import app.overbay.twitter.screenshot.TweetScreenshotCapturer
import app.overbay.twitter.screenshot.TweetScreenshotExporter
import app.overbay.twitter.screenshot.TweetScreenshotFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TweetScreenshotTest {

    @Test
    fun parseMetricsHandlesRawObjectsQuotedStringsAndInvalidPayloads() {
        val rawObj = """{"rectLeft":16,"rectTop":120,"rectWidth":380,"rectHeight":260,"docTop":520,"scrollY":400,"scrollHeight":4000,"viewportWidth":412,"viewportHeight":915,"cleanHeightCss":210,"fullHeightCss":256,"surfaceColorHex":"#15202B","tweetId":"123456","authorHandle":"jack"}"""
        val parsed = TweetScreenshotCapturer.parseMetrics(rawObj)
        assertNotNull(parsed)
        assertEquals(16.0, parsed!!.rectLeft, 0.001)
        assertEquals(380.0, parsed.rectWidth, 0.001)
        assertEquals(210.0, parsed.cleanHeightCss, 0.001)
        assertEquals(256.0, parsed.fullHeightCss, 0.001)
        assertEquals("#15202B", parsed.surfaceColorHex)
        assertEquals("123456", parsed.tweetId)
        assertEquals("jack", parsed.authorHandle)

        // Quoted JSON string payload (as sometimes returned by WebView.evaluateJavascript)
        val quoted = "\"${rawObj.replace("\"", "\\\"")}\""
        assertEquals(parsed, TweetScreenshotCapturer.parseMetrics(quoted))

        // Clean height exceeding fullHeightCss is clamped safely
        val clampedRaw = """{"rectLeft":0,"rectTop":0,"rectWidth":400,"rectHeight":200,"docTop":0,"scrollY":0,"scrollHeight":800,"viewportWidth":400,"viewportHeight":800,"cleanHeightCss":999,"fullHeightCss":180,"surfaceColorHex":"#FFFFFF","tweetId":"1","authorHandle":""}"""
        val clamped = TweetScreenshotCapturer.parseMetrics(clampedRaw)
        assertNotNull(clamped)
        assertEquals(180.0, clamped!!.cleanHeightCss, 0.001)

        // Null / malformed / zero-width responses fail cleanly
        assertNull(TweetScreenshotCapturer.parseMetrics(null))
        assertNull(TweetScreenshotCapturer.parseMetrics("null"))
        assertNull(TweetScreenshotCapturer.parseMetrics("undefined"))
        assertNull(TweetScreenshotCapturer.parseMetrics("{}"))
        assertNull(TweetScreenshotCapturer.parseMetrics("""{"rectWidth":0,"rectHeight":100,"viewportWidth":400,"viewportHeight":800}"""))
    }

    @Test
    fun pixelScaleAndMultiSliceBoundsStitchTallTweetsWithoutDrift() {
        val scale = TweetScreenshotCapturer.computePixelScale(webViewWidthPx = 1080, viewportWidthCss = 360.0)
        assertEquals(3.0, scale, 0.0001)
        assertEquals(0.0, TweetScreenshotCapturer.computePixelScale(0, 360.0), 0.0001)

        // First slice scroll position leaves 12px CSS breathing room and clamps >= 0
        assertEquals(488, TweetScreenshotCapturer.computeDesiredScrollY(docTopCss = 500.0, capturedPxY = 0, scale = 3.0))
        assertEquals(0, TweetScreenshotCapturer.computeDesiredScrollY(docTopCss = 4.0, capturedPxY = 0, scale = 3.0))

        // Tall tweet (1000 CSS px = 3000 physical px) on a 700 CSS px (2100 px) viewport
        val slice1Metrics = TweetCaptureMetrics(
            rectLeft = 0.0, rectTop = 12.0, rectWidth = 360.0, rectHeight = 1000.0,
            docTop = 500.0, scrollY = 488.0, scrollHeight = 4000.0,
            viewportWidth = 360.0, viewportHeight = 700.0,
            cleanHeightCss = 950.0, fullHeightCss = 1000.0,
            surfaceColorHex = "#000000", tweetId = "99", authorHandle = "dev"
        )
        val bounds1 = TweetScreenshotCapturer.computeSliceBounds(
            metrics = slice1Metrics,
            scale = 3.0,
            capturedPxY = 0,
            targetWidthPx = 1080,
            targetFullHeightPx = 3000,
            webViewWidthPx = 1080,
            webViewHeightPx = 2100
        )
        assertEquals(SliceCaptureBounds(leftInView = 0, topInView = 36, widthPx = 1080, heightPx = 2064), bounds1)

        // Second slice continues seamlessly from capturedPxY = 2064
        val nextScroll = TweetScreenshotCapturer.computeDesiredScrollY(docTopCss = 500.0, capturedPxY = 2064, scale = 3.0)
        assertEquals(1180, nextScroll) // 500 + 688 - 8

        val slice2Metrics = slice1Metrics.copy(rectTop = 500.0 - 1180.0, scrollY = 1180.0)
        val bounds2 = TweetScreenshotCapturer.computeSliceBounds(
            metrics = slice2Metrics,
            scale = 3.0,
            capturedPxY = 2064,
            targetWidthPx = 1080,
            targetFullHeightPx = 3000,
            webViewWidthPx = 1080,
            webViewHeightPx = 2100
        )
        // articleTopPx = -2040, topInView = -2040 + 2064 = 24, bottomInView = -2040 + 3000 = 960 -> height = 936
        assertEquals(SliceCaptureBounds(leftInView = 0, topInView = 24, widthPx = 1080, heightPx = 936), bounds2)
        assertEquals(3000, bounds1!!.heightPx + bounds2!!.heightPx)

        // Wide foldable layout with centered column (rectLeft > 0)
        val foldableMetrics = slice1Metrics.copy(rectLeft = 100.0, rectTop = 50.0, rectWidth = 500.0, viewportWidth = 700.0, fullHeightCss = 200.0)
        val foldBounds = TweetScreenshotCapturer.computeSliceBounds(
            metrics = foldableMetrics,
            scale = 2.0,
            capturedPxY = 0,
            targetWidthPx = 1000,
            targetFullHeightPx = 400,
            webViewWidthPx = 1400,
            webViewHeightPx = 1600
        )
        assertEquals(SliceCaptureBounds(leftInView = 200, topInView = 100, widthPx = 1000, heightPx = 400), foldBounds)

        // Completed capture returns null
        assertNull(
            TweetScreenshotCapturer.computeSliceBounds(
                metrics = slice2Metrics,
                scale = 3.0,
                capturedPxY = 3000,
                targetWidthPx = 1080,
                targetFullHeightPx = 3000,
                webViewWidthPx = 1080,
                webViewHeightPx = 2100
            )
        )
    }

    @Test
    fun cardFormatterLayoutsMatchStyleExpectations() {
        val tight = TweetScreenshotFormatter.computeLayout(1000, 600, TweetScreenshotStyle.TIGHT)
        assertEquals(1000, tight.outputWidth)
        assertEquals(0, tight.outerPadX)
        assertEquals(0f, tight.cornerRadius, 0.001f)
        assertTrue(tight.outputHeight > 600)

        val clean = TweetScreenshotFormatter.computeLayout(1000, 600, TweetScreenshotStyle.CLEAN_CARD)
        assertTrue(clean.outerPadX > 0)
        assertTrue(clean.cornerRadius > 0f)
        assertTrue(clean.strokeWidth > 0f)
        assertTrue(clean.outputWidth > tight.outputWidth)
        assertTrue(clean.outputHeight > tight.outputHeight)

        val framed = TweetScreenshotFormatter.computeLayout(1000, 600, TweetScreenshotStyle.FRAMED)
        assertTrue(framed.outerPadX > clean.outerPadX)
        assertTrue(framed.outputWidth > clean.outputWidth)
        assertTrue(framed.outputHeight > clean.outputHeight)
        assertTrue(framed.cornerRadius >= clean.cornerRadius)
    }

    @Test
    fun exporterSanitizesFilenamesSafely() {
        assertEquals(
            "tweet_jack_123456_9999",
            TweetScreenshotExporter.buildFilenameBase("@jack", "123456", timestampMs = 9999L)
        )
        assertEquals(
            "tweet_username_777x1_42",
            TweetScreenshotExporter.buildFilenameBase("user/..name!", "777?x=1", timestampMs = 42L)
        )
        assertEquals(
            "tweet_42",
            TweetScreenshotExporter.buildFilenameBase("", "", timestampMs = 42L)
        )
    }
}
