package com.slipstream.app.ui.send

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A [Uri] resolved down to a real filesystem path [PeerController.push] can read, plus the
 * display name to use as the push's remote name and the size for the queued item's listing. */
data class ResolvedFile(val localPath: String, val displayName: String, val size: Long)

/**
 * Resolves a picked-or-shared [Uri] to a [ResolvedFile]. `content://` URIs (the share sheet and
 * modern file pickers) don't always map onto a direct filesystem path under scoped storage, so
 * this is the seam [ContentUriResolver] uses to copy bytes into app cache when no direct path is
 * resolvable — see its class doc for the direct-path-first strategy.
 */
interface UriResolver {
    suspend fun resolve(uri: Uri): ResolvedFile
}

/** Handles `file://` URIs directly — already a real filesystem path, so no copy is needed. This
 * is also what unit tests use by default (no [Context] required), since a local picker or an
 * already-resolved share extra can hand back one of these. */
object FileUriResolver : UriResolver {
    override suspend fun resolve(uri: Uri): ResolvedFile {
        val path = uri.path ?: throw IllegalArgumentException("Uri has no path: $uri")
        val file = File(path)
        return ResolvedFile(localPath = file.path, displayName = file.name, size = file.length())
    }
}

/**
 * The production [UriResolver] for `content://` URIs from Android's share sheet or a
 * [android.content.Intent.ACTION_OPEN_DOCUMENT] picker. Prefers a direct filesystem path — read
 * from `MediaStore`'s `DATA` column, which still resolves for many `content://` URIs despite
 * scoped storage's general restriction on raw paths — over copying bytes, since a direct path
 * avoids a wasteful double-copy (the receiver's `pushOffer` handler already streams straight off
 * disk). Falls back to copying into [Context.getCacheDir] only when no direct path is available.
 */
class ContentUriResolver(
    private val context: Context,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UriResolver {

    override suspend fun resolve(uri: Uri): ResolvedFile = withContext(dispatcher) {
        if (uri.scheme == "file") return@withContext FileUriResolver.resolve(uri)

        directPathFor(uri)?.let { path ->
            val file = File(path)
            if (file.isFile) return@withContext ResolvedFile(file.path, file.name, file.length())
        }
        copyToCache(uri)
    }

    private fun directPathFor(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun copyToCache(uri: Uri): ResolvedFile {
        val displayName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        val destination = File(File(context.cacheDir, "send-cache/${UUID.randomUUID()}"), displayName)
        destination.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Could not open $uri")
        input.use { source -> destination.outputStream().use { sink -> source.copyTo(sink) } }
        return ResolvedFile(destination.path, displayName, destination.length())
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    } catch (e: Exception) {
        null
    }
}
