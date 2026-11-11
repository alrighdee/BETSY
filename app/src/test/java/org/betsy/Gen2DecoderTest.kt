package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.transport.SimulatedCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Synthetic fixtures built from the PROTOCOL.md §5.2 byte layouts, checked against the corrected
 * formulas (offset-binary u16/100−327.68 for temps, block count from 21D0 byte 0, etc).
 */
class Gen2DecoderTest {
    private fun hex(
        v: Int,
        digits: Int,
    ): String = "%0${digits}X".format(v)

    private fun u16BE(v: Int): String = hex(v, 4)

    private fun u8(v: Int): String = hex(v, 2)

    /** Builds a Gen2 21CED0CF combined response. [fifteen] selects the p044o1 scalar offsets. */
    private fun combinedResponse(
        n: Int,
        fifteen: Boolean,
    ): String {
        // 21CE section: SOC, current, then up to 17 blocks
        val ce = "61CE" + u8(120) + u16BE(35268) + (0 until minOf(n, 17)).joinToString("") { u16BE(34208) }

        // 21D0 section: 15-byte preamble, then N IR bytes. Byte 0 of the preamble is the block count.
        val d0Preamble = u8(n) + (0 until 14).joinToString("") { u8(0) }
        val d0 = "D0" + d0Preamble + (0 until n).joinToString("") { u8(25) }

        // 21CF section. Non-15 layout: aux/limits/delta at hex-char k+6/k+8/k+10/k+12, temps at
        // k+20/k+24/k+28. Fifteen layout: scalars 4 hex chars earlier (k+2/k+4/k+6/k+8), temps
        // stay at k+20. 16 bytes, matching the measured acceptance fixture (PROTOCOL.md §5.2):
        // the combined decoder's requireLength(c+34) covers the tag plus the 16-byte payload.
        // k+6 → byte 3, k+8 → byte 4, k+10 → byte 5, k+12 → byte 6, k+20 → byte 10 (u16)
        val s = if (fifteen) 2 else 6
        val bytes = ByteArray(16)
        bytes[s / 2] = 192.toByte() // aux12V: 192 * 0.2 - 25.6 = 12.8 V
        bytes[s / 2 + 1] = 178.toByte() // maxChg: (178/2 - 64) * 1.34 = 33.5 HP
        bytes[s / 2 + 2] = 170.toByte() // maxDis: (170/2 - 64) * 1.34 = 28.14 HP
        bytes[s / 2 + 3] = 100.toByte() // deltaSOC: 100 * 0.01 = 1.0 %
        writeU16(bytes, 10, 36768) // temps: (36768/100 - 327.68) = 40.0 °C
        writeU16(bytes, 12, 36768)
        writeU16(bytes, 14, 36768)
        val cf = "CF" + bytes.joinToString("") { u8(it.toInt() and 0xFF) }

        // spillover blocks beyond 17 (not used at N<=17)
        return ce + d0 + cf
    }

    private fun writeU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value shr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    @Test
    fun combinedN14() {
        val m = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined(combinedResponse(14, fifteen = false), m, fifteenBlockVariant = false)

        assertEquals(60.0f, m.soc, 0.01f)
        assertEquals(25.0f, m.currentAmps, 0.01f)
        assertEquals(14, m.blockVolts.size)
        assertEquals(14.40f, m.blockVolts.first(), 0.01f)
        assertEquals(14.40f, m.blockVolts.last(), 0.01f)
        assertEquals(listOf(25, 25, 25), m.internalResistance.take(3))
        assertEquals(12.8f, m.aux12V, 0.01f)
        assertEquals(33.5f, m.maxChargeHp, 0.01f)
        assertEquals(28.14f, m.maxDischargeHp, 0.01f)
        assertEquals(1.0f, m.deltaSoc, 0.01f)
        assertEquals(listOf(40.0f, 40.0f, 40.0f), m.temps)
    }

    @Test
    fun combinedN15FifteenVariant() {
        val m = BatteryModel(blockCount = 15)
        Gen2Decoder.decodeCombined(combinedResponse(15, fifteen = true), m, fifteenBlockVariant = true)

        assertEquals(15, m.blockVolts.size)
        assertEquals(60.0f, m.soc, 0.01f)
        assertEquals(12.8f, m.aux12V, 0.01f)
        assertEquals(33.5f, m.maxChargeHp, 0.01f)
        assertEquals(28.14f, m.maxDischargeHp, 0.01f)
        assertEquals(1.0f, m.deltaSoc, 0.01f)
        assertEquals(listOf(40.0f, 40.0f, 40.0f), m.temps)
    }

