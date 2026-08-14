package org.betsy

import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.betsy.elm.NoDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * PROTOCOL.md §7.4.2. The sub-code is a value the car transmits at bytes 29-30 of a freeze page,
 * not a flag the app infers, so these tests are anchored on bytes measured from a real car.
 */
class InfDecoderTest {
    private val c7 = InfLayout.table(0xC7)!!

    /**
     * The whole point, byte for byte. This payload was read from a 2009 Gen2 with `P0571` stored,
     * across an ignition cycle. `P0571` documents sub-code 115.
     */
    private val realP0571Page =
        "03261C7" +
            "808080800000049A417E00615F5B5D70820000A0AF0000000000000001007363" +
            "6B4A02615F5C639E6C665D659E9A801C"

    @Test
    fun readsTheSubCodeAMeasuredCarActuallyTransmitted() {
        val out = InfDecoder.decodeActive(realP0571Page, c7)
        assertEquals(1, out.size)
        assertEquals(115, out[0].code)
        assertEquals(0xC7, out[0].table)
        assertEquals("Detail Code 2", out[0].tableLabel)
    }

    /** A page nothing has written is the normal case on a healthy car, and reports nothing. */
    @Test
    fun anAllZeroPageYieldsNothing() {
        assertEquals(emptyList<Any>(), InfDecoder.decodeActive("03261C7" + "00".repeat(48), c7))
    }

    /**
     * The failure this replaces. Treating the same real payload as a table of flags reported 35
     * sub-codes for a brake-switch fault; those bytes are analog readings, not flags.
     */
    @Test
    fun aPopulatedPageYieldsExactlyOneSubCodeNotOnePerNonZeroByte() {
        val out = InfDecoder.decodeActive(realP0571Page, c7)
        assertEquals("a page carries one sub-code, not one per non-zero byte", 1, out.size)
    }

    /** Short payloads cannot reach the field. Absent, not an error, and not a guess. */
    @Test
    fun aPayloadTooShortToReachTheFieldYieldsNothing() {
        assertEquals(emptyList<Any>(), InfDecoder.decodeActive("03261C7" + "AA".repeat(20), c7))
    }

    /** Non-zero bytes but nothing three-digit in the field: report nothing rather than noise. */
    @Test
    fun anImplausibleValueIsNotReported() {
        val b = MutableList(48) { 0 }
        b[10] = 0x55
        b[InfDecoder.CODE_BYTE] = 0xFF
        b[InfDecoder.CODE_BYTE + 1] = 0xFF
        val hex = b.joinToString("") { "%02X".format(it) }
        assertEquals(emptyList<Any>(), InfDecoder.decodeActive("03261C7$hex", c7))
    }

    /** A missing tag means the read failed, which is different from a page holding nothing. */
    @Test
    fun aMissingTagStillThrows() {
        assertThrows(Exception::class.java) { InfDecoder.decodeActive("7F2112", c7) }
    }

    /** Multi-frame padding must not be read as payload. */
    @Test
    fun declaredLengthWinsOverTrailingPadding() {
        val out = InfDecoder.decodeActive(realP0571Page + "AAAAAAAA", c7)
        assertEquals(115, out.single().code)
    }

    @Test
    fun noDataIsSurfacedNotSwallowed() {
        assertThrows(NoDataException::class.java) { InfDecoder.decodeActive("03261C7", c7) }
    }
}
