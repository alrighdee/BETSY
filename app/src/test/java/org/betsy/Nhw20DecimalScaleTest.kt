package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.model.BatteryModel
import org.betsy.transport.SimulatedCar
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fixed decimal results check rounding near the midpoint and at unsigned word boundaries. */
class Nhw20DecimalScaleTest {
    private val recorded by lazy { SimulatedCar.gen2WithStoredFault.getValue("21CED0CF") }

    @Test
    fun currentAndCeVoltagesRetainTheDecimalScaleInBothReadPaths() {
        val vectors =
            listOf(
                0 to -327.68f,
                1 to -327.67f,
                2 to -327.66f,
                32767 to -0.01f,
                32768 to 0f,
                65534 to 327.66f,
                65535 to 327.67f,
            )
        for ((raw, expected) in vectors) {
            val word = "%04X".format(raw)
            // Synthetic arithmetic vectors in the recorded 14-block layout.
            val ce =
                recorded
                    .substring(0, 66)
                    .replaceRange(6, 10, word)
                    .replaceRange(10, 14, word)
                    .replaceRange(62, 66, word)
            val separate = BatteryModel(blockCount = 14)
            Gen2Decoder.decodeCe(ce, separate)
            val combined = BatteryModel(blockCount = 14)
            Gen2Decoder.decodeCombined(ce + recorded.substring(66), combined, false)
            for (model in listOf(separate, combined)) {
                assertEquals("current raw $raw", expected, model.currentAmps, 0f)
                assertEquals("V1 raw $raw", expected, model.blockVolts.first(), 0f)
                assertEquals("V14 raw $raw", expected, model.blockVolts.last(), 0f)
            }
        }
    }

    @Test
    fun auxiliaryVoltageRetainsTheDecimalScaleInBothReadPaths() {
        val vectors = listOf(0 to -25.6f, 1 to -25.4f, 2 to -25.2f, 127 to -0.2f, 128 to 0f, 254 to 25.2f, 255 to 25.4f)
        for ((raw, expected) in vectors) {
            val cf = ("61" + recorded.substring(126)).replaceRange(10, 12, "%02X".format(raw))
            val separate = BatteryModel(blockCount = 14)
            // Padding keeps this test independent of the separate CF minimum-length correction.
            Gen2Decoder.decodeCf(cf + "0000", separate, false)
            val combined = BatteryModel(blockCount = 14)
            Gen2Decoder.decodeCombined(recorded.substring(0, 126) + cf.substring(2), combined, false)
            assertEquals("standalone auxiliary raw $raw", expected, separate.aux12V, 0f)
            assertEquals("combined auxiliary raw $raw", expected, combined.aux12V, 0f)
        }
    }

    @Test
    fun spilloverVoltagesUseTheSameMidpointAndDecimalScale() {
        // Synthetic twenty-block layout: this checks arithmetic, not another measured vehicle.
        val ce = "61CE788000" + "85A0".repeat(17)
        val d0 = "D014" + "00".repeat(14) + "19".repeat(20)
        val cf = "7FFF80008001" + recorded.substring(128)
        val separate = BatteryModel(blockCount = 20)
        Gen2Decoder.decodeCf("61CF" + cf + "0000", separate, false)
        val combined = BatteryModel(blockCount = 20)
        Gen2Decoder.decodeCombined(ce + d0 + "CF" + cf, combined, false)
        for (model in listOf(separate, combined)) {
            assertEquals(listOf(-0.01f, 0f, 0.01f), model.blockVolts.takeLast(3))
        }
    }
}
