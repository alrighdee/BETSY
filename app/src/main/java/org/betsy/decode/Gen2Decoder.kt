package org.betsy.decode

import org.betsy.elm.NoDataException
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
    /** Unsigned 16-bit midpoint encoding; the signed result is in hundredths of an amp or volt. */
    private fun offsetBinaryHundredths(raw: Int): Float = (raw - 32768) / 100f

    /** The implemented P4 layout has 17 CE voltage words and at most 3 CF spillover words. */
    private const val MAX_BLOCK_COUNT = 20

    /** Read D0 byte 0 and require the full preamble and all reported resistance bytes (§5.2). */
    fun readBlockCount(r: String): Int {
        val response = message(r, "61D0")
        val i = 0
        Normalize.requireLength(response, i + 34, "21D0")
        val count = Normalize.u8(response, i + 4)
        if (count !in 1..MAX_BLOCK_COUNT) throw NoDataException("21D0: unsupported block count $count")
        Normalize.requireLength(response, i + 34 + count * 2, "21D0")
        return count
    }

    /** 21CE, SOC, current, block voltages 0..min(N,17)−1 (§5.2). */
    fun decodeCe(
        r: String,
        m: BatteryModel,
    ) {
        val n = m.blockCount
        val response = message(r, "61CE")
        val i = 0
        val count = minOf(n, 17)
        Normalize.requireLength(response, i + 10 + count * 4, "21CE")
        m.soc = Normalize.u8(response, i + 4) / 2f
        m.currentAmps = offsetBinaryHundredths(Normalize.u16(response, i + 6))
        val blocks = MutableList(n) { 0f }
        var k = i + 10 // blocks start right after current (§5.2)
        for (idx in 0 until count) {
            blocks[idx] = offsetBinaryHundredths(Normalize.u16(response, k))
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
        val response = message(r, "61CF")
        val i = 0
        var k = i + 4
        Normalize.requireLength(response, k + maxOf(n - 17, 0) * 4 + 32, "21CF")
        val blocks = m.blockVolts.ifEmpty { MutableList(n) { 0f } }.toMutableList()
        if (n > 17) {
            // spillover blocks 18..N live at the start of this response (§5.2)
            for (idx in 17 until n) {
                blocks[idx] = offsetBinaryHundredths(Normalize.u16(response, k))
                k += 4
            }
        }
        val s = if (fifteenBlockVariant) -4 else 0 // p044o1 scalars sit 4 hex chars earlier
        m.aux12V = (Normalize.u8(response, k + 6 + s) * 2 - 256) / 10f
        m.maxChargeHp = (Normalize.u8(response, k + 8 + s) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(response, k + 10 + s) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(response, k + 12 + s) * 0.01f
        val temps = MutableList(3) { 0f }
        var kt = k + 20 // skips the intake word; TB1..TB3 (§5.2)
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(response, kt) / 100f - 327.68f // °C (offset-binary)
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
        val reported = readBlockCount(r)
        if (reported != n) throw NoDataException("21D0: block count $reported does not match monitor count $n")
        val response = message(r, "61D0")
        val i = 0
        val k = i + 34 // skips the 4-char tag + 15 bytes of mapped preamble (§5.2)
        val ir = MutableList(n) { 0 }
        for (idx in 0 until n) {
            ir[idx] = Normalize.u8(response, k + idx * 2)
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
        val response = message(r, "61CE")
        val i = 0
        val d = i + 10 + minOf(n, 17) * 4 // CE tag, SOC, current, block voltages.
        val c = d + 32 + n * 2 // D0 tag, 15-byte preamble, one IR byte per block.
        val spilloverChars = maxOf(n - 17, 0) * 4
        Normalize.requireLength(response, c + 34 + spilloverChars, "21CED0CF")
        if (!response.startsWith("D0", d)) throw NoDataException("21CED0CF: expected D0 after CE payload")
        if (!response.startsWith("CF", c)) throw NoDataException("21CED0CF: expected CF after D0 payload")
        val reported = Normalize.u8(response, d + 2)
        if (reported != n) throw NoDataException("21CED0CF: block count $reported does not match monitor count $n")

        m.soc = Normalize.u8(response, i + 4) / 2f
        m.currentAmps = offsetBinaryHundredths(Normalize.u16(response, i + 6))

        val blocks = MutableList(n) { 0f }
        val ir = MutableList(n) { 0 }
        var kV = i + 10
        var kR = d + 32 // "D0" tag + 15 bytes preamble, same +34 as standalone (§5.2)
        for (idx in 0 until n) {
            if (idx < 17) {
                blocks[idx] = offsetBinaryHundredths(Normalize.u16(response, kV))
                kV += 4
            }
            ir[idx] = Normalize.u8(response, kR)
            kR += 2
        }

        var k = c + 2
        if (n > 17) {
            for (idx in 17 until n) {
                blocks[idx] = offsetBinaryHundredths(Normalize.u16(response, k))
                k += 4
            }
        }
        val s = if (fifteenBlockVariant) -4 else 0
        m.aux12V = (Normalize.u8(response, k + 6 + s) * 2 - 256) / 10f
        m.maxChargeHp = (Normalize.u8(response, k + 8 + s) / 2f - 64f) * 1.34f
        m.maxDischargeHp = (Normalize.u8(response, k + 10 + s) / 2f - 64f) * 1.34f
        m.deltaSoc = Normalize.u8(response, k + 12 + s) * 0.01f
        val temps = MutableList(3) { 0f }
        var kt = k + 20
        for (idx in 0 until 3) {
            temps[idx] = Normalize.u16(response, kt) / 100f - 327.68f // °C
            kt += 4
        }
        m.temps = temps
        m.blockVolts = blocks
        m.internalResistance = ir
    }

    /** Accept only the expected application start, optionally preceded by an ELM length. */
    private fun message(
        r: String,
        tag: String,
    ): String {
        if (r.startsWith(tag)) {
            Normalize.requireWholeBytes(r, tag)
            return r
        }
        if (!r.startsWith(tag, 3)) throw NoDataException("$tag: unexpected response service or PID")
        val declared = r.take(3).toIntOrNull(16)
        if (declared == null || declared < 2) throw NoDataException("$tag: invalid response length prefix")
        val end = 3 + declared * 2
        Normalize.requireLength(r, end, tag)
        return r.substring(3, end)
    }
}
