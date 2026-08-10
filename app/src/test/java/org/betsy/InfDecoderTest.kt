package org.betsy

import org.betsy.decode.InfDecoder
import org.betsy.decode.InfField
import org.betsy.decode.InfLayout
import org.betsy.decode.InfTable
import org.betsy.elm.NoDataException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The INF detail-table read, `21C6`..`21CA` on the HV ECU (PROTOCOL.md §9.4.0).
 *
 * What is under test here is the extraction algorithm, not any particular field map. The tests
 * run against a fixture table defined below, with invented codes at chosen offsets, because
 * [InfLayout] deliberately ships no field map: nothing in the data this project has collected
 * supports one yet.
 *
 * Semantics being pinned (§9.4.0): bit order is MSB-first, so "bit 0" is `0x80`; a field is active
 * iff its extracted value is nonzero; a field past the end of the payload is absent rather than an
 * error; and the payload is bounded by the response's declared ISO-TP length, not by the length of
 * the string.
 */
class InfDecoderTest {
    /**
     * Synthetic layout exercising each decode path: a whole byte, the two extreme bit positions
     * within a byte, a big-endian pair, a field beyond a short payload, and a multi-bit field
     * that straddles a byte boundary.
     */
    private val fixture =
        InfTable(
            lid = 0xCA,
            fields =
                listOf(
                    InfField(code = 901, bitStart = 24, bitEnd = 31), // byte 3, whole
                    InfField(code = 902, bitStart = 40, bitEnd = 40), // byte 5, MSB
                    InfField(code = 903, bitStart = 47, bitEnd = 47), // byte 5, LSB
                    InfField(code = 904, bitStart = 64, bitEnd = 79), // bytes 8-9, 16-bit
                    InfField(code = 905, bitStart = 12, bitEnd = 19), // straddles bytes 1-2
                    InfField(code = 906, bitStart = 160, bitEnd = 167), // byte 20, past a short payload
                ),
        )

