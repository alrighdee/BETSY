package org.betsy.capture

/**
 * Removal of vehicle identifiers from anything about to leave the phone.
 *
 * This is a privacy protection, and it is the owner's privacy at stake rather than the project's.
 * A capture is published to a public repository, and a VIN identifies the car and frequently the
 * person who owns it. Someone sharing a battery scan is offering us their fault data, not their
 * registration details, and the difference is not theirs to have to police.
 *
 * The hazard is real rather than hypothetical: the HV ECU on a Gen2 answers SAE mode 09, and
 * `0902` returns the VIN. measured on-car.
 *
 * Nothing in the app requests mode 09 today, so in principle this never fires. That is exactly
 * why it exists: the protection currently lives in the *absence* of a line of code, and the day
 * someone adds a mode 09 read for some other PID, the VIN would start travelling with every
 * upload and nothing would object. This turns "we happen not to ask" into "it cannot leave".
 *
 * The framing matters and is easy to get wrong. An ELM327 splits a long reply across `N:` line
 * indices, so a VIN arrives as
 *
 * ```
 * 014 0:490201314847 1:424834314A584D 2:4E313039313836
 * ```
 *
 * A pattern anchored on `490201` alone matches the first frame and leaves the remainder, which
 * includes the serial portion, sitting in the payload. Both forms are handled below, and
 * `RedactTest` pins the multi-frame case specifically.
 */
object Redact {
    /** Positive response to mode 09 PID 02, the service that returns the VIN. */
    private const val VIN_TAG = "4902"

    private val singleFrame = Regex("(?i)($VIN_TAG ?0?1)(?:[0-9A-F]{2}){4,}")

    /** `0:`/`1:`/`2:` continuation indices and their payloads, once a VIN tag has been seen. */
    private val multiFrameTail = Regex("(?i)($VIN_TAG ?0?1)[0-9A-F: ]*")

    const val MARKER = "<VIN-REDACTED>"

    /**
     * Strip any mode-09 VIN payload from [s], continuation frames included. Text that contains no
     * VIN response is returned unchanged, so this is safe to apply to every value.
     */
    fun vin(s: String): String {
        if (!s.contains(VIN_TAG, ignoreCase = true)) return s
        return s
            .replace(multiFrameTail) { m -> m.groupValues[1] + MARKER }
            .replace(singleFrame) { m -> m.groupValues[1] + MARKER }
    }

    /** [vin] applied across a raw request-to-response map. */
    fun vin(raw: Map<String, String>): Map<String, String> = raw.mapValues { (_, v) -> vin(v) }

    /** [vin] applied across session log lines. */
    fun vin(lines: List<String>): List<String> = lines.map { vin(it) }
}
