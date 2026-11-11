package org.betsy

import org.betsy.decode.DtcDecoder
import org.betsy.decode.Gen2Decoder
import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.betsy.decode.SpeedRpmDecoder
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.transport.SimulatedCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseByteAlignmentTest {
    private val combined = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")
    private val ce = combined.take(66)
    private val d0 = "61" + combined.substring(66, 126)
    private val cf = "61" + combined.takeLast(34)

    @Test
    fun batteryResponsesRejectDanglingNibblesBeforeUpdatingTheModel() {
        val readers: List<Pair<String, (String, BatteryModel) -> Unit>> =
            listOf(
                ce to { r, m -> Gen2Decoder.decodeCe(r, m) },
                d0 to { r, m -> Gen2Decoder.decodeD0(r, m) },
                cf to { r, m -> Gen2Decoder.decodeCf(r, m, false) },
                combined to { r, m -> Gen2Decoder.decodeCombined(r, m, false) },
            )
        for ((response, decode) in readers) {
            val m = BatteryModel(blockCount = 14)
            val before = m.copy()
            assertThrows(NoDataException::class.java) { decode(response + "F", m) }
            assertEquals(before, m)
        }
        assertThrows(NoDataException::class.java) { Gen2Decoder.readBlockCount(d0 + "F") }
    }

    @Test
    fun countedResponsesCannotIgnoreAnUnpairedHexDigit() {
        for ((response, tag) in listOf("5300F" to "53", "53013000F" to "53", "43010AA6F" to "43", "4700F" to "47")) {
            assertThrows(NoDataException::class.java) { DtcDecoder.decodeCounted(response, tag) }
        }
    }

    @Test
    fun infPagesCannotRoundDownAnOddPayloadLength() {
        val table = InfLayout.table(0xC7)!!
        val empty = "61C7" + "00".repeat(48)
        val populated = "61C7" + "00".repeat(29) + "0073" + "00".repeat(17)
        for (response in listOf(empty, populated)) {
            assertThrows(NoDataException::class.java) { InfDecoder.decodeActive(response + "F", table) }
        }
    }

    @Test
    fun speedResponseRejectsAnUnpairedHexDigitBeforeUpdatingEitherValue() {
        val m = BatteryModel()
        m.rpm = 777
        m.speedMph = 12f
        assertThrows(NoDataException::class.java) { SpeedRpmDecoder.decode("410C0FA00D64F", m) }
        assertEquals(777, m.rpm)
        assertEquals(12f, m.speedMph, 0f)
    }

    @Test
    fun threeDigitElmLengthsRemainValidDespiteOddTotalCharacterCounts() {
        val m = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined("050" + combined, m, false)
        assertEquals(14, m.blockVolts.size)
        assertEquals(14, Gen2Decoder.readBlockCount("%03X".format(d0.length / 2) + d0))
        assertEquals(listOf("P3000"), DtcDecoder.decodeMode13("00453013000").map { it.code })
        val inf = "03261C7" + "00".repeat(29) + "0073" + "00".repeat(17)
        assertEquals(115, InfDecoder.decodeActive(inf, InfLayout.table(0xC7)!!).single().code)
        SpeedRpmDecoder.decode("006410C0FA00D64", m)
        assertEquals(1000, m.rpm)
    }
}
