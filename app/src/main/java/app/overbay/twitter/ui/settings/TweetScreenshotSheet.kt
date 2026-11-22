package app.overbay.twitter.ui.settings

import android.app.Activity
import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.overbay.twitter.model.TweetScreenshotStyle
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.screenshot.CapturedTweetScreenshot
import app.overbay.twitter.screenshot.TweetScreenshotCapturer
import app.overbay.twitter.screenshot.TweetScreenshotExporter
import app.overbay.twitter.screenshot.TweetScreenshotFormatter
import app.overbay.twitter.ui.theme.TwitterBlue
import app.overbay.twitter.webview.TwitterWebController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects [TwitterWebController] screenshot requests to [TweetScreenshotCapturer] and hosts
 * the live-formatted [TweetScreenshotSheet] preview modal.
 */
@Composable
fun TweetScreenshotHost(
    activity: Activity?,
    controller: TwitterWebController,
    config: TwitterModConfig,
    fallbackSurfaceColorInt: Int,
    onUpdateConfig: ((TwitterModConfig) -> TwitterModConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestActivity by rememberUpdatedState(activity)
    val latestFallbackColor by rememberUpdatedState(fallbackSurfaceColorInt)

    var isCapturing by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<CapturedTweetScreenshot?>(null) }

    DisposableEffect(controller) {
        controller.onRequestTweetScreenshot = {
            if (!isCapturing) {
                val hostActivity = latestActivity
                val webView = controller.webView
                if (hostActivity != null && webView != null) {
                    isCapturing = true
                    webView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    scope.launch {
                        try {
                            TweetScreenshotCapturer.capture(
                                activity = hostActivity,
                                webView = webView,
                                fallbackSurfaceColorInt = latestFallbackColor
                            ).onSuccess { result ->
                                captured = result
                            }.onFailure { err ->
                                Toast.makeText(
                                    context,
                                    err.message ?: "Could not capture tweet",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } finally {
                            isCapturing = false
                        }
                    }
                }
            }
        }
        onDispose { controller.onRequestTweetScreenshot = null }
    }

    captured?.let { shot ->
        TweetScreenshotSheet(
            captured = shot,
            style = config.tweetScreenshotStyle,
            includeMetrics = config.tweetScreenshotIncludeMetrics,
            onSelectStyle = { nextStyle ->
                onUpdateConfig { it.copy(tweetScreenshotStyle = nextStyle) }
            },
            onToggleMetrics = { nextMetrics ->
                onUpdateConfig { it.copy(tweetScreenshotIncludeMetrics = nextMetrics) }
            },
            onDismiss = { captured = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TweetScreenshotSheet(
    captured: CapturedTweetScreenshot,
    style: TweetScreenshotStyle,
    includeMetrics: Boolean,
    onSelectStyle: (TweetScreenshotStyle) -> Unit,
    onToggleMetrics: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var formattedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    LaunchedEffect(captured, style, includeMetrics) {
        formattedBitmap = withContext(Dispatchers.Default) {
            TweetScreenshotFormatter.format(
                captured = captured,
                style = style,
                includeMetrics = includeMetrics
            )
        }
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Tweet Screenshot",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (captured.authorHandle.isNotBlank()) {
                        Text(
                            text = "@${captured.authorHandle}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(AppIcons.Close, contentDescription = "Close preview")
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 120.dp, max = 380.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                val preview = formattedBitmap
                if (preview == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = TwitterBlue,
                            strokeWidth = 2.5.dp
                        )
                    }
                } else {
                    val imageBitmap = remember(preview) { preview.asImageBitmap() }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = imageBitmap,
                            contentDescription = "Formatted tweet screenshot preview",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.Transparent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TweetScreenshotStyle.entries.forEach { option ->
                    val selected = style == option
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectStyle(option) },
                        label = { Text(option.label, style = MaterialTheme.typography.labelMedium) },
                        colors = chipColors
                    )
                }
                FilterChip(
                    selected = includeMetrics,
                    onClick = { onToggleMetrics(!includeMetrics) },
                    label = { Text("Metrics", style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = if (includeMetrics) {
                        {
                            Icon(
                                imageVector = AppIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        null
                    },
                    colors = chipColors
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            val activeBitmap = formattedBitmap
            val actionsEnabled = activeBitmap != null && !isExporting

            fun runExport(errorFallback: String, action: suspend (Bitmap) -> Result<String?>) {
                val bmp = activeBitmap ?: return
                isExporting = true
                scope.launch {
                    action(bmp).onSuccess { toastMsg ->
                        if (!toastMsg.isNullOrBlank()) Toast.makeText(context, toastMsg, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: errorFallback, Toast.LENGTH_SHORT).show()
                    }
                    isExporting = false
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        runExport("Share failed") { bmp ->
                            TweetScreenshotExporter.share(context, bmp, captured.authorHandle, captured.tweetId).map { null }
                        }
                    },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1.25f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TwitterBlue, contentColor = Color.White)
                ) {
                    Icon(AppIcons.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Share", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                }

                FilledTonalButton(
                    onClick = {
                        runExport("Copy failed") { bmp ->
                            TweetScreenshotExporter.copyToClipboard(context, bmp, captured.authorHandle, captured.tweetId)
                                .map { "Copied to clipboard" }
                        }
                    },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(AppIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Copy", fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                }

                FilledTonalButton(
                    onClick = {
                        runExport("Save failed") { bmp ->
                            TweetScreenshotExporter.saveToPhotos(context, bmp, captured.authorHandle, captured.tweetId)
                        }
                    },
                    enabled = actionsEnabled,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(AppIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("Save", fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
