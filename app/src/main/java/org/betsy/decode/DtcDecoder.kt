package org.betsy.decode

import org.betsy.elm.Normalize
import org.betsy.model.Dtc

/**
 * PROTOCOL.md §9.1–9.3, DTC reads and the count-byte parser.
 *
 * Toyota's DTC reads are KWP2000-style `13 <mask>` (Gen2 and Gen3 layouts), SAE `0A` permanent DTCs
 * (Gen3), and mode `03`/`07` (Gen1/Gen4 engine). Responses carry **no per-DTC status byte**:
 * `53 <count> <DTC 2B>…` (§9.2, confirmed by Selidori capture). The `k.a` count-byte parser
 * (§9.3) is the one to build the M2 reader on; the `k.b`/`k.c` K-line chained-length probes
 * (Gen1 / Gen4.5 engine) are treated as best-effort until a live K-line capture (§9.3 note).
 *
 * [format] maps the first hex digit of each 2-byte DTC to its J2012 letter class (§9.3).
 */
object DtcDecoder {
    /** §9.3, `d(ch)`: letter = class of the high nibble, trailing digit = its low 2 bits. */
    fun letter(firstNibble: Int): Char =
        when (firstNibble) {
            in 0x0..0x3 -> 'P'
            in 0x4..0x7 -> 'C'
            in 0x8..0xB -> 'B'
            else -> 'U'
        }

    /** §9.3, `3019` → `P3019`, `0A80` → `P0A80`, `C112` → `U0112`. */
    fun format(raw: Int): String {
        val nibble = raw ushr 12
        val hex = "%04X".format(raw)
        return "${letter(nibble)}${nibble and 0x3}${hex.substring(1)}"
    }

    /**
     * §9.3 `k.a`, count-byte parser for `53`/`4A`/`43`/`47` responses.
     * Locates [tag], reads the count byte, then walks count × 2-byte DTCs. When the count
     * overstates the response length, trusts the actual length (defensive, as the app does).
     */
    fun decodeCounted(
        r: String,
        tag: String,
    ): List<Dtc> {
        val i = Normalize.requireTag(r, tag)
        Normalize.requireLength(r, i + 2, tag)
        var k = i + 2
        var count = Normalize.u8(r, k)
        k += 2
        val available = (r.length - k) / 4
        if (count > available) count = available
        return (0 until count).map {
            val raw = Normalize.u16(r, k + it * 4)
            Dtc(raw = raw, code = format(raw))
        }
    }

    /** §9.1, mode 13 (`13 <mask>`, response tag `53`). */
    fun decodeMode13(r: String): List<Dtc> = decodeCounted(r, "53")

    /** §9.1, SAE mode 0A permanent DTCs (Gen3 first read, response tag `4A`). */
    fun decodeMode0A(r: String): List<Dtc> = decodeCounted(r, "4A")

    /** §9.1, mode 03 stored DTCs (response tag `43`). */
    fun decodeMode03(r: String): List<Dtc> = decodeCounted(r, "43")

    /** §9.1, mode 07 pending DTCs (response tag `47`). */
    fun decodeMode07(r: String): List<Dtc> = decodeCounted(r, "47")
}
