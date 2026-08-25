package com.slipstream.core.files

import java.io.File

/** One entry in a [DirectoryListing]. */
data class FileEntry(
    val name: String,
    val size: Long,
    val mtimeMs: Long,
    val isDirectory: Boolean,
    val mime: String?,
)

/** A capped directory listing. [truncated] is true when the directory held more than the cap. */
data class DirectoryListing(
    val entries: List<FileEntry>,
    val truncated: Boolean,
)

/** Lists a single directory's immediate children for the remote file browser. */
object FileBrowser {

    const val MAX_ENTRIES = 5000

    private val mimeByExtension = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "mp4" to "video/mp4",
        "mov" to "video/quicktime",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "pdf" to "application/pdf",
        "txt" to "text/plain",
        "md" to "text/markdown",
        "json" to "application/json",
        "xml" to "application/xml",
        "html" to "text/html",
        "htm" to "text/html",
        "csv" to "text/csv",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "apk" to "application/vnd.android.package-archive",
    )

    /**
     * Lists the immediate children of [directory]: directories first, then files, each group
     * sorted case-insensitively by name. Caps the result at [MAX_ENTRIES]; when the directory
     * has more than that, [DirectoryListing.truncated] is true and only the first [MAX_ENTRIES]
     * (by the same sort order) are returned.
     */
    fun list(directory: File): DirectoryListing {
        val children = directory.listFiles()?.toList() ?: emptyList()
        val sorted = children.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
        val truncated = sorted.size > MAX_ENTRIES
        val capped = if (truncated) sorted.subList(0, MAX_ENTRIES) else sorted

        val entries = capped.map { f ->
            val isDir = f.isDirectory
            FileEntry(
                name = f.name,
                size = if (isDir) 0L else f.length(),
                mtimeMs = f.lastModified(),
                isDirectory = isDir,
                mime = if (isDir) null else mimeFor(f.name),
            )
        }
        return DirectoryListing(entries, truncated)
    }

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        return mimeByExtension[ext] ?: "application/octet-stream"
    }
}
