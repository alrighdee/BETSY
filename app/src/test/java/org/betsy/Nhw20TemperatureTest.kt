package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** NHW20 temperature vectors, recorded responses, and payload length checks. */
class Nhw20TemperatureTest {
    private val fixture by lazy {
        JSONObject(requireNotNull(javaClass.getResource("/nhw20_temperature_vectors.json")).readText())
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun writeWord(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun combinedResponse(
        payload: ByteArray,
        blockCount: Int,
    ): String {
        val ce = "61CE008000" + "8000".repeat(minOf(blockCount, 17))
        val d0 = "D0" + "%02X".format(blockCount) + "00".repeat(14) + "19".repeat(blockCount)
        return ce + d0 + "CF" + hex(payload)
    }

    @Test
    fun temperatureVectorsMatchBothReadPaths() {
        val vectors = fixture.getJSONArray("temperature_vectors")
        assertEquals(136, vectors.length())
        for (blockCount in listOf(14, 15, 17, 20)) {
            val fifteenBlockVariant = blockCount == 15
            val spilloverBytes = maxOf(blockCount - 17, 0) * 2
            repeat(vectors.length()) { index ->
                val vector = vectors.getJSONObject(index)
                val payload = ByteArray(spilloverBytes + 16)
                writeWord(payload, spilloverBytes, 0x1234) // Intake is not TB1.
                writeWord(payload, spilloverBytes + 10, vector.getInt("raw"))
                writeWord(payload, spilloverBytes + 12, 0x8C49)
                writeWord(payload, spilloverBytes + 14, 0x8C1B)
                val expected = listOf(vector.getDouble("celsius").toFloat(), 31.5f, 31.0f)
                val separate = BatteryModel(blockCount = blockCount)
                Gen2Decoder.decodeCf("61CF" + hex(payload), separate, fifteenBlockVariant)
                assertEquals("standalone n=$blockCount vector $index", expected, separate.temps)
                val combined = BatteryModel(blockCount = blockCount)
                Gen2Decoder.decodeCombined(combinedResponse(payload, blockCount), combined, fifteenBlockVariant)
                assertEquals("combined n=$blockCount vector $index", expected, combined.temps)
            }
        }
    }

    @Test
    fun recordedNhw20TemperaturesMatchExpectedValues() {
        val captures = fixture.getJSONArray("captures")
        assertEquals(188, captures.length())
        repeat(captures.length()) { index ->
            val capture = captures.getJSONObject(index)
            val expected =
                capture.getJSONArray("expected_celsius").let { values ->
                    List(3) { values.getDouble(it).toFloat() }
                }
            val separate = BatteryModel(blockCount = 14)
            Gen2Decoder.decodeCf(capture.getString("cf"), separate, fifteenBlockVariant = false)
            assertEquals(capture.getString("source"), expected, separate.temps)
            val combined = BatteryModel(blockCount = 14)
            Gen2Decoder.decodeCombined(capture.getString("combined"), combined, fifteenBlockVariant = false)
            assertEquals(capture.getString("source"), expected, combined.temps)
        }
    }

    @Test
    fun standaloneRejectsTruncatedLastTemperature() {
        val capture = fixture.getJSONArray("captures").getJSONObject(0).getString("cf")
        assertEquals(36, capture.length) // Service + identifier +16 payload bytes.
        assertThrows(NoDataException::class.java) {
            Gen2Decoder.decodeCf(capture.dropLast(2), BatteryModel(blockCount = 14), fifteenBlockVariant = false)
        }
    }

    @Test
    fun fifteenBlockLayoutUsesTheSameTemperatureConversion() {
        val payload = ByteArray(16)
        for (offset in listOf(10, 12, 14)) writeWord(payload, offset, 0x8C49)
        val model = BatteryModel(blockCount = 15)
        Gen2Decoder.decodeCf("61CF" + hex(payload), model, fifteenBlockVariant = true)
        for (temperature in model.temps) assertEquals(31.5f, temperature, 0.0001f)
    }
}
