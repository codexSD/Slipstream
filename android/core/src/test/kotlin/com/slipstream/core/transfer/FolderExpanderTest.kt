package com.slipstream.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FolderExpanderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `flattens nested files to slash-separated relative paths`() {
        val root = tmp.newFolder("root")
        File(root, "a.txt").writeText("a")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("b")
        File(root, "sub/deeper").mkdirs()
        File(root, "sub/deeper/c.txt").writeText("c")

        val paths = FolderExpander.expand(root).map { it.relativePath }.toSet()
        assertEquals(setOf("a.txt", "sub/b.txt", "sub/deeper/c.txt"), paths)
    }

    @Test
    fun `empty directories are preserved as explicit entries`() {
        val root = tmp.newFolder("root")
        File(root, "empty").mkdirs()
        File(root, "hasFiles").mkdirs()
        File(root, "hasFiles/file.txt").writeText("x")

        val entries = FolderExpander.expand(root)
        val emptyEntry = entries.single { it.relativePath == "empty" }
        assertTrue(emptyEntry.isDirectory)

        // Non-empty directory gets no explicit entry - its file implies it.
        assertTrue(entries.none { it.relativePath == "hasFiles" })
        assertTrue(entries.any { it.relativePath == "hasFiles/file.txt" })
    }

    @Test
    fun `nested empty directory is preserved at its deepest relative path`() {
        val root = tmp.newFolder("root")
        File(root, "a/b").mkdirs()

        val entries = FolderExpander.expand(root)
        assertEquals(listOf("a/b"), entries.map { it.relativePath })
        assertTrue(entries.single().isDirectory)
    }

    @Test
    fun `an entirely empty root yields no entries`() {
        val root = tmp.newFolder("root")
        assertEquals(emptyList<FolderExpander.Entry>(), FolderExpander.expand(root))
    }

    @Test
    fun `file entries carry their size, directory entries carry zero`() {
        val root = tmp.newFolder("root")
        File(root, "f.bin").writeBytes(ByteArray(1234))

        val entry = FolderExpander.expand(root).single()
        assertEquals(1234L, entry.size)
        assertEquals(false, entry.isDirectory)
    }
}
