package app.overbay.twitter

import android.content.SharedPreferences
import app.overbay.twitter.model.DetectedHomeFeed
import app.overbay.twitter.model.HomeFeedKind
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.model.TwitterThemeMode
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
        assertEquals(TwitterThemeMode.AUTO, defaults.themeMode)
        assertEquals(TwitterDarkVariant.LIGHTS_OUT, defaults.autoDarkVariant)
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
            themeMode = TwitterThemeMode.DIM,
            autoDarkVariant = TwitterDarkVariant.DIM,
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
                themeMode = TwitterThemeMode.LIGHTS_OUT,
                autoDarkVariant = TwitterDarkVariant.DIM,
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

    @Test
    fun decodeConfigJsonMigratesLegacyHideForYouTabWhileAdoptingForYouDefault() {
        val legacyDefaultJson = """{"forceFollowingTab":true,"hideForYouTab":false,"blockAds":true}"""
        val legacyDefault = TwitterConfigRepository.decodeConfigJson(legacyDefaultJson)
        assertEquals("For you", legacyDefault.defaultHomeFeed)
        assertTrue(legacyDefault.hiddenHomeFeeds.isEmpty())

        val legacyHiddenJson = """{"forceFollowingTab":true,"hideForYouTab":true,"hideViewCounts":false}"""
        val legacyHidden = TwitterConfigRepository.decodeConfigJson(legacyHiddenJson)
        assertEquals(listOf("For you"), legacyHidden.hiddenHomeFeeds)
        assertEquals("Following", legacyHidden.defaultHomeFeed)
        assertFalse(legacyHidden.hideViewCounts)

        val modernJson = """{"defaultHomeFeed":"For you","hiddenHomeFeeds":[],"hideForYouTab":true}"""
        val modern = TwitterConfigRepository.decodeConfigJson(modernJson)
        assertEquals("For you", modern.defaultHomeFeed)
        assertTrue(modern.hiddenHomeFeeds.isEmpty())

        val malformed = TwitterConfigRepository.decodeConfigJson("{not valid json")
        assertEquals(TwitterModConfig(), malformed)
    }

    @Test
    fun repositoryHomeFeedMutationsUpdateDefaultPinsVisibilityAndOrder() {
        val repo = TwitterConfigRepository(InMemorySharedPreferences())
        val nfl = DetectedHomeFeed("NFL", HomeFeedKind.SUGGESTED)
        val forYou = DetectedHomeFeed("For you", HomeFeedKind.CORE)

        // Enabling a suggested feed pins it so stripSuggestedHomeFeeds won't hide it
        repo.toggleHomeFeedVisibility(nfl, visible = true)
        assertTrue(repo.config.value.isHomeFeedVisible(nfl))
        assertTrue("NFL" in repo.config.value.pinnedHomeFeeds)

        // Hiding the current default feed falls back to the first remaining visible feed
        repo.toggleHomeFeedVisibility(forYou, visible = false)
        assertFalse(repo.config.value.isHomeFeedVisible(forYou))
        assertEquals("Following", repo.config.value.defaultHomeFeed)

        // Selecting a hidden/suggested feed as default un-hides and pins it
        repo.setDefaultHomeFeed("NFL")
        assertEquals("NFL", repo.config.value.defaultHomeFeed)
        assertTrue(repo.config.value.isHomeFeedVisible(nfl))

        // Reordering moves Following up ahead of For you
        repo.moveHomeFeed("Following", -1)
        assertEquals("Following", repo.config.value.homeFeedOrder.first())
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }
}
