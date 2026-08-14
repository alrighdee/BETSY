package org.betsy.decode

/** A single INF detail-code field inside one freeze page (PROTOCOL.md §7.4). */
data class InfField(
    /** The 3-digit INF code. */
    val code: Int,
    /** Bit offset of the field's MSB, MSB-first within each byte (byte = bit shr 3). */
    val bitStart: Int,
    /** Bit offset of the field's LSB, inclusive. */
    val bitEnd: Int,
)

/** One detail-code table, addressed by its single-byte local identifier. */
data class InfTable(
    /** Local identifier sent as `21<lid>`, 0xC6..0xCA. */
    val lid: Int,
    val fields: List<InfField>,
) {
    /** `21CA`, the request that reads this table. */
    val request: String get() = "21%02X".format(lid)

    /** `61CA`, the expected positive-response tag. */
    val tag: String get() = "61%02X".format(lid)
}

/**
 * The five INF freeze pages on the hybrid-control ECU, `21C6`..`21CA` (PROTOCOL.md §7.4).
 *
 * **The sub-code is read as a value, not looked up in this map. See PROTOCOL.md §7.4.2.**
 *
 * `21C6`..`21CA` are per-DTC freeze pages, not flag tables. When the ECU stores a DTC it writes
 * one page, and the page carries the sub-code as a `u16` big-endian at **bytes 29-30**, alongside
 * a frame of analog snapshot values. Reading it needs no field map at all:
 *
 * ```
 * a page that is not all zero  ->  sub-code = u16BE(payload[29], payload[30])
 * ```
 *
 * Measured on a 2009 Gen2 with `P0571` stored: bytes 29-30 read `0x0073`, 115, which is that
 * code's documented sub-code. The page remained byte-for-byte identical across an ignition cycle,
 * so it is a stored snapshot rather than live data.
 *
 * **This type was built for a model that does not describe this ECU.** A `(code, bitStart,
 * bitEnd)` triple answers "which flag is set", and there are no flags. Treating a page's non-zero
 * bytes as active slots reported 35 simultaneous sub-codes on a car whose only fault was a brake
 * switch; those 35 were analog readings, offset-binary, which is why a car at rest shows a run of
 * `0x80` midpoints.
 *
 * **What the layout is still good for** is the other 62 fields in each page: naming and scaling
 * the analog snapshot items, currents, temperatures and voltages captured when the fault set. That
 * is a second and richer freeze frame than the generic mode 02 one, and it is worth having. If
 * this type is populated for that purpose, note that the field at bits 232-247 is 16 bits wide and
 * is the sub-code carrier, not an analog item.
 */
object InfLayout {
    val tables: List<InfTable> =
        listOf(
            InfTable(lid = 0xC6, fields = emptyList()),
            InfTable(lid = 0xC7, fields = emptyList()),
            InfTable(lid = 0xC8, fields = emptyList()),
            InfTable(lid = 0xC9, fields = emptyList()),
            InfTable(lid = 0xCA, fields = emptyList()),
        )

    fun table(lid: Int): InfTable? = tables.firstOrNull { it.lid == lid }
}
