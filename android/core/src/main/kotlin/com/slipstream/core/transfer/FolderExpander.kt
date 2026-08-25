package com.slipstream.core.transfer

import java.io.File

/**
 * Flattens a directory tree into a flat list of `/`-separated relative paths, per spec §7:
 * "A folder transfer is expanded to a flat file list with relative paths on the sending side...
 * Directory structure is recreated on the receiver from the relative paths. Empty directories
 * are preserved."
 *
 * Non-empty directories need no explicit entry - their existence is implied by the files (or
 * nested empty-directory entries) beneath them, which the receiver recreates via `mkdirs()` on
 * each relative path. An empty directory has nothing to imply it, so it gets an explicit
 * [Entry] with [Entry.isDirectory] set.
 */
object FolderExpander {

    data class Entry(
        val relativePath: String,
        val isDirectory: Boolean,
        val size: Long,
    )

    /** Walks [root] and returns its contents as [Entry] values with `/`-separated relative paths. */
    fun expand(root: File): List<Entry> {
        require(root.isDirectory) { "${root.path} is not a directory" }
        val entries = mutableListOf<Entry>()
        walk(root, "", entries)
        return entries
    }

    private fun walk(dir: File, prefix: String, out: MutableList<Entry>) {
        val children = dir.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (children.isEmpty()) {
            if (prefix.isNotEmpty()) out.add(Entry(prefix, isDirectory = true, size = 0))
            return
        }
        for (child in children) {
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) {
                walk(child, rel, out)
            } else {
                out.add(Entry(rel, isDirectory = false, size = child.length()))
            }
        }
    }
}
