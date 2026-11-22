package app.overbay.twitter.model

import androidx.compose.runtime.Immutable
import java.util.regex.PatternSyntaxException
import kotlinx.serialization.Serializable

/**
 * Live session filter counters reported by the GraphQL interception layer.
 */
@Immutable
data class SessionFilterStats(
    val adsBlocked: Int = 0,
    val clutterBlocked: Int = 0
) {
    val totalBlocked: Int get() = adsBlocked + clutterBlocked
}

/**
 * Controls how the app and embedded X/Twitter surface theme behave.
 */
@Serializable
enum class TwitterThemeMode(val label: String, val shortLabel: String) {
    AUTO("Follow Device", "Auto"),
    LIGHT("Light", "Light"),
    DIM("Dim", "Dim"),
    LIGHTS_OUT("Lights Out", "Lights Out");

    companion object {
        fun fromWireValue(raw: String?): TwitterThemeMode {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: AUTO
        }
    }
}

/**
 * Dark palette variant used when [TwitterThemeMode.AUTO] is active in system dark mode.
 */
@Serializable
enum class TwitterDarkVariant(val label: String) {
    DIM("Dim"),
    LIGHTS_OUT("Lights Out");

    companion object {
        fun fromWireValue(raw: String?): TwitterDarkVariant {
            return entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: LIGHTS_OUT
        }
    }
}

/**
 * Controls how verification checkmarks render across timelines, profiles, and replies.
 */
@Serializable
enum class VerifiedBadgeMode(val label: String) {
    SHOW_ALL("Show All"),
    HIDE_PAID_BLUE("Hide Paid"),
    HIDE_ALL("Hide All")
}

/**
 * Visual framing preset used when exporting a formatted tweet screenshot.
 */
@Serializable
enum class TweetScreenshotStyle(val label: String) {
    CLEAN_CARD("Clean Card"),
    FRAMED("Framed"),
    TIGHT("Tight")
}

/**
 * Classification of tabs appearing in `/home` header `[role="tablist"]`.
 */
@Serializable
enum class HomeFeedKind(val label: String) {
    CORE("Core"),
    LIST("List"),
    SUGGESTED("Suggested")
}

/**
 * A home header feed tab discovered from `/home` or baseline defaults.
 */
@Immutable
@Serializable
data class DetectedHomeFeed(
    val name: String,
    val kind: HomeFeedKind
) {
    companion object {
        val DEFAULTS = listOf(
            DetectedHomeFeed("For you", HomeFeedKind.CORE),
            DetectedHomeFeed("Following", HomeFeedKind.CORE)
        )

        /**
         * Deduplicates feed entries case-insensitively while guaranteeing core tabs exist.
         */
        fun normalizeList(feeds: List<DetectedHomeFeed>): List<DetectedHomeFeed> {
            val seen = LinkedHashSet<String>(feeds.size + 2)
            val result = ArrayList<DetectedHomeFeed>(feeds.size + 2)
            for (feed in feeds) {
                val cleanName = feed.name.trim()
                val key = cleanName.lowercase()
                if (cleanName.isNotEmpty() && seen.add(key)) {
                    val resolvedKind = if (key == "for you" || key == "following") {
                        HomeFeedKind.CORE
                    } else {
                        feed.kind
                    }
                    result.add(DetectedHomeFeed(cleanName, resolvedKind))
                }
            }
            if (seen.add("for you")) {
                result.add(0, DetectedHomeFeed("For you", HomeFeedKind.CORE))
            }
            if (seen.add("following")) {
                val insertAt = if (result.firstOrNull()?.name.equals("For you", ignoreCase = true)) 1 else 0
                result.add(insertAt, DetectedHomeFeed("Following", HomeFeedKind.CORE))
            }
            return result
        }
    }
}

/**
 * Persistent configuration for all Twitter WebView interception, branding, and UI mod layers.
 */
