package com.slipstream.core

import org.junit.Test
import org.junit.Assert.assertTrue

class VectorsTest {
    @Test
    fun `finds the shared conformance vectors`() {
        assertTrue(Vectors.read("crc32c.json").contains("Castagnoli"))
    }
}
