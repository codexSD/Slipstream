package com.slipstream.app.peer

import java.io.File
import java.util.UUID
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Tests for [HistoryStore] persistence, ordering, eviction, and file existence checks.
 */
class HistoryStoreTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "slipstream-history-test-${UUID.randomUUID()}")
        tempDir.mkdirs()
    }

    @Test
    fun persistsAcrossTwoInstancesPointedAtSameFile() {
        val file = File(tempDir, "history.json")

        // First instance: add an entry
        val store1 = HistoryStore(file)
        val entry1 = createTestEntry(path = "file1.txt", size = 100)
        store1.addEntry(entry1)
        store1.saveSync()

        // Second instance: read from same file
        val store2 = HistoryStore(file)
        store2.loadSync()
        val entries = store2.entries.value

        assertEquals(1, entries.size)
        assertEquals("file1.txt", entries[0].path)
        assertEquals(100L, entries[0].size)
    }

    @Test
    fun ordersNewestFirst() {
        val file = File(tempDir, "history.json")
        val store = HistoryStore(file)

        // Add three entries with different timestamps
        val entry1 = createTestEntry(path = "old.txt", timestamp = 1000)
        val entry2 = createTestEntry(path = "middle.txt", timestamp = 2000)
        val entry3 = createTestEntry(path = "newest.txt", timestamp = 3000)

        store.addEntry(entry1)
        store.addEntry(entry2)
        store.addEntry(entry3)

        val entries = store.entries.value
        assertEquals(3, entries.size)
        assertEquals("newest.txt", entries[0].path)
        assertEquals("middle.txt", entries[1].path)
        assertEquals("old.txt", entries[2].path)
    }

    @Test
    fun evictsOldestWhenExceeding500Entries() {
        val file = File(tempDir, "history.json")
        val store = HistoryStore(file)

        // Add 501 entries
        repeat(501) { i ->
            val entry = createTestEntry(path = "file-$i.txt", timestamp = i.toLong())
            store.addEntry(entry)
        }

        // Should only have 500
        val entries = store.entries.value
        assertEquals(500, entries.size)

        // Oldest (timestamp 0) should be gone, newest should be present
        assertEquals("file-500.txt", entries[0].path) // newest first
        assertEquals("file-1.txt", entries[499].path) // oldest remaining
    }

    @Test
    fun disablesOpenWhenFileNoLongerExists() {
        val file = File(tempDir, "nonexistent-${UUID.randomUUID()}.txt")

        val entry = HistoryEntry(
            id = UUID.randomUUID().toString(),
            path = file.absolutePath,
            size = 100,
            timestamp = System.currentTimeMillis(),
            direction = HistoryEntry.Direction.Pull,
            state = HistoryEntry.State.Completed,
        )

        // Entry's canOpen should be false
        assertFalse(entry.canOpen)

        // Create the file
        file.createNewFile()

        // canOpen should now be true since the file exists
        assertTrue(entry.canOpen)
    }

    @Test
    fun reEnqueueInvokesCallback() {
        val file = File(tempDir, "history.json")
        val store = HistoryStore(file)

        var reEnqueueCalled = false
        val reEnqueueCallback: (String) -> Unit = { _ ->
            reEnqueueCalled = true
        }

        val entry = createTestEntry(path = "file.txt")
        store.addEntry(entry)

        // Call re-enqueue
        entry.reEnqueue(reEnqueueCallback)

        assertTrue(reEnqueueCalled)
    }

    @Test
    fun loadsPersistentDataFromJsonFile() {
        val file = File(tempDir, "history.json")

        // Manually write a JSON file
        val json = """
            {
                "entries": [
                    {
                        "id": "12345678-1234-5678-1234-567812345678",
                        "path": "manual-entry.txt",
                        "size": 500,
                        "timestamp": 9999,
                        "direction": "Pull",
                        "state": "Completed"
                    }
                ]
            }
        """.trimIndent()
        file.writeText(json)

        // Load with HistoryStore
        val store = HistoryStore(file)
        store.loadSync()

        val entries = store.entries.value
        assertEquals(1, entries.size)
        assertEquals("manual-entry.txt", entries[0].path)
    }

    private fun createTestEntry(
        path: String = "test.txt",
        size: Long = 1024,
        timestamp: Long = System.currentTimeMillis(),
    ) = HistoryEntry(
        id = UUID.randomUUID().toString(),
        path = path,
        size = size,
        timestamp = timestamp,
        direction = HistoryEntry.Direction.Pull,
        state = HistoryEntry.State.Completed,
    )
}