    /** `n` payload bytes, zero except the given overrides. */
    private fun payload(
        n: Int,
        vararg set: Pair<Int, Int>,
    ): String {
        val b = ByteArray(n)
        for ((k, v) in set) b[k] = v.toByte()
        return b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    @Test
    fun theFiveTableIdentifiersAreTheOnesTheEcuAnswers() {
        // Established by observation on a real Gen2: 7E2 answers each of these with 61 <lid>.
        assertEquals(listOf(0xC6, 0xC7, 0xC8, 0xC9, 0xCA), InfLayout.tables.map { it.lid })
        assertEquals("21CA", InfLayout.table(0xCA)!!.request)
        assertEquals("61CA", InfLayout.table(0xCA)!!.tag)
    }

    @Test
    fun noFieldMapIsShipped() {
        // Deliberate: no capture has ever shown a bit set, so no mapping is asserted here. The
        // read still happens and the raw bytes are still recorded; only the naming is withheld.
        assertTrue(InfLayout.tables.all { it.fields.isEmpty() })
        assertEquals(emptyList<Int>(), InfDecoder.decodeActive("61CA" + payload(48, 13 to 0x42), InfLayout.table(0xCA)!!).map { it.code })
    }

    @Test
    fun wholeByteFieldDecodesVerbatim() {
        val active = InfDecoder.decodeActive("61CA" + payload(12, 3 to 0x42), fixture)
        assertEquals(listOf(901), active.map { it.code })
        assertEquals(0x42, active.single().value)
        assertEquals(0xCA, active.single().table)
        assertEquals("Detail Code 5", active.single().tableLabel)
    }

    @Test
    fun bitZeroIsTheMostSignificantBit() {
        // MSB-first: byte 5 bit 0 is 0x80, not 0x01. Reversing this silently swaps neighbours.
        assertEquals(listOf(902), InfDecoder.decodeActive("61CA" + payload(12, 5 to 0x80), fixture).map { it.code })
        assertEquals(listOf(903), InfDecoder.decodeActive("61CA" + payload(12, 5 to 0x01), fixture).map { it.code })
    }

    @Test
    fun sixteenBitFieldIsBigEndian() {
        val active = InfDecoder.decodeActive("61CA" + payload(12, 8 to 0x01), fixture)
        assertEquals(listOf(904), active.map { it.code })
        assertEquals(0x0100, active.single().value)
    }

    @Test
    fun fieldsPastTheEndOfTheResponseAreAbsentNotAnError() {
        // One layout is applied to whatever length the ECU returns; a short table simply reports
        // fewer ordinals. On real hardware this is the normal case, not an edge case.
        val short = ByteArray(12) { 0xFF.toByte() }.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        val active = InfDecoder.decodeActive("61CA$short", fixture)
        assertTrue(active.none { it.code == 906 })
        assertEquals(5, InfDecoder.reportableFields(fixture, 12))
        assertEquals(6, InfDecoder.reportableFields(fixture, 21))
    }

    @Test
    fun aLongerPayloadReportsTheSlotsAShorterOneCannot() {
        val long = ByteArray(21) { 0xFF.toByte() }.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        assertTrue(InfDecoder.decodeActive("61CA$long", fixture).any { it.code == 906 })
    }

    /**
     * §9.4.0: the bottom-byte shift truncates to 8 bits, discarding bits above bitStart. Doing it
     * in 32 bits would leak them into the result for any multi-bit field that is not byte-aligned.
     */
    @Test
    fun bottomByteShiftTruncatesToEightBits() {
        val data = byteArrayOf(0xAB.toByte(), 0xCD.toByte())
        // bits 4..11: low nibble of 0xAB then high nibble of 0xCD = 0xBC, not 0xABC.
        assertEquals(0xBC, InfDecoder.extract(data, 4, 11))
    }

    @Test
    fun allZeroPayloadReportsNothing() {
        assertEquals(emptyList<Int>(), InfDecoder.decodeActive("61CA" + payload(12), fixture).map { it.code })
    }

    @Test(expected = NoDataException::class)
    fun missingTagRaises() {
        InfDecoder.decodeActive("61C9" + payload(12, 3 to 1), fixture)
    }

    @Test(expected = NoDataException::class)
    fun emptyPayloadRaises() {
        InfDecoder.decodeActive("61CA", fixture)
    }

    /**
     * A real 2009 Gen2 answers with `032` + `61CA` + 53 bytes: an ISO-TP length of 0x032 = 50
     * bytes (2 tag + 48 payload) followed by 48 payload bytes and 5 bytes of frame padding.
     *
     * Taken from a published capture in captures/real/. The padding there is 0x00, which is
     * why this stayed invisible; an ECU padding with 0xAA would have reported the trailing
     * ordinals as active codes that do not exist.
     */
    @Test
    fun framePaddingBeyondTheDeclaredLengthIsNotDecoded() {
        // Declared 0x00E = 14 bytes: 2 tag + 12 payload. Byte 20 is padding, so 906 must not fire.
        val padded = "00E" + "61CA" + payload(12) + "AA".repeat(9)
        assertEquals(emptyList<Int>(), InfDecoder.decodeActive(padded, fixture).map { it.code })
    }

    @Test
    fun aCodeWithinTheDeclaredLengthStillDecodes() {
        val padded = "00E" + "61CA" + payload(12, 3 to 0x42) + "AA".repeat(9)
        assertEquals(listOf(901), InfDecoder.decodeActive(padded, fixture).map { it.code })
    }

    @Test
    fun anUnprefixedResponseIsUnaffected() {
        // Single-frame responses carry no length header; the string length is all there is.
        assertEquals(
            listOf(901),
            InfDecoder.decodeActive("61CA" + payload(12, 3 to 0x42), fixture).map { it.code },
        )
    }
}
