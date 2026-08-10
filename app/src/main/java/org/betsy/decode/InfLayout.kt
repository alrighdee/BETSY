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
 * **The field map is intentionally empty in this repository.**
 *
 * What is established by observation, and is what this object provides, is the read itself: `7E2`
 * answers these five identifiers, each with a `61 <lid>` positive response carrying a 48-byte
 * payload. That is enough for BETSY to read every table and record it verbatim, which is what the
 * capture pipeline uploads.
 *
 * What is *not* established is which bit of that payload means which 3-digit INF code. No car
 * BETSY has read has ever had a bit set, so nothing in the collected data supports a mapping.
 * Publishing one here would assert a correspondence the project cannot currently demonstrate.
 *
 * The consequence is deliberate: [InfDecoder.decodeActive] returns nothing, every capture from a
 * car with a fault is flagged `decoder_miss`, and the raw bytes travel intact for analysis. That
 * is the honest state of the work, and those captures are the route to a field map this project
 * can stand behind.
 *
 * Supplying a populated `fields` list here is all that is needed to enable decoding.
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
