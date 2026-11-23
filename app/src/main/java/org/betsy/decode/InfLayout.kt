package org.betsy.decode

/** Legacy slot metadata; unused by value-based freeze-page INF decoding (PROTOCOL.md §7.4). */
data class InfField(
    /** Legacy slot identifier; not a decimal-width limit on the decoded detail value. */
    val code: Int,
    /** Bit offset of the field's MSB, MSB-first within each byte (byte = bit shr 3). */
    val bitStart: Int,
    /** Bit offset of the field's LSB, inclusive. */
    val bitEnd: Int,
)

/** One freeze page, addressed by its single-byte local identifier. */
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
 * The five captured hybrid-control freeze pages, `21C6`..`21CA` (PROTOCOL.md §7.4).
 *
 * The supported compact layout carries an unsigned 16-bit detail value at payload bytes 29-30.
 * InfDecoder reads that value directly; neither the page index nor a set bit identifies its DTC.
 *
 * The legacy field lists remain empty. This class does not name or scale the remaining snapshot
 * values. Shared definitions contain numeric values, enums and individual flags; they are not a
 * list of fields confirmed on this vehicle. Additional decoding requires the selected ECU's byte
 * support mapping. Expanded-buffer bit offsets cannot be assumed to index the compact wire data.
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
