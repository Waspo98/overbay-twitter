package app.overbay.twitter.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.overbay.twitter.webview.TwitterRequestBlocker

/**
 * Bottom sheet displayed when long-pressing an external URL inside a tweet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionSheet(
    url: String,
    useCustomTab: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val cleanUrl = remember(url) { TwitterRequestBlocker.cleanTrackingUrl(url) }
    val hostLabel = remember(cleanUrl) {
        runCatching { cleanUrl.toUri().host?.removePrefix("www.").orEmpty() }
            .getOrDefault("")
            .ifEmpty { "External Link" }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = hostLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = cleanUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)

            ListItem(
                colors = itemColors,
                headlineContent = { Text("Open Clean Link", fontWeight = FontWeight.Medium) },
                supportingContent = {
                    Text(if (useCustomTab) "In-app Chrome Custom Tab" else "Default external browser")
                },
                leadingContent = {
                    Icon(AppIcons.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    TwitterRequestBlocker.openExternalUrl(context, cleanUrl, useCustomTab = useCustomTab)
                }
            )

            ListItem(
                colors = itemColors,
                headlineContent = { Text("Open in Archive.today", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Load archived snapshot via archive.ph") },
                leadingContent = {
                    Icon(AppIcons.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    TwitterRequestBlocker.openExternalUrl(
                        context = context,
                        rawUrl = cleanUrl,
                        useCustomTab = useCustomTab,
                        openInArchiveToday = true
                    )
                }
            )

            ListItem(
                colors = itemColors,
                headlineContent = { Text("Copy Clean URL", fontWeight = FontWeight.Medium) },
                supportingContent = { Text("Strips utm, fbclid, gclid, and tracker parameters") },
                leadingContent = {
                    Icon(AppIcons.ContentCopy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("URL", cleanUrl))
                    Toast.makeText(context, "Clean URL copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            )

            ListItem(
                colors = itemColors,
                headlineContent = { Text("Share Clean Link", fontWeight = FontWeight.Medium) },
                leadingContent = {
                    Icon(AppIcons.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    runCatching {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, cleanUrl)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "Share link"))
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
