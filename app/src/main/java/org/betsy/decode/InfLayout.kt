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
 * **The field map is intentionally empty, and the bit-field model below is now known to be the
 * wrong shape for this ECU. Read PROTOCOL.md §7.4.2 before extending this.**
 *
 * Established by observation: `7E2` answers these five identifiers, each with a `61 <lid>`
 * positive response carrying a 48-byte payload. BETSY reads all five and records them verbatim,
 * which is what the capture pipeline uploads, and that is what made the finding below possible.
 *
 * A Gen2 has been read with `P0571` deliberately stored on `7E2`, the first faulted HV
 * ECU this project has seen. Exactly one table changed, `21C7`, and byte 30 of its payload held
 * `0x73`, decimal **115**, which is the INF documented for that code. So the ECU writes the INF
 * **number itself as a value**; it does not set a flag bit at a per-code offset. A `(code,
 * bitStart, bitEnd)` triple cannot express that, which is why no field map was ever findable and
 * why leaving this empty was right rather than merely cautious.
 *
 * One fault is not enough to ship a decoder. It cannot separate a fixed offset from a
 * coincidence, cannot tell `u8` at byte 30 from `u16` at 29..30, and does not explain what
 * selects `21C7` over the other four. A second faulted car settles all three. Until then
 * [InfDecoder.decodeActive] returns nothing and the bytes travel intact, which is the behaviour
 * that turned one afternoon's fault into a usable measurement.
 *
 * When that second data point arrives, replace this type rather than populating it: the
 * replacement reads a value out of a record, it does not test bits.
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
