package org.betsy

import org.betsy.decode.DtcDecoder
import org.betsy.elm.NoDataException
import org.betsy.model.Dtc
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic DTC fixtures (PROTOCOL.md §9.1–9.3). Responses carry no per-DTC status byte:
 * `53 <count> <DTC 2B>…`. J2012 letter mapping by first hex digit (0-3→P, 4-7→C, 8-B→B, C-F→U).
 */
class DtcDecoderTest {
    @Test
    fun letterMapping() {
        assertEquals('P', DtcDecoder.letter(0x0))
        assertEquals('P', DtcDecoder.letter(0x3))
        assertEquals('C', DtcDecoder.letter(0x4))
        assertEquals('C', DtcDecoder.letter(0x7))
        assertEquals('B', DtcDecoder.letter(0x8))
        assertEquals('B', DtcDecoder.letter(0xB))
        assertEquals('U', DtcDecoder.letter(0xC))
        assertEquals('U', DtcDecoder.letter(0xF))
    }

    @Test
    fun formatJoinsLetterAndDigits() {
        assertEquals("P3019", DtcDecoder.format(0x3019))
        assertEquals("P0A80", DtcDecoder.format(0x0A80))
        assertEquals("U0112", DtcDecoder.format(0xC112))
    }

    @Test
    fun mode13CountByte() {
        val r = "530230000A60" // P3000, P0A60
        val dtcs = DtcDecoder.decodeMode13(r)
        assertEquals(listOf("P3000", "P0A60"), dtcs.map { it.code })
        assertEquals(listOf(0x3000, 0x0A60), dtcs.map { it.raw })
    }

    @Test
    fun mode13NoDtc() {
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode13("5300"))
    }

    @Test
    fun mode13CountOverstatesLengthIsTrustedDown() {
        // count says 3 but only 1 DTC fits; must not read past the end.
        val r = "53033000"
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13(r).map { it.code })
    }

    @Test
    fun mode0aPermanent() {
        val r = "4A01C112" // U0112
        assertEquals(listOf("U0112"), DtcDecoder.decodeMode0A(r).map { it.code })
    }

    @Test
    fun mode03Stored() {
        val r = "4302B00A0A60" // B300A, P0A60
        assertEquals(listOf("B300A", "P0A60"), DtcDecoder.decodeMode03(r).map { it.code })
    }

    @Test
    fun mode07Pending() {
        val r = "47010A60" // P0A60
        assertEquals(listOf("P0A60"), DtcDecoder.decodeMode07(r).map { it.code })
    }

    @Test(expected = NoDataException::class)
    fun missingTagRaises() {
        DtcDecoder.decodeMode13("6205CA00")
    }
}
