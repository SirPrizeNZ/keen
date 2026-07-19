package com.keenzero.app.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpByteRangeTest {
    @Test
    fun parsesBoundedAndOpenEndedRanges() {
        assertEquals(HttpByteRange(100, 199), HttpByteRange.parse("bytes=100-199", 1000))
        assertEquals(HttpByteRange(900, 999), HttpByteRange.parse("bytes=900-", 1000))
    }

    @Test
    fun parsesSuffixAndClampsEnd() {
        assertEquals(HttpByteRange(900, 999), HttpByteRange.parse("bytes=-100", 1000))
        assertEquals(HttpByteRange(900, 999), HttpByteRange.parse("bytes=900-2000", 1000))
    }

    @Test
    fun rejectsInvalidOrUnsatisfiableRanges() {
        assertNull(HttpByteRange.parse("bytes=1000-", 1000))
        assertNull(HttpByteRange.parse("bytes=200-100", 1000))
        assertNull(HttpByteRange.parse("bytes=0-1,4-5", 1000))
    }
}
