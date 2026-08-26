package com.slipstream.app.ui.send

import android.net.Uri
import android.os.Environment
import java.io.File

/**
 * Resolves a `content://...tree/...` [Uri] from [android.content.Intent.ACTION_OPEN_DOCUMENT_TREE]
 * (the folder picker) to a real filesystem [File], for [SendViewModel.onPathsSelected].
 *
 * `DocumentFile` deliberately has no path-on-disk accessor under scoped storage, but a tree URI's
 * document id still encodes `<volume>:<relativePath>` (e.g. `primary:DCIM/Movies`), and the
 * `primary` volume is always `Environment.getExternalStorageDirectory()` — so that one case (by
 * far the common one: internal shared storage) resolves to a direct path with no copying. A
 * secondary SD-card volume's id is a per-device UUID this class has no reliable way to map back
 * to `/storage/<uuid>` without `StorageManager` APIs this task doesn't add, so that case (and any
 * other unresolvable tree) returns null — a known gap, not solved here — and the caller should
 * show an error rather than attempt a byte-by-byte SAF copy of an entire folder tree.
 */
object TreePathResolver {

    fun resolve(treeUri: Uri): File? {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, relativePath) = parts
        val root = when (volume) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> return null
        }
        val resolved = if (relativePath.isEmpty()) root else File(root, relativePath)
        return resolved.takeIf { it.isDirectory }
    }
}
