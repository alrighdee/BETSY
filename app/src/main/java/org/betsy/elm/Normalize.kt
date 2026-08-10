package org.betsy.elm

/**
 * Explicit no-data error. PROTOCOL.md §2 failure-mode note: the app lets indexOf run to -1 and
 * silently returns stale values; a clean implementation treats "tag not found" as an error.
 */
class NoDataException(
    message: String,
) : Exception(message)

/**
 * PROTOCOL.md §2 response normalization. Applied to every response before decoding; the result R
 * is one continuous uppercase hex string, and all spec offsets are hex-char indices into R.
 */
object Normalize {
    // §2 step 1: ISO-TP frame counters like "0:" / "1:"
    private val FRAME_COUNTER = Regex("[0-9A-F]:")

    // §2 step 2: everything that is not a hex digit
    private val NON_HEX = Regex("[^A-F0-9]")

    fun normalize(raw: String): String {
        // Uppercase first so lowercase-hex adapters survive the non-hex strip.
        val up = raw.uppercase()
        return NON_HEX.replace(FRAME_COUNTER.replace(up, ""), "")
    }

    /** u8(k), one byte at hex-char index k (§2.1). Callers length-guard via [requireLength]. */
    fun u8(
        r: String,
        k: Int,
    ): Int = r.substring(k, k + 2).toInt(16)

    /** u16(k), two big-endian bytes at hex-char index k (§2.1). */
    fun u16(
        r: String,
        k: Int,
    ): Int = r.substring(k, k + 4).toInt(16)

    /** bytes(k, n), n bytes starting at hex-char index k (§2.1). Callers length-guard. */
    fun bytes(
        r: String,
        k: Int,
        n: Int,
    ): ByteArray {
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = (r.substring(k + i * 2, k + i * 2 + 2).toInt(16)).toByte()
        }
        return out
    }

    /** §2 anchor: index of the positive-response tag, or an explicit no-data error. */
    fun requireTag(
        r: String,
        tag: String,
        fromIndex: Int = 0,
    ): Int {
        val i = r.indexOf(tag, fromIndex)
        if (i < 0) throw NoDataException("tag $tag not found in response ($r)")
        return i
    }

    fun requireLength(
        r: String,
        needed: Int,
        what: String,
    ) {
        if (r.length < needed) {
            throw NoDataException("$what: truncated response (${r.length} < $needed hex chars)")
        }
    }
}
