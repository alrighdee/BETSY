package org.betsy.capture

import org.betsy.detect.VehicleInfo
import org.betsy.dtc.DtcReadResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shareable capture: the bytes a car answered with, plus enough context to interpret them.
 *
 * **No vehicle identifier may be added to this type.** A capture is uploaded to a public
 * repository, and a VIN identifies the car and frequently its owner. The HV ECU does answer SAE
 * mode 09 (`0902` returns the VIN, verified on a 2009 Gen2), so this is a live hazard rather than
 * a theoretical one: nothing in the sweep requests it, and [raw] must never be widened to carry a
 * response that contains one. If a future read needs mode 09 for some other PID, redact the VIN
 * before it reaches [CaptureData], not afterwards.
 *
 * The split between [raw] and [codes] is the whole point of this type. `InfLayout`'s bit mapping
 * has never been checked against a car with a real fault (§9.4.0), so [codes] is a hypothesis,
 * not a finding. It travels beside the bytes and never in place of them, and nothing downstream
 * is allowed to decide a capture's worth by looking at it: a decoder that is wrong would then
 * discard exactly the captures that prove it wrong.
 */
data class CaptureData(
    /** App version, from BuildConfig. */
    val version: String,
    /**
     * Short git identity of the tree this APK was built from, from `BuildConfig.GIT_HASH`, with a
     * trailing `+` when the tree was dirty.
     *
     * [version] alone cannot identify the code that produced a capture: it only moves when a
     * release is cut, so every unreleased commit shares one number and a capture can name a version
     * the phone was not running. The read path changes between captures — a probe added, a decoder
     * corrected — and a capture that cannot be tied to the build that made it cannot be re-read
     * later against what that build actually did.
     */
    val build: String,
    /** Detected generation and pack shape, e.g. "Gen2 (2004-2009) 14 blocks / 28 cells". */
    val car: String,
    /** Adapter identity from the ELM327 banner. */
    val elm: String,
    /** Every request issued during the sweep mapped to its verbatim response. */
    val raw: Map<String, String>,
    /** Stored DTCs as read, formatted "<ecu label>: <code>". */
    val dtcs: List<String>,
    /** Per-read failures from the sweep. A table that refused says so here. */
    val notes: List<String>,
    /** The decoder's current reading of [raw]. Advisory. May be empty on a faulty car. */
    val codes: List<InfCode>,
    /** Bounded tail of the session log, for timing and failure context around the sweep. */
    val logTail: List<String>,
    /**
     * Whether the ECU reported stored DTCs. Routing keys on this, never on [codes] being
     * non-empty, so a fault the decoder cannot name is still filed as a real capture.
     */
    val hasStoredDtcs: Boolean,
    /**
     * What the owner already knows, in their words. Raw hex says which bits are set; it cannot
     * say what fault they represent. An owner holding an independent readout is often the only
     * thing that turns a capture into an answer.
     */
    val ownerNotes: String = "",
) {
    fun toJson(): String =
        JSONObject()
            .put("version", version)
            .put("build", build)
            .put("car", car)
            .put("elm", elm)
            .put("raw", JSONObject().also { o -> raw.forEach { (k, v) -> o.put(k, v) } })
            .put("dtcs", JSONArray(dtcs))
            .put("notes", JSONArray(notes))
            .put(
                "codes",
                JSONArray().also { arr ->
                    codes.forEach { arr.put(JSONObject().put("table", it.table).put("code", it.code)) }
                },
            ).put("logTail", JSONArray(logTail))
            .put("hasStoredDtcs", hasStoredDtcs)
            .put("ownerNotes", ownerNotes)
            // Always false: anything the app sends came off a car. The flag exists so test
            // harnesses can mark their own submissions, which keeps a healthy real car filed as
            // a real capture instead of beside a curl probe.
            .put("synthetic", false)
            .toString()

    companion object {
        /** Lines of session log carried along; enough for context, small enough for the endpoint. */
        const val LOG_TAIL_LINES = 120

        fun from(
            result: DtcReadResult,
            info: VehicleInfo,
            elm: String,
            version: String,
            build: String,
            logTail: List<String>,
            ownerNotes: String = "",
        ): CaptureData =
            CaptureData(
                version = version,
                build = build,
                car = "${info.model.label} ${info.blockCount} blocks / ${info.cellCount} cells",
                elm = elm.ifBlank { "unknown" },
                raw = result.rawResponses,
                dtcs = result.groups.flatMap { g -> g.codes.map { "${g.label}: ${it.code}" } },
                notes = result.notes,
                codes = result.infCodes.map { InfCode(it.tableLabel, it.code) },
                logTail = logTail.takeLast(LOG_TAIL_LINES),
                hasStoredDtcs = result.hasStoredDtcs,
                ownerNotes = ownerNotes,
            )
    }
}

/** One decoded INF detail code, flattened for transport. */
data class InfCode(
    val table: String,
    val code: Int,
)
