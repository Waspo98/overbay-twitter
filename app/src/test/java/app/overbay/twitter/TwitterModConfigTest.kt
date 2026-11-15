package app.overbay.twitter

import app.overbay.twitter.model.DetectedHomeFeed
import app.overbay.twitter.model.HomeFeedKind
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.model.VerifiedBadgeMode
import app.overbay.twitter.webview.TwitterRequestBlocker
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterModConfigTest {

    @Test
    fun defaultConfigEnablesCoreAdGrokUpsellAndBrandingMods() {
        val defaults = TwitterModConfig()
        assertTrue(defaults.classicTwitterBranding)
        assertTrue(defaults.blockAds)
        assertTrue(defaults.removeGrok)
        assertTrue(defaults.removePremiumUpsells)
        assertEquals("For you", defaults.defaultHomeFeed)
        assertTrue(defaults.stripSuggestedHomeFeeds)
        assertFalse(defaults.hideAddFeedButton)
        assertTrue(defaults.homeFeedOrder.isEmpty())
        assertTrue(defaults.hiddenHomeFeeds.isEmpty())
        assertTrue(defaults.pinnedHomeFeeds.isEmpty())
        assertTrue(defaults.stripTimelineCarousels)
        assertTrue(defaults.disableAutoRefreshOnResume)
        assertTrue(defaults.hideViewCounts)
        assertTrue(defaults.cleanBottomNav)
        assertTrue(defaults.enableVideoDownloader)
        assertTrue(defaults.forceOrigQualityImages)
        assertTrue(defaults.unwrapTcoLinks)
        assertTrue(defaults.openLinksInCustomTab)
        assertEquals(VerifiedBadgeMode.SHOW_ALL, defaults.verifiedBadgeMode)
        assertTrue(defaults.mutedPatterns.isEmpty())
    }

    @Test
    fun jsonRoundTripPreservesAllSettingsAndMutePatterns() {
        val custom = TwitterModConfig(
            classicTwitterBranding = false,
            defaultHomeFeed = "Following",
            stripSuggestedHomeFeeds = false,
            hideAddFeedButton = true,
            homeFeedOrder = listOf("Following", "Tech List", "For you"),
            hiddenHomeFeeds = listOf("NFL"),
            pinnedHomeFeeds = listOf("NBA"),
            demoteBlueCheckReplies = true,
            hideRetweets = true,
            hideQuoteTweets = true,
            hideBookmarkCounts = true,
            verifiedBadgeMode = VerifiedBadgeMode.HIDE_PAID_BLUE,
            openLinksInCustomTab = false,
            disableVideoAutoplay = true,
            mutedPatterns = listOf("crypto", "/\\bpromo\\b/i", "spam.example")
        )
        val encoded = TwitterConfigRepository.json.encodeToString(custom)
        val decoded = TwitterConfigRepository.json.decodeFromString<TwitterModConfig>(encoded)
        assertEquals(custom, decoded)
    }

    @Test
    fun requiresReloadFlagsGraphQlAndPlaybackChangesOnly() {
        val base = TwitterModConfig()
        assertFalse(
            base.copy(
                classicTwitterBranding = false,
                removeGrok = false,
                hideViewCounts = false,
                verifiedBadgeMode = VerifiedBadgeMode.HIDE_ALL
            ).requiresReloadComparedTo(base)
        )
        assertTrue(base.copy(blockAds = false).requiresReloadComparedTo(base))
        assertTrue(base.copy(hideRetweets = true).requiresReloadComparedTo(base))
        assertTrue(base.copy(mutedPatterns = listOf("spoiler")).requiresReloadComparedTo(base))
        assertTrue(base.copy(disableVideoAutoplay = true).requiresReloadComparedTo(base))
    }

    @Test
    fun requestBlockerMatchesTelemetryCdnFastPassAndInternalHostsAccurately() {
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://analytics.twitter.com/i/adsct"))
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://sub.ads-twitter.com:443/pixel"))
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/1.1/jot/client_event.json"))
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/graphql/abc123/ClientEvent?variables={\"raw\":true}"))
        assertFalse(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/graphql/xyz/HomeLatestTimeline?query=ClientEvent"))
        assertFalse(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://pbs.twimg.com/media/Gabc123?format=jpg&name=orig"))
        assertFalse(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://video.twimg.com/ext_tw_video/123/pu/vid/avc1/720x1280/clip.mp4"))

        assertTrue(TwitterRequestBlocker.isInternalTwitterHost("x.com"))
        assertTrue(TwitterRequestBlocker.isInternalTwitterHost("mobile.twitter.com"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("notx.com"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("ads.twitter.com"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("analytics.twitter.com"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("t.co"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("example.com"))

        assertEquals(
            "https://example.com/article?id=42&page=2#section-3",
            TwitterRequestBlocker.cleanTrackingUrl(
                "https://example.com/article?utm_source=twitter&id=42&fbclid=abc123&page=2&UTM_MEDIUM=social#section-3"
            )
        )
        assertEquals(
            "https://example.com/clean#top",
            TwitterRequestBlocker.cleanTrackingUrl("https://example.com/clean?gclid=xyz&utm_campaign=launch#top")
        )
    }

    @Test
    fun normalizeAcceptTypesHandlesExtensionsCommaListsAndFallback() {
        val extMap = mapOf("jpg" to "image/jpeg", "mp4" to "video/mp4")
        val resolved = TwitterRequestBlocker.normalizeAcceptTypes(
            arrayOf(".jpg, image/png", "video/*, .mp4", "invalid")
        ) { extMap[it] }
        assertArrayEquals(arrayOf("image/jpeg", "image/png", "video/*", "video/mp4"), resolved)

        val fallback = TwitterRequestBlocker.normalizeAcceptTypes(null) { null }
        assertArrayEquals(arrayOf("image/*", "video/*"), fallback)
    }

    @Test
    fun buildEffectiveUserAgentTogglesMobileTokenByFoldPosture() {
        val tabletUa = "Mozilla/5.0 (Linux; Android 16; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/134.0.0.0 Safari/537.36"
        val mobileUa = "Mozilla/5.0 (Linux; Android 16; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/134.0.0.0 Mobile Safari/537.36"

        val splitOrFoldedFromTablet = TwitterRequestBlocker.buildEffectiveUserAgent(tabletUa, isFullyUnfolded = false)
        assertTrue(splitOrFoldedFromTablet.contains("Mobile Safari/537.36"))
        assertFalse(splitOrFoldedFromTablet.contains("; wv"))
        assertFalse(splitOrFoldedFromTablet.contains("Version/4.0"))

        val splitOrFoldedFromMobile = TwitterRequestBlocker.buildEffectiveUserAgent(mobileUa, isFullyUnfolded = false)
        assertEquals(splitOrFoldedFromTablet, splitOrFoldedFromMobile)

        val unfoldedFromMobile = TwitterRequestBlocker.buildEffectiveUserAgent(mobileUa, isFullyUnfolded = true)
        assertFalse(unfoldedFromMobile.contains("Mobile"))
        assertTrue(unfoldedFromMobile.contains("Chrome/134.0.0.0 Safari/537.36"))

        val unfoldedFromTablet = TwitterRequestBlocker.buildEffectiveUserAgent(tabletUa, isFullyUnfolded = true)
        assertEquals(unfoldedFromMobile, unfoldedFromTablet)
    }

    @Test
    fun homeFeedVisibilityRespectsSuggestedStrippingPinsAndManualHides() {
        val core = DetectedHomeFeed("For you", HomeFeedKind.CORE)
        val list = DetectedHomeFeed("Tech List", HomeFeedKind.LIST)
        val nfl = DetectedHomeFeed("NFL", HomeFeedKind.SUGGESTED)

        val defaultCfg = TwitterModConfig()
        assertTrue(defaultCfg.isHomeFeedVisible(core))
        assertTrue(defaultCfg.isHomeFeedVisible(list))
        assertFalse(defaultCfg.isHomeFeedVisible(nfl))

        val pinnedNfl = defaultCfg.copy(pinnedHomeFeeds = listOf("nfl"))
        assertTrue(pinnedNfl.isHomeFeedVisible(nfl))

        val allowSuggested = defaultCfg.copy(stripSuggestedHomeFeeds = false)
        assertTrue(allowSuggested.isHomeFeedVisible(nfl))

        val hiddenCoreAndList = allowSuggested.copy(hiddenHomeFeeds = listOf("for you", "Tech List", "NFL"))
        assertFalse(hiddenCoreAndList.isHomeFeedVisible(core))
        assertFalse(hiddenCoreAndList.isHomeFeedVisible(list))
        assertFalse(hiddenCoreAndList.isHomeFeedVisible(nfl))
    }

    @Test
    fun orderedHomeFeedsAndNormalizeListDeduplicateAndGuaranteeCoreTabs() {
        val rawDetected = listOf(
            DetectedHomeFeed("  Tech List ", HomeFeedKind.LIST),
            DetectedHomeFeed("tech list", HomeFeedKind.SUGGESTED),
            DetectedHomeFeed("NFL", HomeFeedKind.SUGGESTED)
        )
        val normalized = DetectedHomeFeed.normalizeList(rawDetected)
        assertEquals(
            listOf("For you", "Following", "Tech List", "NFL"),
            normalized.map { it.name }
        )
        assertEquals(HomeFeedKind.LIST, normalized[2].kind)

        val orderedCfg = TwitterModConfig(homeFeedOrder = listOf("tech list", "Following"))
        assertEquals(
            listOf("Tech List", "Following", "For you", "NFL"),
            orderedCfg.orderedHomeFeeds(normalized).map { it.name }
        )
    }

    @Test
    fun validateMutePatternEnforcesRegexSyntaxAndFlags() {
        assertNull(TwitterModConfig.validateMutePattern("crypto"))
        assertNull(TwitterModConfig.validateMutePattern("@spammer"))
        assertNull(TwitterModConfig.validateMutePattern("/\\bpromo\\b/i"))
        assertNull(TwitterModConfig.validateMutePattern("/https?:\\/\\/bad\\.site/im"))

        assertNotNull(TwitterModConfig.validateMutePattern("   "))
        assertNotNull(TwitterModConfig.validateMutePattern("/unclosed"))
        assertNotNull(TwitterModConfig.validateMutePattern("//i"))
        assertNotNull(TwitterModConfig.validateMutePattern("/valid/g"))
        assertNotNull(TwitterModConfig.validateMutePattern("/[unclosedGroup/i"))
    }

    @Test
    fun detectedHomeFeedJsonDecodesExpectedKindsAndReloadCheckStaysInstant() {
        val raw = """[{"name":"For you","kind":"CORE"},{"name":"Tech","kind":"LIST"},{"name":"NFL","kind":"SUGGESTED"}]"""
        val decoded = TwitterConfigRepository.json.decodeFromString<List<DetectedHomeFeed>>(raw)
        assertEquals(
            listOf(
                DetectedHomeFeed("For you", HomeFeedKind.CORE),
                DetectedHomeFeed("Tech", HomeFeedKind.LIST),
                DetectedHomeFeed("NFL", HomeFeedKind.SUGGESTED)
            ),
            decoded
        )

        val base = TwitterModConfig()
        val feedTweak = base.copy(
            defaultHomeFeed = "Following",
            stripSuggestedHomeFeeds = false,
            hideAddFeedButton = true,
            hiddenHomeFeeds = listOf("For you"),
            homeFeedOrder = listOf("Following", "Tech")
        )
        assertFalse(feedTweak.requiresReloadComparedTo(base))
    }
}
