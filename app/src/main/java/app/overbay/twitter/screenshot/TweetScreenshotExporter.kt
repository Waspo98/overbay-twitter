package app.overbay.twitter.screenshot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles writing formatted tweet screenshot PNGs to app cache for FileProvider sharing/clipboard
 * copy, and saving permanently to `Pictures/Twitter` via MediaStore with zero runtime permissions.
 */
object TweetScreenshotExporter {

    private const val CACHE_DIR_NAME = "screenshots"
    private const val MAX_CACHED_FILES = 6
    private val UNSAFE_CHARS_RE = Regex("[^A-Za-z0-9_-]")

    private inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (ce: CancellationException) {
        throw ce
    } catch (err: Throwable) {
        Result.failure(err)
    }

    suspend fun share(
        context: Context,
        bitmap: Bitmap,
        authorHandle: String,
        tweetId: String
    ): Result<Unit> = runSuspendCatching {
        val uri = writeTempPng(context, bitmap, authorHandle, tweetId)
        withContext(Dispatchers.Main) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Tweet Screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share tweet screenshot").apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        }
    }

    suspend fun copyToClipboard(
        context: Context,
        bitmap: Bitmap,
        authorHandle: String,
        tweetId: String
    ): Result<Unit> = runSuspendCatching {
        val uri = writeTempPng(context, bitmap, authorHandle, tweetId)
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: throw IllegalStateException("Clipboard unavailable")
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Tweet Screenshot", uri))
        }
    }

    suspend fun saveToPhotos(
        context: Context,
        bitmap: Bitmap,
        authorHandle: String,
        tweetId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runSuspendCatching {
            val fileName = "${buildFilenameBase(authorHandle, tweetId)}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Twitter")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Could not create MediaStore entry")
                try {
                    resolver.openOutputStream(uri)?.use { stream ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw IllegalStateException("PNG compression failed")
                        }
                    } ?: throw IllegalStateException("Could not open MediaStore stream")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (err: Throwable) {
                    runCatching { resolver.delete(uri, null, null) }
                    throw err
                }
                "Saved to Pictures/Twitter"
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Twitter")
                dir.mkdirs()
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { stream ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw IllegalStateException("PNG compression failed")
                    }
                }
                "Saved screenshot"
            }
        }
    }

    private suspend fun writeTempPng(
        context: Context,
        bitmap: Bitmap,
        authorHandle: String,
        tweetId: String
    ): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        pruneOldCacheFiles(dir)
        val fileName = "${buildFilenameBase(authorHandle, tweetId)}.png"
        val outFile = File(dir, fileName)
        FileOutputStream(outFile).use { stream ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                throw IllegalStateException("PNG compression failed")
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
    }

    private fun pruneOldCacheFiles(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        if (files.size < MAX_CACHED_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - (MAX_CACHED_FILES - 1))
            .forEach { runCatching { it.delete() } }
    }

    internal fun buildFilenameBase(
        authorHandle: String,
        tweetId: String,
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val cleanHandle = authorHandle.trim().removePrefix("@")
            .replace(UNSAFE_CHARS_RE, "")
            .take(24)
        val cleanId = tweetId.trim().replace(UNSAFE_CHARS_RE, "").take(24)
        return buildString {
            append("tweet")
            if (cleanHandle.isNotEmpty()) {
                append('_')
                append(cleanHandle)
            }
            if (cleanId.isNotEmpty()) {
                append('_')
                append(cleanId)
            }
            append('_')
            append(timestampMs)
        }
    }
}
