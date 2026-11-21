package app.overbay.twitter.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import app.overbay.twitter.R
import app.overbay.twitter.model.DetectedHomeFeed
import app.overbay.twitter.model.SessionFilterStats
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.model.TwitterThemeMode
import app.overbay.twitter.ui.theme.TwitterBlue

/**
 * Native Material 3 Expressive settings sheet controlling every Twitter WebView mod toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwitterModSettingsSheet(
    config: TwitterModConfig,
    detectedHomeFeeds: List<DetectedHomeFeed>,
    sessionStats: SessionFilterStats,
    pendingReload: Boolean,
    onSelectDefaultFeed: (String) -> Unit,
    onSelectThemeMode: (TwitterThemeMode) -> Unit,
    onSelectAutoDarkVariant: (TwitterDarkVariant) -> Unit,
    onToggleFeedVisibility: (DetectedHomeFeed, Boolean) -> Unit,
    onMoveFeed: (String, Int) -> Unit,
    onUpdate: ((TwitterModConfig) -> TwitterModConfig) -> Unit,
    onAddMutePattern: (String) -> Unit,
    onRemoveMutePattern: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onReloadTimeline: () -> Unit,
    onClearCacheAndReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    var confirmReset by remember { mutableStateOf(false) }

    val sheetScrollGuard = remember(scrollState) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                return if (available.y < 0f || scrollState.canScrollBackward) available else Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                return if (available.y < 0f || scrollState.canScrollBackward) available else Velocity.Zero
            }
        }
    }

    if (confirmReset) {
        ResetDefaultsDialog(
            onConfirm = {
                confirmReset = false
                onResetDefaults()
            },
            onDismiss = { confirmReset = false }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_twitter_bird),
                    contentDescription = null,
                    tint = TwitterBlue,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Twitter Mod Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Long-press Home or Bird anytime to open",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    onReloadTimeline()
                    onDismiss()
                }) {
                    Icon(AppIcons.Refresh, contentDescription = "Reload timeline")
                }
                IconButton(onClick = { confirmReset = true }) {
                    Icon(AppIcons.RestartAlt, contentDescription = "Reset defaults")
                }
            }

            AnimatedVisibility(
                visible = pendingReload,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Network filter updated — reload feed to apply",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            onReloadTimeline()
                            onDismiss()
                        }) {
                            Text("Reload Now", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .nestedScroll(sheetScrollGuard)
                    .verticalScroll(scrollState, overscrollEffect = null)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SettingsSectionCard(title = "Branding & Identity") {
                    ModSwitchRow(
                        title = "Classic Twitter Branding",
                        subtitle = "Restore Larry the Bird, #1DA1F2 blue accents, and Tweet / Retweet labels.",
                        checked = config.classicTwitterBranding,
                        onCheckedChange = { checked -> onUpdate { it.copy(classicTwitterBranding = checked) } }
                    )
                    ThemeModeRow(
                        selectedMode = config.themeMode,
                        selectedAutoDarkVariant = config.autoDarkVariant,
                        onSelect = onSelectThemeMode,
                        onSelectAutoDarkVariant = onSelectAutoDarkVariant
                    )
                }

                SettingsSectionCard(title = "Ads, Grok & Upsells") {
                    ModSwitchRow(
                        title = "Block All Ads",
                        subtitle = "Remove promoted tweets at the GraphQL network layer with zero gaps.",
                        checked = config.blockAds,
                        onCheckedChange = { checked -> onUpdate { it.copy(blockAds = checked) } }
                    )
                    ModSwitchRow(
                        title = "Remove All Grok",
                        subtitle = "Strip Grok navigation tabs, tweet buttons, summaries, and drawers.",
                        checked = config.removeGrok,
                        onCheckedChange = { checked -> onUpdate { it.copy(removeGrok = checked) } }
                    )
                    ModSwitchRow(
                        title = "Remove Subscribe & Premium",
                        subtitle = "Strip Subscribe buttons, Get Verified banners, and Premium links.",
                        checked = config.removePremiumUpsells,
                        onCheckedChange = { checked -> onUpdate { it.copy(removePremiumUpsells = checked) } },
                        showDivider = false
                    )
                }

                SettingsSectionCard(title = "Header Feeds") {
                    HomeFeedsSection(
                        config = config,
                        detectedFeeds = detectedHomeFeeds,
                        onSelectDefaultFeed = onSelectDefaultFeed,
                        onToggleStripSuggested = { checked -> onUpdate { it.copy(stripSuggestedHomeFeeds = checked) } },
                        onToggleHideAddButton = { checked -> onUpdate { it.copy(hideAddFeedButton = checked) } },
                        onToggleFeedVisibility = onToggleFeedVisibility,
                        onMoveFeed = onMoveFeed
                    )
                }

                SettingsSectionCard(title = "Timeline & Algorithm") {
                    ModSwitchRow(
                        title = "Strip Timeline Carousels",
                        subtitle = "Remove Who to Follow, Topics, and Communities modules.",
                        checked = config.stripTimelineCarousels,
                        onCheckedChange = { checked -> onUpdate { it.copy(stripTimelineCarousels = checked) } }
                    )
                    ModSwitchRow(
                        title = "Demote Paid Blue-Check Replies",
                        subtitle = "Hide unfollowed paid-blue replies in tweet threads (keeps OP & followed).",
                        checked = config.demoteBlueCheckReplies,
                        onCheckedChange = { checked -> onUpdate { it.copy(demoteBlueCheckReplies = checked) } }
                    )
                    ModSwitchRow(
                        title = "Prevent Auto-Refresh on Resume",
                        subtitle = "Keep your scroll position when switching back from other apps.",
                        checked = config.disableAutoRefreshOnResume,
                        onCheckedChange = { checked -> onUpdate { it.copy(disableAutoRefreshOnResume = checked) } }
                    )
                    ModSwitchRow(
                        title = "Hide Retweets",
                        subtitle = "Filter reposted tweets out of your timeline.",
                        checked = config.hideRetweets,
                        onCheckedChange = { checked -> onUpdate { it.copy(hideRetweets = checked) } }
                    )
                    ModSwitchRow(
                        title = "Hide Quote Tweets",
                        subtitle = "Filter quote tweets out of your timeline.",
                        checked = config.hideQuoteTweets,
                        onCheckedChange = { checked -> onUpdate { it.copy(hideQuoteTweets = checked) } },
                        showDivider = false
                    )
                }

                SettingsSectionCard(title = "UI & Metric Cleanup") {
                    ModSwitchRow(
                        title = "Hide View Counts",
                        subtitle = "Remove analytics view counts from tweet action bars.",
                        checked = config.hideViewCounts,
                        onCheckedChange = { checked -> onUpdate { it.copy(hideViewCounts = checked) } }
                    )
                    ModSwitchRow(
                        title = "Hide Bookmark Counts",
                        subtitle = "Hide bookmark counters for a cleaner 4-icon look.",
                        checked = config.hideBookmarkCounts,
                        onCheckedChange = { checked -> onUpdate { it.copy(hideBookmarkCounts = checked) } }
                    )
                    ModSwitchRow(
                        title = "Clean 4-Tab Bottom Bar",
                        subtitle = "Keep primary tabs while hiding Grok, Communities, Spaces, and Jobs.",
                        checked = config.cleanBottomNav,
                        onCheckedChange = { checked -> onUpdate { it.copy(cleanBottomNav = checked) } }
                    )
                    VerifiedBadgeModeRow(
                        selectedMode = config.verifiedBadgeMode,
                        onSelect = { mode -> onUpdate { it.copy(verifiedBadgeMode = mode) } }
                    )
                }

                SettingsSectionCard(title = "Media & Link Enhancements") {
                    ModSwitchRow(
                        title = "1-Tap Media Downloader",
                        subtitle = "Save highest-bitrate MP4s & full-res photos to Downloads/Twitter.",
                        checked = config.enableVideoDownloader,
                        onCheckedChange = { checked -> onUpdate { it.copy(enableVideoDownloader = checked) } }
                    )
                    ModSwitchRow(
                        title = "Force Original Quality Images",
                        subtitle = "Upgrade full-screen photo viewers to name=orig resolution.",
                        checked = config.forceOrigQualityImages,
                        onCheckedChange = { checked -> onUpdate { it.copy(forceOrigQualityImages = checked) } }
                    )
                    ModSwitchRow(
                        title = "Unwrap t.co Tracking Links",
                        subtitle = "Replace t.co redirects with clean destination URLs (long-press for options).",
                        checked = config.unwrapTcoLinks,
                        onCheckedChange = { checked -> onUpdate { it.copy(unwrapTcoLinks = checked) } }
                    )
                    ModSwitchRow(
                        title = "Open Links in In-App Tab",
                        subtitle = "Use Chrome Custom Tabs instead of switching to your external browser.",
                        checked = config.openLinksInCustomTab,
                        onCheckedChange = { checked -> onUpdate { it.copy(openLinksInCustomTab = checked) } }
                    )
                    ModSwitchRow(
                        title = "Disable Video Autoplay",
                        subtitle = "Pause timeline videos and GIFs until tapped.",
                        checked = config.disableVideoAutoplay,
                        onCheckedChange = { checked -> onUpdate { it.copy(disableVideoAutoplay = checked) } },
                        showDivider = false
                    )
                }

                SettingsSectionCard(title = "Muted Keywords, Domains & Regex (${config.mutedPatterns.size})") {
                    MutePatternsSection(
                        patterns = config.mutedPatterns,
                        onAddPattern = onAddMutePattern,
                        onRemovePattern = onRemoveMutePattern
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            onClearCacheAndReload()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(AppIcons.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Clear Cache")
                    }
                    FilledTonalButton(
                        onClick = {
                            onReloadTimeline()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Icon(AppIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Reload Feed")
                    }
                }
                ModSettingsFooter(sessionStats = sessionStats)
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
