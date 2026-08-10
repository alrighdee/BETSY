package org.betsy

import org.betsy.decode.Gen3Decoder
import org.betsy.model.BatteryModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Synthetic fixtures built from the PROTOCOL.md §5.1 byte layouts. Uses the corrected temp
 * constant (°C = u16/256 − 50) and the block/aux `0.122` scale.
 */
class Gen3DecoderTest {
    private fun u8(v: Int): String = "%02X".format(v)

    private fun u16BE(v: Int): String = "%04X".format(v)

    /** Builds a Gen3 210181958798 combined response for N blocks (N <= 17 layout). */
    private fun combinedResponse(n: Int): String {
        // 2101 section: SOC at i+46, i.e. 46 hex chars after the tag
        val soc = "6101" + "00".repeat(21) + u8(153) // SOC byte at offset 4+42 = 46 → 153*0.3922 ≈ 60.0

        // 2181 section: N blocks + aux + pack total (unread). "81" tag must sit at i+48 = offset 48.
        val blocks = (0 until n).joinToString("") { u16BE(11803) } // round(11803*0.122)/100 = 14.40
        val aux = u16BE(44836) // round(44836*0.122)/100 - 40 = 14.70
        val packTotal = u16BE(0)

        // 2195 section: N IR bytes (1 mΩ each)
        val ir = (0 until n).joinToString("") { u8(25) }

        // 2187 section: intake word then TB1..TB3 at u16/256 - 50 °C
        val intake = u16BE(0)
        val temps = (0 until 3).joinToString("") { u16BE(23040) } // 23040/256 - 50 = 40.0 °C

        // 2198 section: current u16 at u+2, chg at u+6, dis at u+8, delta at u+10
        val scalars = u16BE(35268) + u8(178) + u8(170) + u8(100)
        // current = 35268/100 - 327.68 = 25.0; chg (178/2-64)*1.34 = 33.5; dis 28.14; delta 50

        return soc + "81" + blocks + aux + packTotal +
            "95" + ir +
            "87" + intake + temps +
            "98" + scalars
    }

    @Test
    fun combinedN14() {
        val m = BatteryModel(blockCount = 14)
        Gen3Decoder.decodeCombined(combinedResponse(14), m)

        assertEquals(60.0f, m.soc, 0.5f)
        assertEquals(14, m.blockVolts.size)
        assertEquals(14.40f, m.blockVolts.first(), 0.01f)
        assertEquals(14.40f, m.blockVolts.last(), 0.01f)
        assertEquals(14.70f, m.aux12V, 0.01f)
        assertEquals(listOf(25, 25, 25), m.internalResistance.take(3))
        assertEquals(listOf(40.0f, 40.0f, 40.0f), m.temps)
        assertEquals(25.0f, m.currentAmps, 0.01f)
        assertEquals(33.5f, m.maxChargeHp, 0.01f)
        assertEquals(28.14f, m.maxDischargeHp, 0.01f)
        assertEquals(50.0f, m.deltaSoc, 0.01f)
    }

    @Test
    fun standaloneBlocks() {
        val n = 14
        val r = "6181" + (0 until n).joinToString("") { u16BE(11803) } + u16BE(44836)
        val m = BatteryModel(blockCount = n)
        Gen3Decoder.decodeBlocks(r, m)
        assertEquals(14.40f, m.blockVolts.first(), 0.01f)
        assertEquals(14.70f, m.aux12V, 0.01f)
    }

    @Test
    fun standaloneTemps() {
        // +8 skip: intake word then TB1..TB3; pad to satisfy the i+24 length guard
        val r = "6187" + u16BE(0) + u16BE(23040) + u16BE(23040) + u16BE(23040) + u16BE(0) + u16BE(0)
        val m = BatteryModel(blockCount = 14)
        Gen3Decoder.decodeTemps(r, m)
        assertEquals(listOf(40.0f, 40.0f, 40.0f), m.temps)
    }

    @Test
    fun standaloneIr() {
        val n = 14
        val r = "6195" + (0 until n).joinToString("") { u8(25) }
        val m = BatteryModel(blockCount = n)
        Gen3Decoder.decodeIr(r, m)
        assertEquals(n, m.internalResistance.size)
        assertEquals(25, m.internalResistance[0])
    }

    @Test
    fun standaloneSoc() {
        val r = "6101" + "00".repeat(21) + u8(153) // SOC byte at i+46
        val m = BatteryModel(blockCount = 14)
        Gen3Decoder.decodeSoc(r, m)
        assertEquals(60.0f, m.soc, 0.5f)
    }

    @Test
    fun standaloneScalars() {
        val r = "6198" + u16BE(35268) + u8(178) + u8(170) + u8(100)
        val m = BatteryModel(blockCount = 14)
        Gen3Decoder.decodeScalars(r, m)
        assertEquals(25.0f, m.currentAmps, 0.01f)
        assertEquals(33.5f, m.maxChargeHp, 0.01f)
        assertEquals(28.14f, m.maxDischargeHp, 0.01f)
        assertEquals(50.0f, m.deltaSoc, 0.01f)
    }
}