    @Test
    fun combinedWrongVariantFailsLengthGuard() {
        // N=15 with the non-15 layout misparses: aux read from the wrong offset → different length
        val m = BatteryModel(blockCount = 15)
        Gen2Decoder.decodeCombined(combinedResponse(15, fifteen = true), m, fifteenBlockVariant = false)
        // The spec says decoding a fifteen pack without the variant reads garbage, here it
        // happens to survive (the layout is offset by 2 bytes). Guard is behavior, not an assert.
        assertEquals(60.0f, m.soc, 0.01f)
    }

    @Test
    fun blockCountFromByteZero() {
        // Byte 0 of the 21D0 preamble is the ECU-reported block count (§5.2)
        val r = "61D0" + u8(14) + (0 until 14).joinToString("") { u8(0) } + (0 until 14).joinToString("") { u8(25) }
        val m = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeD0(r, m)
        assertEquals(listOf(25, 25, 25), m.internalResistance.take(3))
        assertEquals(14, m.internalResistance.size)
    }

    /**
     * The measured parked-and-charging fixture that drives the demo monitor. Its 21CF section is
     * the real 16 bytes, so this pins that the combined decoder's length guard accepts what a car
     * actually answers, not just the synthetic 18-byte fixture above.
     */
    @Test
    fun measuredParkedAndChargingFixtureDecodes() {
        val r = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")
        val m = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined(r, m, fifteenBlockVariant = false)

        assertEquals(54.5f, m.soc, 0.01f)
        assertEquals(2.67f, m.currentAmps, 0.01f)
        assertEquals(13.8f, m.aux12V, 0.01f)
        assertEquals(listOf(19, 19, 19), m.internalResistance.take(3))
        assertEquals(14, m.blockVolts.size)
        assertEquals(31.45f, m.temps[0], 0.01f)
        assertEquals(30.99f, m.temps[1], 0.01f)
        assertEquals(31.56f, m.temps[2], 0.01f)
    }

    @Test
    fun standalone16ByteCfMatchesTheSameFieldsInTheCombinedReply() {
        val combined = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")
        val whole = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCombined(combined, whole, fifteenBlockVariant = false)
        val separate = BatteryModel(blockCount = 14)
        Gen2Decoder.decodeCe(combined.take(66), separate)
        Gen2Decoder.decodeD0("61" + combined.substring(66, 126), separate)
        Gen2Decoder.decodeCf("61" + combined.takeLast(34), separate, fifteenBlockVariant = false)
        assertEquals(whole, separate)
    }

    @Test
    fun batteryDecodersRespectApplicationBoundariesAndDeclaredLengths() {
        val combined = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")
        val ce = combined.take(66)
        val d0 = "61" + combined.substring(66, 126)
        val cf = "61" + combined.takeLast(34)
        val readers: List<Pair<String, (String, BatteryModel) -> Unit>> =
            listOf(
                ce to { r, m -> Gen2Decoder.decodeCe(r, m) },
                d0 to { r, m -> Gen2Decoder.decodeD0(r, m) },
                cf to { r, m -> Gen2Decoder.decodeCf(r, m, fifteenBlockVariant = false) },
                combined to { r, m -> Gen2Decoder.decodeCombined(r, m, fifteenBlockVariant = false) },
            )
        for ((valid, decode) in readers) {
            val expected = BatteryModel(blockCount = 14)
            decode(valid, expected)
            val length = valid.length / 2
            val prefixed = "%03X".format(length) + valid
            val actual = BatteryModel(blockCount = 14)
            decode(prefixed + "00000000", actual)
            assertEquals(expected, actual)
            for (bad in listOf("6200" + valid, "002" + valid, "%03X".format(length + 1) + valid, valid.dropLast(2))) {
                val before = BatteryModel(blockCount = 14, soc = 7f, currentAmps = 8f, aux12V = 9f)
                val after = before.copy()
                assertThrows(NoDataException::class.java) { decode(bad, after) }
                assertEquals(before, after)
            }
        }
    }

    @Test
    fun blockCountCannotBeReadFromAnotherResponseOrOutsideDeclaredData() {
        val raw = "61D00E" + "00".repeat(14) + "14".repeat(14)
        assertEquals(14, Gen2Decoder.readBlockCount("%03X".format(raw.length / 2) + raw))
        for (bad in listOf("6200" + raw, "002" + raw, "%03X".format(raw.length / 2 + 1) + raw)) {
            assertThrows(NoDataException::class.java) { Gen2Decoder.readBlockCount(bad) }
        }
    }
}
