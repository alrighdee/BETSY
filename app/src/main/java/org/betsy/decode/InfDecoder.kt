package org.betsy.decode

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.betsy.model.InfDetail

/**
 * PROTOCOL.md §7.4.2, reading the INF sub-code out of a freeze page.
 *
 * `21C6`..`21CA` on `7E2` are **per-DTC freeze pages**, not flag tables. When the ECU stores a DTC
 * it writes one page, and the page carries the sub-code as a `u16` big-endian at **bytes 29-30**
 * of the payload, alongside a frame of analog snapshot values.
 *
 * So decoding is a field read, not a search:
 *
 * ```
 * page not all zero  ->  sub-code = u16BE(payload[29], payload[30])
 * ```
 *
 * Measured on a 2009 Gen2 with `P0571` stored: bytes 29-30 read `0x0073`, decimal 115, which is
 * that code's documented sub-code. All 48 bytes remained identical across an ignition cycle, so a
 * page is a snapshot written when the fault sets rather than live data.
 *
 * **Why there is no bit map here.** An earlier model treated each page as a table of flags and
 * called a slot active when non-zero. On a car whose only fault was a brake switch that reported
 * **35 simultaneous sub-codes**: those bytes are analog readings in offset binary, which is why a
 * car at rest shows a run of `0x80` midpoints. `InfLayout` still describes those 62 analog items
 * for naming and scaling, but it plays no part in reading the sub-code.
 */
object InfDecoder {
    /** Byte offset of the sub-code field within a page payload; 16 bits, big-endian. */
    const val CODE_BYTE = 29

    /** Sub-codes are three digits. Anything outside this is not one, and is not reported. */
    private val PLAUSIBLE = 100..999

    /**
     * The sub-code carried by one page, or null when the page carries none.
     *
     * Null covers three distinct cases, all of them normal: the page is all zero because no DTC
     * wrote it, the payload is too short to reach the field, or the field holds something outside
     * the three-digit range. None is an error, so none throws. A missing `61 <lid>` tag does
     * throw, because that means the read itself failed (§2.2).
     */
    fun decodeActive(
        r: String,
        table: InfTable,
    ): List<InfDetail> {
        val i = Normalize.requireTag(r, table.tag)
        var bytes = (r.length - (i + 4)) / 2

        // A multi-frame reply declares its length ahead of the tag and pads the final frame, so
        // the tail of the string is filler. Believing the string length would read padding as
        // data. Only ever shrinks.
        if (i > 0) {
            val declared = r.substring(0, i).toIntOrNull(16)
            if (declared != null && declared > 2) bytes = minOf(bytes, declared - 2)
        }
        if (bytes <= 0) throw NoDataException("${table.request}: no payload after ${table.tag}")
        if (bytes <= CODE_BYTE + 1) return emptyList()

        val payload = Normalize.bytes(r, i + 4, bytes)
        if (payload.all { it.toInt() == 0 }) return emptyList()

        val code =
            ((payload[CODE_BYTE].toInt() and 0xFF) shl 8) or
                (payload[CODE_BYTE + 1].toInt() and 0xFF)
        if (code !in PLAUSIBLE) return emptyList()
        return listOf(InfDetail(table = table.lid, code = code))
    }
}
