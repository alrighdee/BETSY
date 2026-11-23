package org.betsy.decode

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.betsy.model.InfDetail

/**
 * PROTOCOL.md §7.4.2: read the unsigned detail value from a supported Gen2 compact freeze page.
 *
 * `21C6`..`21CA` on `7E2` carry snapshot data, not tables of active INF flags. In the captured
 * 48-byte layout, payload bytes 29-30 contain the big-endian detail value; a recorded P0571 page
 * carries 0x0073 (115). A nonzero value is evidence, not proof of ownership by a particular DTC.
 *
 * Other snapshot values are retained as raw data. They are not decoded by InfLayout, whose legacy
 * field lists are empty, and must not be treated as a uniform set of offset-binary analog items.
 */
object InfDecoder {
    /** Byte offset of the sub-code field within a page payload; 16 bits, big-endian. */
    const val CODE_BYTE = 29

    /** Complete payload size of the supported Gen2 compact freeze-page layout. */
    private const val PAGE_BYTES = 48

    /**
     * The sub-code carried by one complete page, or an empty list when it carries none.
     *
     * An all-zero page or a zero detail field yields no entry. Nonzero unsigned 16-bit
     * values are retained even when their meaning is unknown; interpretation is separate.
     * Incomplete pages and missing `61 <lid>` tags throw because the read failed (§2.2).
     * A length-prefixed response must contain every declared byte before padding is ignored.
     */
    fun decodeActive(
        r: String,
        table: InfTable,
    ): List<InfDetail> {
        val i = Normalize.requireTag(r, table.tag)
        if (i == 0) Normalize.requireWholeBytes(r, table.request)
        val payloadStart = i + 4
        var bytes = (r.length - payloadStart) / 2

        // ELM multi-frame output can prefix the tag with a three-hex-digit application length,
        // including the service and local identifier. Require the whole declared response.
        if (i > 0) {
            val declared = if (i == 3) r.substring(0, i).toIntOrNull(16) else null
            if (declared == null || declared < 2) {
                throw NoDataException("${table.request}: invalid response length prefix")
            }
            Normalize.requireLength(r, i + declared * 2, table.request)
            bytes = declared - 2
        }
        if (bytes < PAGE_BYTES) {
            throw NoDataException("${table.request}: truncated INF page ($bytes < $PAGE_BYTES payload bytes)")
        }

        val payload = Normalize.bytes(r, i + 4, bytes)
        if (payload.all { it.toInt() == 0 }) return emptyList()

        val code =
            ((payload[CODE_BYTE].toInt() and 0xFF) shl 8) or
                (payload[CODE_BYTE + 1].toInt() and 0xFF)
        if (code == 0) return emptyList()
        return listOf(InfDetail(table = table.lid, code = code))
    }
}
