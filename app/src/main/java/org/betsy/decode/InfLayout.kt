package org.betsy.decode

/** A single INF detail-code field inside one table's response (PROTOCOL.md §9.4.0). */
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
 * The five INF detail tables on the HV ECU, `21C6`..`21CA` (PROTOCOL.md §9.4.0).
 *
 * **The field map is intentionally empty, and a faulted car has now shown that filling it is not
 * simply a matter of obtaining the right table. Read PROTOCOL.md §7.4.2 first.**
 *
 * Established by observation: `7E2` answers these five identifiers, each with a `61 <lid>`
 * positive response carrying a 48-byte payload. BETSY reads all five and records them verbatim,
 * which is what the capture pipeline uploads, and that is what made the finding below possible.
 *
 * A Gen2 has been read with `P0571` stored on `7E2`, the first faulted HV ECU this
 * project has seen. Exactly one table changed, `21C7`. Two things follow, and both constrain any
 * future implementation of this type:
 *
 * 1. Treating each byte as a slot and calling a slot active when non-zero reports **35
 *    simultaneous sub-codes** for a brake-switch fault. Whatever those bytes are, that is not it.
 * 2. `P0571` carries INF **115**, and the tables are partitioned by hundreds digit, 2xx..6xx.
 *    There is no 1xx table, so that sub-code cannot appear in any of these five. A stored DTC
 *    does not imply its INF is readable here.
 *
 * So an empty map is not a gap waiting on data; it reflects that the payload's meaning is
 * genuinely unresolved. What would resolve it is a car with a stored **2xx-6xx** DTC, where the
 * sub-code is expressible and the corresponding table can be checked directly.
 *
 * Until then [InfDecoder.decodeActive] returns nothing and the bytes travel intact, which is the
 * behaviour that made the measurement possible at all.
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
