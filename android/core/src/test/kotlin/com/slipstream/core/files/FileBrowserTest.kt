package com.slipstream.core.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBrowserTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `directories sort before files, each group sorted by name`() {
        val root = tmp.newFolder("root")
        File(root, "zeta.txt").writeText("z")
        File(root, "alpha.txt").writeText("a")
        File(root, "bravoDir").mkdirs()
        File(root, "alphaDir").mkdirs()

        val names = FileBrowser.list(root).entries.map { it.name }
        assertEquals(listOf("alphaDir", "bravoDir", "alpha.txt", "zeta.txt"), names)
    }

    @Test
    fun `mime type is inferred from extension`() {
        val root = tmp.newFolder("root")
        File(root, "photo.JPG").writeBytes(ByteArray(10))
        File(root, "notes.txt").writeText("hi")
        File(root, "mystery.xyzzy").writeBytes(ByteArray(1))

        val byName = FileBrowser.list(root).entries.associateBy { it.name }
        assertEquals("image/jpeg", byName.getValue("photo.JPG").mime)
        assertEquals("text/plain", byName.getValue("notes.txt").mime)
        assertEquals("application/octet-stream", byName.getValue("mystery.xyzzy").mime)
    }

    @Test
    fun `directories carry a null mime and zero size`() {
        val root = tmp.newFolder("root")
        File(root, "sub").mkdirs()

        val entry = FileBrowser.list(root).entries.single()
        assertEquals(null, entry.mime)
        assertEquals(0L, entry.size)
        assertTrue(entry.isDirectory)
    }

    @Test
    fun `listing at exactly the cap is not truncated`() {
        val root = tmp.newFolder("root")
        repeat(FileBrowser.MAX_ENTRIES) { File(root, "f$it.txt").writeText("x") }

        val listing = FileBrowser.list(root)
        assertFalse(listing.truncated)
        assertEquals(FileBrowser.MAX_ENTRIES, listing.entries.size)
    }

    @Test
    fun `listing one over the cap is truncated to exactly the cap`() {
        val root = tmp.newFolder("root")
        repeat(FileBrowser.MAX_ENTRIES + 1) { File(root, "f$it.txt").writeText("x") }

        val listing = FileBrowser.list(root)
        assertTrue(listing.truncated)
        assertEquals(FileBrowser.MAX_ENTRIES, listing.entries.size)
    }
}
