package app.overbay.twitter.webview

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceResponse
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import java.io.ByteArrayInputStream

/**
 * Zero-allocation network-layer ad/telemetry filter and external link router for Twitter WebView.
 */
object TwitterRequestBlocker {

    private val blockedHosts = arrayOf(
        "ads-twitter.com",
        "ads.twitter.com",
        "ads.x.com",
        "ads-api.twitter.com",
        "static.ads-twitter.com",
        "analytics.twitter.com",
        "analytics.x.com",
        "pixel.twitter.com",
        "scribe.twitter.com"
    )

    private val fastPassCdnDomains = arrayOf(
        "twimg.com"
    )

    private val blockedPathFragments = arrayOf(
        "/i/api/1.1/jot/",
        "/1.1/jot/",
        "/i/adsct",
        "/ClientEvent"
    )

    private val internalHosts = arrayOf(
        "x.com",
        "twitter.com"
    )

    private val trackingQueryParams = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
        "fbclid", "gclid", "dclid", "msclkid", "mc_cid", "mc_eid", "igshid", "igsh",
        "_hsenc", "_hsmi", "vero_id", "mkt_tok"
    )

    private val mobileBeforeSafariRegex = Regex("""\s+Mobile(?=\s+Safari/)""")
    private val mobileWordRegex = Regex("""\bMobile\b""")

    /**
     * Evaluates telemetry/ad URLs on Chromium's IO thread without allocating URI or substring objects.
     */
    fun shouldBlockTelemetryOrAd(urlString: String): Boolean {
        val schemeSep = urlString.indexOf("://")
        if (schemeSep <= 0) return false
        val hostStart = schemeSep + 3
        val len = urlString.length
        if (hostStart >= len) return false

        var pathStart = len
        var pathEnd = len
        for (i in hostStart until len) {
            val ch = urlString[i]
            if (ch == '/' && pathStart == len) {
                pathStart = i
            } else if (ch == '?' || ch == '#') {
                pathEnd = i
                if (pathStart == len) pathStart = i
                break
            }
        }

        var hostEnd = pathStart
        for (i in hostStart until pathStart) {
            if (urlString[i] == ':') {
                hostEnd = i
                break
            }
        }
        if (hostEnd <= hostStart) return false

        for (cdn in fastPassCdnDomains) {
            if (regionMatchesDomain(urlString, hostStart, hostEnd, cdn)) return false
        }
        for (blocked in blockedHosts) {
            if (regionMatchesDomain(urlString, hostStart, hostEnd, blocked)) return true
        }

        val pathLen = pathEnd - pathStart
        if (pathLen <= 1) return false
        for (frag in blockedPathFragments) {
            val idx = urlString.indexOf(frag, startIndex = pathStart, ignoreCase = true)
            if (idx >= pathStart && idx + frag.length <= pathEnd) return true
        }
        return false
    }

    fun isBlockedHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return blockedHosts.any { regionMatchesDomain(host, 0, host.length, it) }
    }

    fun isInternalTwitterHost(host: String): Boolean {
        if (host.isEmpty() || isBlockedHost(host)) return false
        return internalHosts.any { regionMatchesDomain(host, 0, host.length, it) }
    }

    private fun regionMatchesDomain(
        text: String,
        start: Int,
        end: Int,
        domain: String
    ): Boolean {
        val hostLen = end - start
        val domLen = domain.length
        if (hostLen < domLen) return false
        if (hostLen == domLen) {
            return text.regionMatches(start, domain, 0, domLen, ignoreCase = true)
        }
        val dotIndex = end - domLen - 1
        return text[dotIndex] == '.' &&
            text.regionMatches(dotIndex + 1, domain, 0, domLen, ignoreCase = true)
    }

    fun cleanTrackingUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val qIndex = trimmed.indexOf('?')
        if (qIndex < 0) return trimmed
        val hashIndex = trimmed.indexOf('#')
        if (hashIndex in 0 until qIndex) return trimmed

        val queryEnd = if (hashIndex > qIndex) hashIndex else trimmed.length
        val rawPairs = trimmed.substring(qIndex + 1, queryEnd).split('&')
        val kept = rawPairs.filter { pair ->
            pair.isNotEmpty() && pair.substringBefore('=').lowercase() !in trackingQueryParams
        }
        if (kept.size == rawPairs.size) return trimmed

        val base = trimmed.substring(0, qIndex)
        val fragment = if (hashIndex > qIndex) trimmed.substring(hashIndex) else ""
        return if (kept.isEmpty()) {
            "$base$fragment"
        } else {
            "$base?${kept.joinToString("&")}$fragment"
        }
    }

    fun normalizeAcceptTypes(
        acceptTypes: Array<String>?,
        extToMime: (String) -> String?
    ): Array<String> {
        val normalized = acceptTypes.orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { token ->
                when {
                    token.startsWith(".") -> extToMime(token.removePrefix(".").lowercase())
                    token.contains('/') -> token
                    else -> null
                }
            }
            .distinct()
        return if (normalized.isNotEmpty()) normalized.toTypedArray() else arrayOf("image/*", "video/*")
    }

    fun buildEffectiveUserAgent(baseUserAgent: String, preferDesktopUserAgent: Boolean): String {
        val cleaned = baseUserAgent
            .replace("; wv", "")
            .replace("Version/4.0 ", "")
        return if (preferDesktopUserAgent) {
            cleaned.replace(mobileBeforeSafariRegex, "")
        } else if (mobileWordRegex.containsMatchIn(cleaned)) {
            cleaned
        } else if (cleaned.contains("Safari/")) {
            cleaned.replaceFirst("Safari/", "Mobile Safari/")
        } else {
            "$cleaned Mobile"
        }
    }

    fun isAllowedOriginUrl(origin: String?): Boolean {
        if (origin.isNullOrBlank() || !origin.startsWith("https://", ignoreCase = true)) return false
        val hostStart = 8
        var hostEnd = origin.length
        for (i in hostStart until origin.length) {
            val ch = origin[i]
            if (ch == '/' || ch == ':' || ch == '?' || ch == '#') {
                hostEnd = i
                break
            }
        }
        if (hostEnd <= hostStart) return false
        return internalHosts.any { regionMatchesDomain(origin, hostStart, hostEnd, it) } &&
            blockedHosts.none { regionMatchesDomain(origin, hostStart, hostEnd, it) }
    }

    fun isJsonTelemetryUrl(urlString: String): Boolean {
        return urlString.contains("/jot/", ignoreCase = true) ||
            urlString.contains("/ClientEvent", ignoreCase = true)
    }

    fun emptyJsonResponse(
        origin: String? = null,
        isJsonEndpoint: Boolean = true
    ): WebResourceResponse {
        val headers = if (isAllowedOriginUrl(origin)) {
            mapOf(
                "Access-Control-Allow-Origin" to origin!!,
                "Access-Control-Allow-Credentials" to "true",
                "Vary" to "Origin"
            )
        } else {
            mapOf("Access-Control-Allow-Origin" to "*")
        }
        return if (isJsonEndpoint) {
            WebResourceResponse(
                "application/json",
                "UTF-8",
                200,
                "OK",
                headers,
                ByteArrayInputStream(EMPTY_JSON_BYTES)
            )
        } else {
            WebResourceResponse(
                "text/plain",
                "UTF-8",
                204,
                "No Content",
                headers,
                ByteArrayInputStream(EMPTY_BYTES)
            )
        }
    }

    fun openExternalUrl(
        context: Context,
        rawUrl: String,
        useCustomTab: Boolean,
        openInArchiveToday: Boolean = false,
        toolbarColor: Int = 0xFF15202B.toInt()
    ) {
        val cleaned = cleanTrackingUrl(rawUrl)
        val target = if (openInArchiveToday) {
            "https://archive.ph/newest/${cleaned.substringBefore('#')}"
        } else {
            cleaned
        }
        val uri = runCatching { target.toUri() }.getOrNull() ?: return

        if (useCustomTab) {
            val launched = runCatching {
                val r = (toolbarColor shr 16) and 0xFF
                val g = (toolbarColor shr 8) and 0xFF
                val b = toolbarColor and 0xFF
                val isLight = (0.2126 * r + 0.7152 * g + 0.0722 * b) > 128.0
                val colorParams = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor)
                    .setNavigationBarColor(toolbarColor)
                    .build()
                CustomTabsIntent.Builder()
                    .setColorScheme(
                        if (isLight) CustomTabsIntent.COLOR_SCHEME_LIGHT else CustomTabsIntent.COLOR_SCHEME_DARK
                    )
                    .setDefaultColorSchemeParams(colorParams)
                    .setShowTitle(true)
                    .build()
                    .launchUrl(context, uri)
            }.isSuccess
            if (launched) return
        }

        runCatching {
            val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(viewIntent)
        }
    }

    private val EMPTY_BYTES = ByteArray(0)
    private val EMPTY_JSON_BYTES = "{}".toByteArray(Charsets.UTF_8)
}
