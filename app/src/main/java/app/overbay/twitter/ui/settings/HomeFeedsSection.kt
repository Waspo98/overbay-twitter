package app.overbay.twitter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.overbay.twitter.model.DetectedHomeFeed
import app.overbay.twitter.model.HomeFeedKind
import app.overbay.twitter.model.TwitterModConfig

/**
 * Controls `/home` header feed tabs: auto-stripping suggested feeds, toggling individual feeds,
 * customizing tab order, and picking the default launch feed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeFeedsSection(
    config: TwitterModConfig,
    detectedFeeds: List<DetectedHomeFeed>,
    onSelectDefaultFeed: (String) -> Unit,
    onToggleStripSuggested: (Boolean) -> Unit,
    onToggleHideAddButton: (Boolean) -> Unit,
    onToggleFeedVisibility: (DetectedHomeFeed, Boolean) -> Unit,
    onMoveFeed: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val orderedFeeds = remember(config.homeFeedOrder, detectedFeeds) {
        config.orderedHomeFeeds(detectedFeeds)
    }
    val visibleFeeds = remember(
        orderedFeeds,
        config.hiddenHomeFeeds,
        config.pinnedHomeFeeds,
        config.stripSuggestedHomeFeeds
    ) {
        orderedFeeds.filter { config.isHomeFeedVisible(it) }
    }
    val visibleCount = visibleFeeds.size

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Default Launch Feed",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Automatically selected when opening Home.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                visibleFeeds.forEach { feed ->
                    FilterChip(
                        selected = config.defaultHomeFeed.equals(feed.name, ignoreCase = true),
                        onClick = { onSelectDefaultFeed(feed.name) },
                        label = { Text(feed.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        ModSwitchRow(
            title = "Strip X Suggested Feeds",
            subtitle = "Hide algorithmic sports & topic tabs (NFL, NBA, etc.) while keeping For you, Following, and your Lists.",
            checked = config.stripSuggestedHomeFeeds,
            onCheckedChange = onToggleStripSuggested
        )

        ModSwitchRow(
            title = "Hide '+' Feeds Button",
            subtitle = "Remove the trailing + button in the home header bar.",
            checked = config.hideAddFeedButton,
            onCheckedChange = onToggleHideAddButton
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "Feed Order & Visibility",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "Detected tabs from Home. Reorder with arrows or toggle individual feeds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        orderedFeeds.forEachIndexed { index, feed ->
            val isVisible = config.isHomeFeedVisible(feed)
            val statusNote = when {
                !isVisible && feed.kind == HomeFeedKind.SUGGESTED && config.stripSuggestedHomeFeeds ->
                    "${feed.kind.label} • Auto-hidden"
                !isVisible -> "${feed.kind.label} • Hidden"
                else -> feed.kind.label
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = feed.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusNote,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onMoveFeed(feed.name, -1) },
                    enabled = index > 0,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.KeyboardArrowUp,
                        contentDescription = "Move ${feed.name} up"
                    )
                }
                IconButton(
                    onClick = { onMoveFeed(feed.name, 1) },
                    enabled = index < orderedFeeds.lastIndex,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.KeyboardArrowDown,
                        contentDescription = "Move ${feed.name} down"
                    )
                }
                Switch(
                    checked = isVisible,
                    enabled = !(isVisible && visibleCount <= 1),
                    onCheckedChange = { checked -> onToggleFeedVisibility(feed, checked) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
            if (index < orderedFeeds.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}
