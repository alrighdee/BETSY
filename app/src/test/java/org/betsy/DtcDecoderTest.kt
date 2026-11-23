package org.betsy

import org.betsy.decode.DtcDecoder
import org.betsy.elm.NoDataException
import org.betsy.model.Dtc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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

    @Test(expected = NoDataException::class)
    fun mode13RejectsPartialList() {
        DtcDecoder.decodeMode13("53033000")
    }

    @Test(expected = NoDataException::class)
    fun mode13RejectsMissingCount() {
        DtcDecoder.decodeMode13("53")
    }

    @Test(expected = NoDataException::class)
    fun mode13RejectsMissingBody() {
        DtcDecoder.decodeMode13("5302")
    }

    @Test(expected = NoDataException::class)
    fun mode13RejectsIncompleteCode() {
        DtcDecoder.decodeMode13("530130")
    }

    @Test
    fun mode13IgnoresPaddingBeyondCountedList() {
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13("530130000000").map { it.code })
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode13("530000000000"))
    }

    @Test
    fun mode13AcceptsACompleteElmLengthPrefixedListAndPadding() {
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13("004530130000000").map { it.code })
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode13("00253000000"))
    }

    @Test
    fun mode13RejectsTagsInsideAnUnrelatedResponseOrAtANibbleBoundary() {
        for (r in listOf("62005300", "15300", "005300", "AA5300", "620053013000")) {
            assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13(r) }
        }
    }

    @Test
    fun mode13CannotReadACodeFromOutsideTheDeclaredMessage() {
        assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13("00253013000") }
    }

    @Test
    fun mode13RequiresTheEntireDeclaredMessageEvenWhenTheCountAlreadyFits() {
        assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13("00653013000") }
    }

    @Test
    fun mode13RejectsDeclaredLengthsWithoutACompleteHeader() {
        for (r in listOf("0005300", "0015300")) {
            assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13(r) }
        }
    }

    @Test
    fun zeroWordIsAnEndMarkerRatherThanP0000() {
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode13("53010000"))
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13("530230000000").map { it.code })
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13("5303300000000571").map { it.code })
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode13("530200000571"))
    }

    @Test
    fun zeroBytesWithinANonzeroCodeAreNotAnEndMarker() {
        assertEquals(listOf("P0001", "P0100"), DtcDecoder.decodeMode13("530200010100").map { it.code })
    }

    @Test
    fun zeroMarkerDoesNotExcuseAMissingCountedBody() {
        assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13("53020000") }
        assertThrows(NoDataException::class.java) { DtcDecoder.decodeMode13("00253010000") }
    }

    @Test
    fun sharedCountedDecodersDoNotReportZeroWordsAsFaults() {
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode03("43010000"))
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode07("47010000"))
        assertEquals(emptyList<Dtc>(), DtcDecoder.decodeMode0A("4A010000"))
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
