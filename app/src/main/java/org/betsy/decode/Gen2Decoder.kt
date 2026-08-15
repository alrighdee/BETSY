package org.betsy.decode

import org.betsy.elm.Normalize
import org.betsy.model.BatteryModel

/**
 * PROTOCOL.md §5.2, Gen2 (`ATSH7E3`) and the `7E2` Gen2 layout (`ATSH7E2`), mode 21.
 *
 * Two decoder families: the general case (`p041n1/`) and the `N == 15` variant (`p044o1/`) whose
 * 21CF scalar offsets sit 4 hex chars earlier. [fifteenBlockVariant] selects the latter.
 *
 * Temps are decoded in Celsius directly as `u16/100 − 327.68` (the offset-binary container, §5.2
 * correction), the doc's `°F = 0.018·u16 − 557.824` is exactly that converted. The model stores
 * °C; unit conversion is a display-layer concern.
 */
object Gen2Decoder {
    /** 21CE, SOC, current, block voltages 0..min(N,17)−1 (§5.2). */
    fun decodeCe(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "61CE")
        val count = minOf(n, 17)
        Normalize.requireLength(r, i + 4 + count * 4, "21CE")
        m.soc = Normalize.u8(r, i + 4) / 2f
        m.currentAmps = Normalize.u16(r, i + 6) / 100f - 327.68f
        val blocks = MutableList(n) { 0f }
        var k = i + 10 // blocks start right after current (§5.2)
        for (idx in 0 until count) {
            blocks[idx] = Normalize.u16(r, k) / 100f - 327.68f
            k += 4
        }
        m.blockVolts = blocks
    }

    /** 21CF, spillover blocks, limits, temperatures (§5.2). */
    fun decodeCf(
        r: String,
        m: BatteryModel,
        fifteenBlockVariant: Boolean,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "61CF")
        var k = i + 4
        val blocks = m.blockVolts.ifEmpty { MutableList(n) { 0f } }.toMutableList()
        if (n > 17) {
            // spillover blocks 18..N live at the start of this response (§5.2)
            for (idx in 17 until n) {
                blocks[idx] = Normalize.u16(r, k) / 100f - 327.68f
                k += 4
            }
        }
        Normalize.requireLength(r, k + 36, "21CF")
        val s = if (fifteenBlockVariant) -4 else 0 // p044o1 scalars sit 4 hex chars earlier
        m.aux12V = Normalize.u8(r, k + 6 + s) * 0.2f - 25.6f
        m.maxChargeHp = (Normalize.u8(r, k + 8 + s) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(r, k + 10 + s) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(r, k + 12 + s) * 0.01f
        val temps = MutableList(3) { 0f }
        var kt = k + 20 // skips the intake word; TB1..TB3 (§5.2)
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(r, kt) / 100f - 327.68f // °C (offset-binary)
            kt += 4
        }
        m.temps = temps
        m.blockVolts = blocks
    }

    /** 21D0, internal resistance, 1 raw unit = 1 mΩ (§4.1, §5.2). */
    fun decodeD0(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "61D0")
        val k = i + 34 // skips the 4-char tag + 15 bytes of mapped preamble (§5.2)
        Normalize.requireLength(r, k + n * 2, "21D0")
        val ir = MutableList(n) { 0 }
        for (idx in 0 until n) {
            ir[idx] = Normalize.u8(r, k + idx * 2)
        }
        m.internalResistance = ir
    }

    /** 21CED0CF, combined fast-mode request (§5.2). */
    fun decodeCombined(
        r: String,
        m: BatteryModel,
        fifteenBlockVariant: Boolean,
    ) {
        val n = m.blockCount
        val i = Normalize.requireTag(r, "61CE")
        val end = if (n > 17) i + 72 else i + 4 + n * 4
        val d = Normalize.requireTag(r, "D0", end) // IR section
        val c = Normalize.requireTag(r, "CF", d + 2 + n * 2) // spillover/limits/temps section
        // 21CF payload is 16 bytes: aux, three limits, ΔSOC, then TB1–TB3 (u16) ending at byte 15.
        // The measured acceptance fixture (PROTOCOL.md §5.2) is 34 chars here, tag included.
        Normalize.requireLength(r, c + 34, "21CED0CF")

        m.soc = Normalize.u8(r, i + 4) / 2f
        m.currentAmps = Normalize.u16(r, i + 6) / 100f - 327.68f

        val blocks = MutableList(n) { 0f }
        val ir = MutableList(n) { 0 }
        var kV = i + 10
        var kR = d + 32 // "D0" tag + 15 bytes preamble, same +34 as standalone (§5.2)
        for (idx in 0 until n) {
            if (idx < 17) {
                blocks[idx] = Normalize.u16(r, kV) / 100f - 327.68f
                kV += 4
            }
            ir[idx] = Normalize.u8(r, kR)
            kR += 2
        }

        var k = c + 2
        if (n > 17) {
            for (idx in 17 until n) {
                blocks[idx] = Normalize.u16(r, k) / 100f - 327.68f
                k += 4
            }
        }
        val s = if (fifteenBlockVariant) -4 else 0
        m.aux12V = Normalize.u8(r, k + 6 + s) * 0.2f - 25.6f
        m.maxChargeHp = (Normalize.u8(r, k + 8 + s) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(r, k + 10 + s) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(r, k + 12 + s) * 0.01f
        val temps = MutableList(3) { 0f }
        var kt = k + 20
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(r, kt) / 100f - 327.68f // °C
            kt += 4
        }
        m.temps = temps
        m.blockVolts = blocks
        m.internalResistance = ir
    }
}
