package org.betsy.elm

/**
 * An ISO 14229 negative response, `7F <service> <NRC>`, the ECU understood the frame and is
 * refusing it.
 *
 * The adapter passes these through as ordinary data, so unrecognized they reach the decoders and
 * surface as a confusing "missing tag" failure. A Gen2 HV ECU answers `7F 22 11` to the §9.4 INF
 * read (mode 22 is newer than the car), which should read as "this ECU does not support that
 * request", not as a decode error.
 */
data class NegativeResponse(
    /** The service that was refused, as two hex chars, `22` for a mode-22 read. */
    val service: String,
    /** Response code, ISO 14229 Annex A. */
    val nrc: Int,
) {
    val meaning: String get() = NAMES[nrc] ?: String.format("refused with NRC 0x%02X", nrc)

    override fun toString(): String = "ECU refused mode $service: $meaning"

    companion object {
        private val NAMES =
            mapOf(
                0x11 to "service not supported",
                0x12 to "sub-function not supported",
                0x13 to "wrong message length",
                0x22 to "conditions not correct",
                0x31 to "request out of range",
                0x33 to "security access denied",
                0x78 to "response pending",
                // The codes an ECU returns when a service exists but is session-gated. Absent here,
                // a real session refusal would have printed as a bare hex NRC.
                0x7F to "service not supported in the active session",
                0x80 to "service not supported in the active diagnostic mode",
            )

        /**
         * Parses a §2-normalized response, or null when it is not a refusal. Only a response that
         * *begins* with `7F` counts: `7F` also occurs as ordinary payload data, and treating that as
         * a refusal would discard good frames.
         */
        fun parse(r: String): NegativeResponse? {
            if (!r.startsWith("7F") || r.length < 6) return null
            val service = r.substring(2, 4)
            val nrc = r.substring(4, 6).toIntOrNull(16) ?: return null
            return NegativeResponse(service, nrc)
        }
    }
}

/** Thrown when an ECU refuses a request outright (see [NegativeResponse]). */
class NegativeResponseException(
    val response: NegativeResponse,
    cmd: String,
) : Exception("$cmd: $response")
