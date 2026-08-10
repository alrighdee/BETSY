package org.betsy

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizeTest {
    @Test
    fun stripsFrameCountersAndNonHex() {
        // ATH0 means no CAN ID header; only ISO-TP frame counters and spacing appear (§1.1, §2)
        val raw =
            "61 CE 78 89 C4\r" +
                "1: 85 A0 85 A0\r" +
                "2: >"
        assertEquals("61CE7889C485A085A0", Normalize.normalize(raw))
    }

    @Test
    fun handlesLowercaseHex() {
        assertEquals("61CE7889C4", Normalize.normalize("61ce 78 89 c4"))
    }

    @Test
    fun u8AndU16BigEndian() {
        val r = "ABCDEF12"
        assertEquals(0xAB, Normalize.u8(r, 0))
        assertEquals(0xEF, Normalize.u8(r, 4))
        assertEquals(0xABCD, Normalize.u16(r, 0))
        assertEquals(0xEF12, Normalize.u16(r, 4))
    }

    @Test(expected = NoDataException::class)
    fun missingTagThrows() {
        Normalize.requireTag("41CE00", "61CE")
    }

    @Test
    fun requireTagFindsFromOffset() {
        val r = "000061CE78"
        assertEquals(4, Normalize.requireTag(r, "61CE"))
    }

    @Test
    fun requireLengthRejectsTruncation() {
        val e =
            try {
                Normalize.requireLength("ABCD", 8, "test")
                null
            } catch (ex: NoDataException) {
                ex
            }
        assertTrue(e != null)
        assertTrue(e!!.message!!.contains("truncated"))
    }
}
