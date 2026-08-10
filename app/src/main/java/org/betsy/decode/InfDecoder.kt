package org.betsy.decode

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.betsy.model.InfDetail

/**
 * PROTOCOL.md §9.4.0, the INF detail-code read: KWP2000 service `0x21` with a single-byte
 * local identifier, `21C6`..`21CA` on the HV ECU (`7E2`). Response is `61 <lid>` followed by
 * the table payload.
 *
 * - bit offsets are **MSB-first** within each byte, so "bit 0" is `0x80`;
 * - a code is **active iff its extracted value is nonzero**, the reference normalises the
 *   extraction to 0/1 and discards the magnitude, so [InfDetail.value] is informational only;
 * - a field whose range falls past the end of the response is **absent**, not an error. One
 *   layout is applied to whatever length the ECU returns. A 2009 Gen2 returns 48 bytes and so
 *   never reports the last 9 ordinals; another generation may return more.
 *
 * The payload length is therefore never assumed. Only a completely empty payload is an error.
 */
object InfDecoder {
    /**
     * Return bits [bitStart, bitEnd] of [data] as a dword, MSB-first, [bitEnd] being the
     * lowest-order bit of the range.
     *
     * Note the bottom-byte step truncates to 8 bits before
     * being shifted into place, bits above [bitStart] are **discarded**, not carried. Doing
     * this in 32 bits would leak spurious high bits for any multi-bit field that is not
     * byte-aligned (§9.4.0).
     */
    fun extract(
        data: ByteArray,
        bitStart: Int,
        bitEnd: Int,
    ): Int {
        val endByte = bitEnd shr 3
        val startByte = bitStart shr 3
        val byteSpan = endByte - startByte
        val shiftTop = 7 - (bitEnd and 7)

        var value = (data[endByte].toInt() and 0xFF) ushr shiftTop
        if (byteSpan > 1) {
            var shift = 8 - shiftTop
            for (i in endByte - 1 downTo startByte + 1) {
                value = value or ((data[i].toInt() and 0xFF) shl shift)
                shift += 8
            }
        }
        if (byteSpan > 0) {
            val truncated = ((data[startByte].toInt() and 0xFF) shl (bitStart and 7)) and 0xFF
            value = value or (truncated shl (bitEnd - bitStart - 7))
        } else {
            value = value and ((1 shl (bitEnd - bitStart + 1)) - 1)
        }
        return value
    }

    /**
     * Decode one table's response into its active INF codes.
     *
     * [r] is the normalized response to [table]'s request; a missing `61 <lid>` tag raises
     * rather than yielding a silent empty list (§2.2). Fields beyond the payload are skipped,
     * which is how a shorter-than-reference table reports itself.
     */
    fun decodeActive(
        r: String,
        table: InfTable,
    ): List<InfDetail> {
        val i = Normalize.requireTag(r, table.tag)
        var payloadBytes = (r.length - (i + 4)) / 2

        // A multi-frame response carries its ISO-TP length ahead of the tag, and the frames are
        // padded out to an 8-byte boundary, so the tail of the string is filler rather than table
        // data. A real 2009 Gen2 declares 0x032 = 50 bytes (2 tag + 48 payload) and then hands
        // over 53 bytes, the last 5 being pad.
        //
        // Believing the string length would decode the final ordinals out of that padding. It is
        // harmless while an ECU pads with 0x00, and becomes a false active INF code on any ECU
        // that pads with 0xAA or 0x55. Only ever shrinks: an absent or unparseable prefix leaves
        // the length alone rather than risking truncation of real data.
        if (i > 0) {
            val declared = r.substring(0, i).toIntOrNull(16)
            if (declared != null && declared > 2) {
                payloadBytes = minOf(payloadBytes, declared - 2)
            }
        }

        if (payloadBytes <= 0) throw NoDataException("${table.request}: no payload after ${table.tag}")
        val payload = Normalize.bytes(r, i + 4, payloadBytes)
        val availableBits = payloadBytes * 8

        val active = mutableListOf<InfDetail>()
        for (field in table.fields) {
            // out of range for this car's table -> absent, exactly as the reference reports it
            if (field.bitEnd >= availableBits) continue
            val value = extract(payload, field.bitStart, field.bitEnd)
            if (value > 0) active += InfDetail(table.lid, field.code, value)
        }
        return active
    }

    /** How many of [table]'s fields a payload of [payloadBytes] can actually report. */
    fun reportableFields(
        table: InfTable,
        payloadBytes: Int,
    ): Int = table.fields.count { it.bitEnd < payloadBytes * 8 }
}
