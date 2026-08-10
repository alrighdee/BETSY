package org.betsy.decode

import org.betsy.elm.Normalize
import org.betsy.model.BatteryModel
import kotlin.math.round

/**
 * PROTOCOL.md §5.1, Gen3 (`ATSH7E2`), mode 21.
 *
 * Uses the corrected temperature constant `°C = u16/256 − 50` (equivalent to the app's
 * `°F = u16 × 0.00703125 − 58`) rather than the buggy `0.007` from the combined decoder.
 * Temps are stored in °C; conversion is a display-layer concern (§5.1 note).
 */
object Gen3Decoder {
    /** 2181, block voltages + aux 12 V (§5.1). */
    fun decodeBlocks(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "6181")
        var k = i + 4
        Normalize.requireLength(r, k + n * 4 + 4, "2181")
        val blocks = MutableList(n) { 0f }
        for (idx in 0 until n) {
            blocks[idx] = round(Normalize.u16(r, k) * 0.122f) / 100f
            k += 4
        }
        m.blockVolts = blocks
        // the trailing word really is the 12 V auxiliary battery (§5.1)
        m.aux12V = round(Normalize.u16(r, k) * 0.122f) / 100f - 40f
    }

    /** 2187, battery temperatures TB1..TB3 (§5.1). */
    fun decodeTemps(
        r: String,
        m: BatteryModel,
    ) {
        val i = Normalize.requireTag(r, "6187")
        val k = i + 8 // +8 skips the intake-air word (§5.1)
        Normalize.requireLength(r, i + 24, "2187")
        val temps = MutableList(3) { 0f }
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(r, k + idx * 4) / 256f - 50f // °C
        }
        m.temps = temps
    }

    /** 2195, internal resistance, 1 raw unit = 1 mΩ (§4.1, §5.1). */
    fun decodeIr(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "6195")
        var k = i + 4
        Normalize.requireLength(r, k + n * 2, "2195")
        val ir = MutableList(n) { 0 }
        for (idx in 0 until n) {
            ir[idx] = Normalize.u8(r, k + idx * 2)
        }
        m.internalResistance = ir
    }

    /** 2101, state of charge (§5.1). */
    fun decodeSoc(
        r: String,
        m: BatteryModel,
    ) {
        val i = Normalize.requireTag(r, "6101")
        Normalize.requireLength(r, i + 48, "2101")
        m.soc = Normalize.u8(r, i + 46) * 0.3922f
    }

    /** 2198, current, power limits, delta SOC (§5.1). */
    fun decodeScalars(
        r: String,
        m: BatteryModel,
    ) {
        val i = Normalize.requireTag(r, "6198")
        Normalize.requireLength(r, i + 12, "2198")
        m.currentAmps = Normalize.u16(r, i + 4) / 100f - 327.68f
        m.maxChargeHp = (Normalize.u8(r, i + 8) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(r, i + 10) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(r, i + 12) / 2f
    }

    /** 210181958798, combined fast mode (§5.1). Uses the corrected temp constant. */
    fun decodeCombined(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "6101")
        Normalize.requireLength(r, i + 48, "210181958798")
        m.soc = Normalize.u8(r, i + 46) * 0.3922f

        val j = Normalize.requireTag(r, "81", i + 48) // block-voltage section
        var kV = j + 2
        val end = if (n > 17) j + 70 else kV + n * 4 + 8
        var kR = Normalize.requireTag(r, "95", end) + 2 // IR section
        val t = Normalize.requireTag(r, "87", kR + n * 2) // temperature section
        val kT = t + 6
        val u = Normalize.requireTag(r, "98", t + 18) // current/limits section
        Normalize.requireLength(r, u + 12, "210181958798")

        val blocks = MutableList(n) { 0f }
        val ir = MutableList(n) { 0 }
        for (idx in 0 until n) {
            blocks[idx] = round(Normalize.u16(r, kV) * 0.122f) / 100f
            kV += 4
            ir[idx] = Normalize.u8(r, kR)
            kR += 2
        }
        m.blockVolts = blocks
        m.internalResistance = ir
        m.aux12V = round(Normalize.u16(r, kV) * 0.122f) / 100f - 40f

        val temps = MutableList(3) { 0f }
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(r, kT + idx * 4) / 256f - 50f // °C, not 0.007 (§5.1)
        }
        m.temps = temps

        m.currentAmps = Normalize.u16(r, u + 2) / 100f - 327.68f
        m.maxChargeHp = (Normalize.u8(r, u + 6) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(r, u + 8) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(r, u + 10) / 2f
    }
}
