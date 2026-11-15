package app.overbay.twitter.bridge

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap

/**
 * Enqueues video, GIF, and original-resolution image downloads through Android DownloadManager.
 */
object MediaDownloader {

    private val allowedHosts = setOf("video.twimg.com", "pbs.twimg.com", "ton.twimg.com")

    fun enqueue(context: Context, rawUrl: String, filenameHint: String): Result<Long> {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid media URL"))
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || allowedHosts.none { host == it || host.endsWith(".$it") }) {
            return Result.failure(IllegalArgumentException("Untrusted media host: $host"))
        }

        val safeName = sanitizeFilename(filenameHint, uri)
        val mimeType = resolveMimeType(safeName)
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return Result.failure(IllegalStateException("DownloadManager unavailable"))

        val request = DownloadManager.Request(uri).apply {
            setTitle(safeName)
            setDescription("Downloading from Twitter")
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader("Referer", "https://x.com/")
            CookieManager.getInstance().getCookie(rawUrl)?.let { cookie ->
                if (cookie.isNotBlank()) addRequestHeader("Cookie", cookie)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "Twitter/$safeName"
                )
            } else {
                setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    "Twitter/$safeName"
                )
            }
        }

        return runCatching { dm.enqueue(request) }
    }

    private fun sanitizeFilename(hint: String, uri: Uri): String {
        val fallbackExt = when {
            uri.path?.contains(".mp4", ignoreCase = true) == true -> "mp4"
            uri.getQueryParameter("format") == "png" -> "png"
            else -> "jpg"
        }
        val cleaned = hint.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        val base = cleaned.ifBlank { "twitter_${System.currentTimeMillis()}.$fallbackExt" }
        return if (base.contains('.')) base else "$base.$fallbackExt"
    }

    private fun resolveMimeType(filename: String): String {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "mp4" -> "video/mp4"
            "png" -> "image/png"
            else -> "image/jpeg"
        }
    }
}
