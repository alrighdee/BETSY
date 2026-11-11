package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.transport.SimulatedCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** NHW20 section boundaries must remain distinct from sensor bytes with the same value. */
class Nhw20SectionBoundaryTest {
    private val recorded by lazy { SimulatedCar.gen2WithStoredFault.getValue("21CED0CF") }

    @Test
    fun voltageByteD0DoesNotStartAnIrSection() {
        // Change only V14 to 0x85D0 = 14.88 V in the recorded 14-block layout.
        val response = recorded.replaceRange(62, 66, "85D0")
        val model = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined(response, model, fifteenBlockVariant = false)
        assertEquals(14.88f, model.blockVolts.last(), 0.001f)
        assertEquals(List(14) { 19 }, model.internalResistance)
        assertEquals(13.8f, model.aux12V, 0.001f)
    }

    @Test
    fun resistanceByteCfDoesNotStartALimitsSection() {
        // Synthetic raw resistance 0xCF: preserve its number without interpreting it as a tag.
        val response = recorded.replaceRange(98, 100, "CF")
        val model = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined(response, model, fifteenBlockVariant = false)
        assertEquals(listOf(207) + List(13) { 19 }, model.internalResistance)
        assertEquals(13.8f, model.aux12V, 0.001f)
    }

    @Test
    fun missingSectionIdentifierIsRejectedBeforeUpdatingValues() {
        for (offset in listOf(66, 126)) {
            val model = BatteryModel(blockCount = 14, soc = 17f, internalResistance = listOf(23))
            val response = recorded.replaceRange(offset, offset + 2, "00")
            assertThrows(NoDataException::class.java) {
                Gen2Decoder.decodeCombined(response, model, fifteenBlockVariant = false)
            }
            assertEquals(17f, model.soc, 0f)
            assertEquals(listOf(23), model.internalResistance)
        }
    }

    @Test
    fun truncatedCeTailIsRejectedBeforeUpdatingValues() {
        val ce = recorded.substring(0, 66)
        for (missingBytes in 1..3) {
            val model = BatteryModel(blockCount = 14, soc = 17f, blockVolts = listOf(12f))
            assertThrows(NoDataException::class.java) {
                Gen2Decoder.decodeCe(ce.dropLast(missingBytes * 2), model)
            }
            assertEquals(17f, model.soc, 0f)
            assertEquals(listOf(12f), model.blockVolts)
        }
    }

    @Test
    fun completeCeRetainsItsLastVoltage() {
        val model = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCe(recorded.substring(0, 66), model)
        assertEquals(14, model.blockVolts.size)
        assertEquals(54.5f, model.soc, 0.001f)
        assertEquals(15.05f, model.blockVolts.last(), 0.001f)
    }
}
