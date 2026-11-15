package app.overbay.twitter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.overbay.twitter.R
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.ui.theme.TwitterBlue

/**
 * Native Material 3 settings sheet controlling every Twitter WebView mod toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwitterModSettingsSheet(
    config: TwitterModConfig,
    onUpdate: ((TwitterModConfig) -> TwitterModConfig) -> Unit,
    onAddMutePattern: (String) -> Unit,
    onRemoveMutePattern: (String) -> Unit,
    onResetDefaults: () -> Unit,
    onReloadTimeline: () -> Unit,
    onClearCacheAndReload: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
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
                    contentDescription = "Twitter",
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
                        text = "Open via Profile Drawer or long-press the top Bird",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = {
                    onReloadTimeline()
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reload timeline")
                }
                IconButton(onClick = onResetDefaults) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = "Reset defaults")
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SettingsSectionCard(title = "Branding & Identity") {
                        ModSwitchRow(
                            title = "Classic Twitter Branding",
                            subtitle = "Restore Larry the Bird, #1DA1F2 blue accents, and Tweet / Retweet labels.",
                            checked = config.classicTwitterBranding,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(classicTwitterBranding = checked) }
                            },
                            showDivider = false
                        )
                    }
                }

                item {
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
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(removePremiumUpsells = checked) }
                            },
                            showDivider = false
                        )
                    }
                }

                item {
                    SettingsSectionCard(title = "Timeline & Algorithm") {
                        ModSwitchRow(
                            title = "Force Following Tab",
                            subtitle = "Automatically switch to chronological Following on Home load.",
                            checked = config.forceFollowingTab,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(forceFollowingTab = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Hide \"For You\" Tab",
                            subtitle = "Remove the algorithmic For You tab header completely.",
                            checked = config.hideForYouTab,
                            onCheckedChange = { checked -> onUpdate { it.copy(hideForYouTab = checked) } }
                        )
                        ModSwitchRow(
                            title = "Strip Timeline Carousels",
                            subtitle = "Remove Who to Follow, Topics, and Communities modules.",
                            checked = config.stripTimelineCarousels,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(stripTimelineCarousels = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Demote Paid Blue-Check Replies",
                            subtitle = "Hide unfollowed paid-blue replies in tweet threads (keeps OP).",
                            checked = config.demoteBlueCheckReplies,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(demoteBlueCheckReplies = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Prevent Auto-Refresh on Resume",
                            subtitle = "Keep your scroll position when switching back from other apps.",
                            checked = config.disableAutoRefreshOnResume,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(disableAutoRefreshOnResume = checked) }
                            }
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
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(hideQuoteTweets = checked) }
                            },
                            showDivider = false
                        )
                    }
                }

                item {
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
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(hideBookmarkCounts = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Clean 4-Tab Bottom Bar",
                            subtitle = "Keep only Home, Search, Notifications, and Messages.",
                            checked = config.cleanBottomNav,
                            onCheckedChange = { checked -> onUpdate { it.copy(cleanBottomNav = checked) } }
                        )
                        VerifiedBadgeModeRow(
                            selectedMode = config.verifiedBadgeMode,
                            onSelect = { mode -> onUpdate { it.copy(verifiedBadgeMode = mode) } }
                        )
                    }
                }

                item {
                    SettingsSectionCard(title = "Media & Link Enhancements") {
                        ModSwitchRow(
                            title = "1-Tap Video & GIF Downloader",
                            subtitle = "Add a download button saving highest-bitrate MP4s to Downloads/Twitter.",
                            checked = config.enableVideoDownloader,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(enableVideoDownloader = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Force Original Quality Images",
                            subtitle = "Upgrade full-screen photo viewers to name=orig resolution.",
                            checked = config.forceOrigQualityImages,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(forceOrigQualityImages = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Unwrap t.co Tracking Links",
                            subtitle = "Replace t.co redirects with destination URLs (long-press for Archive.today).",
                            checked = config.unwrapTcoLinks,
                            onCheckedChange = { checked -> onUpdate { it.copy(unwrapTcoLinks = checked) } }
                        )
                        ModSwitchRow(
                            title = "Open Links in In-App Tab",
                            subtitle = "Use Chrome Custom Tabs instead of switching to your external browser.",
                            checked = config.openLinksInCustomTab,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(openLinksInCustomTab = checked) }
                            }
                        )
                        ModSwitchRow(
                            title = "Disable Video Autoplay",
                            subtitle = "Pause timeline videos and GIFs until tapped.",
                            checked = config.disableVideoAutoplay,
                            onCheckedChange = { checked ->
                                onUpdate { it.copy(disableVideoAutoplay = checked) }
                            },
                            showDivider = false
                        )
                    }
                }

                item {
                    SettingsSectionCard(
                        title = "Muted Keywords, Domains & Regex (${config.mutedPatterns.size})"
                    ) {
                        MutePatternsSection(
                            patterns = config.mutedPatterns,
                            onAddPattern = onAddMutePattern,
                            onRemovePattern = onRemoveMutePattern
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                onClearCacheAndReload()
                                onDismiss()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
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
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("Reload Feed")
                        }
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }
        }
    }
}
