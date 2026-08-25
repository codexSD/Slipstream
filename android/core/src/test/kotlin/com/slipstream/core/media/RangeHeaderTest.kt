package com.slipstream.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeHeaderTest {

    @Test
    fun `absent header serves whole file`() {
        assertEquals(RangeHeader.ParseResult.Absent, RangeHeader.parse(null, 1000))
    }

    @Test
    fun `closed range resolves start and end`() {
        val result = RangeHeader.parse("bytes=100-199", 1000)
        assertEquals(RangeHeader.ParseResult.Satisfiable(RangeHeader.Range(100, 199)), result)
    }

    @Test
    fun `open-ended range resolves to end of file`() {
        val result = RangeHeader.parse("bytes=500-", 1000)
        assertEquals(RangeHeader.ParseResult.Satisfiable(RangeHeader.Range(500, 999)), result)
    }

    @Test
    fun `suffix range resolves last N bytes`() {
        val result = RangeHeader.parse("bytes=-500", 1000)
        assertEquals(RangeHeader.ParseResult.Satisfiable(RangeHeader.Range(500, 999)), result)
    }

    @Test
    fun `suffix range larger than file clamps to start`() {
        val result = RangeHeader.parse("bytes=-5000", 1000)
        assertEquals(RangeHeader.ParseResult.Satisfiable(RangeHeader.Range(0, 999)), result)
    }

    @Test
    fun `range starting at or past content length is unsatisfiable`() {
        assertEquals(RangeHeader.ParseResult.Unsatisfiable, RangeHeader.parse("bytes=1000-1500", 1000))
    }

    @Test
    fun `malformed range header is unsatisfiable`() {
        assertEquals(RangeHeader.ParseResult.Unsatisfiable, RangeHeader.parse("bytes=abc-def", 1000))
        assertEquals(RangeHeader.ParseResult.Unsatisfiable, RangeHeader.parse("not-a-range", 1000))
        assertEquals(RangeHeader.ParseResult.Unsatisfiable, RangeHeader.parse("bytes=", 1000))
    }

    @Test
    fun `end clamps to last byte of the file`() {
        val result = RangeHeader.parse("bytes=0-99999", 1000)
        assertEquals(RangeHeader.ParseResult.Satisfiable(RangeHeader.Range(0, 999)), result)
    }
}
