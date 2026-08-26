package com.slipstream.app.ui.send

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TreePathResolverTest {

    @Test
    fun `a primary-volume tree uri resolves to a real path under external storage`() {
        val expected = java.io.File(Environment.getExternalStorageDirectory(), "Movies")
        expected.mkdirs()

        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Movies")
        val resolved = TreePathResolver.resolve(treeUri)

        assertEquals(expected, resolved)
    }

    @Test
    fun `a non-primary volume is a known gap and resolves to null rather than guessing a path`() {
        val treeUri = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "1234-5678:Movies")
        assertNull(TreePathResolver.resolve(treeUri))
    }

    @Test
    fun `an unparseable uri resolves to null`() {
        val treeUri = Uri.parse("content://nonsense")
        assertNull(TreePathResolver.resolve(treeUri))
    }
}
