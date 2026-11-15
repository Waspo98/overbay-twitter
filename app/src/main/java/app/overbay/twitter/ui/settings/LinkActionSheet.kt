package app.overbay.twitter.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.overbay.twitter.webview.TwitterRequestBlocker

/**
 * Bottom sheet displayed when long-pressing an unwrapped external URL inside a tweet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkActionSheet(
    url: String,
    useCustomTab: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HorizontalDivider()

            ListItem(
                headlineContent = { Text("Open Clean Link") },
                supportingContent = {
                    Text(if (useCustomTab) "In-app Chrome Custom Tab" else "Default external browser")
                },
                leadingContent = {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    TwitterRequestBlocker.openExternalUrl(context, url, useCustomTab = useCustomTab)
                }
            )

            ListItem(
                headlineContent = { Text("Open in Archive.today") },
                supportingContent = { Text("Load archived snapshot via archive.ph") },
                leadingContent = {
                    Icon(Icons.Rounded.Archive, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    onDismiss()
                    TwitterRequestBlocker.openExternalUrl(
                        context = context,
                        rawUrl = url,
                        useCustomTab = useCustomTab,
                        openInArchiveToday = true
                    )
                }
            )

            ListItem(
                headlineContent = { Text("Copy Clean URL") },
                leadingContent = {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                },
                modifier = Modifier.clickable {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("URL", url))
                    Toast.makeText(context, "Clean URL copied", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
