package app.overbay.twitter.model

import kotlinx.serialization.Serializable

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
 * Persistent configuration for all Twitter WebView interception, branding, and UI mod layers.
 */
@Serializable
data class TwitterModConfig(
    // 1. Branding & Identity
    val classicTwitterBranding: Boolean = true,

    // 2. Ads, Grok & Upsells
    val blockAds: Boolean = true,
    val removeGrok: Boolean = true,
    val removePremiumUpsells: Boolean = true,

    // 3. Timeline & Algorithmic Control
    val forceFollowingTab: Boolean = true,
    val hideForYouTab: Boolean = false,
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
    val forceOrigQualityImages: Boolean = true,
    val unwrapTcoLinks: Boolean = true,
    val openLinksInCustomTab: Boolean = true,
    val disableVideoAutoplay: Boolean = false,

    // 6. Advanced Filtering
    val mutedPatterns: List<String> = emptyList()
) {
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
}
