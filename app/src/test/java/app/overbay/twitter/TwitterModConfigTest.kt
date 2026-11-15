package app.overbay.twitter

import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.model.VerifiedBadgeMode
import app.overbay.twitter.webview.TwitterRequestBlocker
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(defaults.forceFollowingTab)
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
            hideForYouTab = true,
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
    fun requestBlockerMatchesTelemetryAndInternalHostsAccurately() {
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://analytics.twitter.com/i/adsct"))
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/1.1/jot/client_event.json"))
        assertTrue(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/graphql/abc123/ClientEvent"))
        assertFalse(TwitterRequestBlocker.shouldBlockTelemetryOrAd("https://x.com/i/api/graphql/xyz/HomeLatestTimeline"))

        assertTrue(TwitterRequestBlocker.isInternalTwitterHost("x.com"))
        assertTrue(TwitterRequestBlocker.isInternalTwitterHost("mobile.twitter.com"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("t.co"))
        assertFalse(TwitterRequestBlocker.isInternalTwitterHost("example.com"))
    }
}
