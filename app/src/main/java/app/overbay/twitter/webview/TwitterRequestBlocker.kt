package app.overbay.twitter.webview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Network-layer ad/telemetry filter and external link router for Twitter WebView.
 */
object TwitterRequestBlocker {

    private val blockedHosts = setOf(
        "ads-twitter.com",
        "ads-api.twitter.com",
        "static.ads-twitter.com",
        "analytics.twitter.com",
        "pixel.twitter.com",
        "scribe.twitter.com"
    )

    private val blockedPathFragments = listOf(
        "/i/api/1.1/jot/",
        "/1.1/jot/",
        "/i/adsct",
        "/ClientEvent"
    )

    private val internalHosts = setOf(
        "x.com",
        "twitter.com"
    )

    fun shouldBlockTelemetryOrAd(urlString: String): Boolean {
        val uri = runCatching { URI.create(urlString) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (isBlockedHost(host)) {
            return true
        }
        val path = uri.rawPath.orEmpty()
        return blockedPathFragments.any { path.contains(it, ignoreCase = true) }
    }

    fun isBlockedHost(host: String): Boolean {
        val normalized = host.lowercase()
        return blockedHosts.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun isInternalTwitterHost(host: String): Boolean {
        val normalized = host.lowercase()
        return internalHosts.any { normalized == it || normalized.endsWith(".$it") }
    }

    fun emptyJsonResponse(): WebResourceResponse {
        return WebResourceResponse(
            "application/json",
            "UTF-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    fun openExternalUrl(
        context: Context,
        rawUrl: String,
        useCustomTab: Boolean,
        openInArchiveToday: Boolean = false
    ) {
        val target = if (openInArchiveToday) {
            "https://archive.ph/newest/$rawUrl"
        } else {
            rawUrl
        }
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return

        if (useCustomTab) {
            val launched = runCatching {
                val colorParams = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(0xFF15202B.toInt())
                    .build()
                CustomTabsIntent.Builder()
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
            }.isSuccess
            if (launched) return
        }

        runCatching {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(viewIntent)
        }
    }
}