@Immutable
@Serializable
data class TwitterModConfig(
    // 1. Branding & Identity
    val classicTwitterBranding: Boolean = true,
    val themeMode: TwitterThemeMode = TwitterThemeMode.AUTO,
    val autoDarkVariant: TwitterDarkVariant = TwitterDarkVariant.LIGHTS_OUT,

    // 2. Ads, Grok & Upsells
    val blockAds: Boolean = true,
    val removeGrok: Boolean = true,
    val removePremiumUpsells: Boolean = true,

    // 3. Timeline & Algorithmic Control
    val defaultHomeFeed: String = "For you",
    val stripSuggestedHomeFeeds: Boolean = true,
    val hideAddFeedButton: Boolean = false,
    val homeFeedOrder: List<String> = emptyList(),
    val hiddenHomeFeeds: List<String> = emptyList(),
    val pinnedHomeFeeds: List<String> = emptyList(),
    val stripTimelineCarousels: Boolean = true,
    val demoteBlueCheckReplies: Boolean = false,
    val disableAutoRefreshOnResume: Boolean = true,
    val hideRetweets: Boolean = false,
    val hideQuoteTweets: Boolean = false,

    // 4. UI & Metric De-Cluttering
    val hideViewCounts: Boolean = true,
    val hideBookmarkCounts: Boolean = false,
    val cleanBottomNav: Boolean = true,
    val verifiedBadgeMode: VerifiedBadgeMode = VerifiedBadgeMode.SHOW_ALL,

    // 5. Media & Link Enhancements
    val enableVideoDownloader: Boolean = true,
    val enableTweetScreenshot: Boolean = true,
    val tweetScreenshotStyle: TweetScreenshotStyle = TweetScreenshotStyle.CLEAN_CARD,
    val tweetScreenshotIncludeMetrics: Boolean = false,
    val forceOrigQualityImages: Boolean = true,
    val unwrapTcoLinks: Boolean = true,
    val openLinksInCustomTab: Boolean = true,
    val disableVideoAutoplay: Boolean = false,

    // 6. Advanced Filtering
    val mutedPatterns: List<String> = emptyList()
) {
    fun isHomeFeedVisible(feed: DetectedHomeFeed): Boolean {
        if (hiddenHomeFeeds.any { it.equals(feed.name, ignoreCase = true) }) return false
        if (feed.kind != HomeFeedKind.SUGGESTED || !stripSuggestedHomeFeeds) return true
        return pinnedHomeFeeds.any { it.equals(feed.name, ignoreCase = true) }
    }

    fun orderedHomeFeeds(detected: List<DetectedHomeFeed>): List<DetectedHomeFeed> {
        val merged = DetectedHomeFeed.normalizeList(detected)
        if (homeFeedOrder.isEmpty()) return merged
        val rank = HashMap<String, Int>(homeFeedOrder.size)
        homeFeedOrder.forEachIndexed { idx, name ->
            rank.putIfAbsent(name.trim().lowercase(), idx)
        }
        return merged.withIndex().sortedWith(
            compareBy<IndexedValue<DetectedHomeFeed>>(
                { rank[it.value.name.trim().lowercase()] ?: Int.MAX_VALUE },
                { it.index }
            )
        ).map { it.value }
    }

    /**
     * Returns true when changing from [previous] to `this` alters GraphQL payload filtering
     * or native WebView media playback settings that benefit from a timeline reload.
     */
    fun requiresReloadComparedTo(previous: TwitterModConfig): Boolean {
        return blockAds != previous.blockAds ||
            stripTimelineCarousels != previous.stripTimelineCarousels ||
            demoteBlueCheckReplies != previous.demoteBlueCheckReplies ||
            hideRetweets != previous.hideRetweets ||
            hideQuoteTweets != previous.hideQuoteTweets ||
            disableVideoAutoplay != previous.disableVideoAutoplay ||
            mutedPatterns != previous.mutedPatterns
    }

    companion object {
        private val allowedRegexFlags = setOf('i', 'm', 's', 'u')

        /**
         * Returns null when [rawPattern] is valid, or a short error message when `/regex/flags` syntax is invalid.
         */
        fun validateMutePattern(rawPattern: String): String? {
            val trimmed = rawPattern.trim()
            if (trimmed.isEmpty()) return "Enter a keyword, domain, or /regex/"
            if (!trimmed.startsWith("/")) return null
            val closeSlash = trimmed.lastIndexOf('/')
            if (closeSlash <= 0) return "Unclosed regex — add a trailing '/'"
            val body = trimmed.substring(1, closeSlash)
            if (body.isEmpty()) return "Regex pattern cannot be empty"
            val flags = trimmed.substring(closeSlash + 1)
            val badFlag = flags.firstOrNull { it !in allowedRegexFlags }
            if (badFlag != null) return "Unsupported regex flag '$badFlag' (use i, m, s, u)"
            return try {
                java.util.regex.Pattern.compile(body)
                null
            } catch (e: PatternSyntaxException) {
                "Invalid regex: ${e.description}"
            }
        }
    }
}
