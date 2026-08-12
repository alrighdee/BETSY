package org.betsy.dtc

import org.betsy.debug.CaptureLog
import org.betsy.decode.DtcDecoder
import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmSession
import org.betsy.elm.NegativeResponse
import org.betsy.elm.NegativeResponseException
import org.betsy.elm.Normalize
import org.betsy.model.Dtc
import org.betsy.model.InfDetail

/** One labeled DTC group from one ECU's read (PROTOCOL.md §7.1). */
data class DtcGroup(
    val label: String,
    val codes: List<Dtc>,
)

/**
 * Result of the 7E2 liveness probe (`0100`). [responding] is true only when the ECU itself
 * answered: positive mode-01 (`41…`) or a negative response (`7F…`). Adapter text and unexpected
 * hex are not "alive."
 */
data class EcuLiveness(
    val header: String,
    val responding: Boolean,
    val detail: String,
    val negativeResponse: NegativeResponse? = null,
)

/** Result of one DTC/INF sweep: labeled DTC groups, active INF codes, and per-read failures. */
data class DtcReadResult(
    val groups: List<DtcGroup>,
    val infCodes: List<InfDetail>,
    val notes: List<String>,
    /**
     * Every request issued during the sweep mapped to its verbatim response. Keys are
     * `"<header>/<cmd>"` (e.g. `"7E2/13B0"`, `"7E0/13B0"`, `"7E2/21C6"`) so the same command
     * on two ECUs cannot overwrite each other. [infCodes] is this data run through a bit
     * mapping that has never been exercised against a real fault (§7.4), so the bytes are
     * kept beside the interpretation rather than replaced by it.
     */
    val rawResponses: Map<String, String> = emptyMap(),
    /** Gen2/Gen2_7E2 only: outcome of the `0100` liveness probe on 7E2. */
    val liveness: EcuLiveness? = null,
    /** Gen2/Gen2_7E2 only: SAE mode $03 stored DTCs on 7E2 (generic OBD, not Toyota enhanced). */
    val storedGenericDtcs: List<Dtc> = emptyList(),
    /**
     * Gen2/Gen2_7E2 only: SAE mode $07 pending DTCs on 7E2. Supplemental — Toyota enhanced /
     * non-emissions faults may not appear here.
     */
    val pendingGenericDtcs: List<Dtc> = emptyList(),
    /** How many of the five INF tables returned a positive response (did not throw). */
    val infTablesResponded: Int = 0,
) {
    /**
     * Toyota enhanced (KWP2000 groups + INF) only. Generic $03/$07 do **not** count: a clean
     * generic read must not be read as "HV has no Toyota DTCs."
     */
    val hasCodes: Boolean
        get() = groups.any { it.codes.isNotEmpty() } || infCodes.isNotEmpty()

    /**
     * True when a KWP2000 group reported stored DTCs. Generic OBD $03/$07 are deliberately
     * excluded (openspec gen2-diagnostics R3).
     */
    val hasStoredDtcs: Boolean
        get() = groups.any { it.codes.isNotEmpty() }
}

/**
 * PROTOCOL.md §7.1, HV/hybrid DTCs, engine (ECM) DTCs, Gen2 7E2 liveness + generic OBD, and the
 * five INF detail-code tables (`21C6`..`21CA`, §7.4) on the HV ECU. Reads are one-shot and slow,
 * so they run on a separate screen, never inside the fast poll cycle. Failed individual reads
 * become notes, never a silent empty list (§2).
 *
 * Generic OBD ($03/$07) and Toyota enhanced (13xx / INF) remain separate observations.
 */
class DtcReader(
    private val session: ElmSession,
    private val info: VehicleInfo,
) {
    /** Runs the HV + engine DTC reads for this generation, then the INF tables, and decodes both. */
    suspend fun read(): DtcReadResult {
        val groups = mutableListOf<DtcGroup>()
        val notes = mutableListOf<String>()
        val raws = linkedMapOf<String, String>()
        var liveness: EcuLiveness? = null
        var storedGeneric: List<Dtc> = emptyList()
        var pendingGeneric: List<Dtc> = emptyList()

        // Unconditional, and worded to contain CaptureLog.SWEEP_MARKER: the capture's log window
        // anchors on it so the sweep is preserved rather than whatever the battery poll was doing
        // when the user pressed share.
        CaptureLog.log("DTC", "${CaptureLog.SWEEP_MARKER} ${info.model.label}")

        val gen2Family = info.model == VehicleModel.GEN2 || info.model == VehicleModel.GEN2_7E2
        if (gen2Family) {
            liveness = checkLiveness(raws)
            CaptureLog.log("DTC", "liveness 7E2: responding=${liveness.responding} ${liveness.detail}")
        }

        when (info.model) {
            VehicleModel.GEN3 ->
                readDtcGroup(
                    "HV ECU (7E2)",
                    "7E2",
                    listOf("0A" to { DtcDecoder.decodeMode0A(it) }, "13B0" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
            VehicleModel.GEN2 -> {
                readDtcGroup(
                    "HV ECU (7E2)",
                    "7E2",
                    listOf("13B0" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
                readDtcGroup(
                    "Battery ECU (7E3)",
                    "7E3",
                    listOf("1380" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
            }
            VehicleModel.GEN2_7E2 ->
                readDtcGroup(
                    "HV ECU (7E2)",
                    "7E2",
                    listOf("13B0" to { DtcDecoder.decodeMode13(it) }, "1380" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
            else -> throw UnsupportedOperationException(
                "${info.model.label} DTC read is not supported in this build",
            )
        }
        // Always attempt the ECM so a fuse-test U0293 is not invisible when 7E2 INF is empty.
        readEngineDtcs(groups, notes, raws)

        if (gen2Family) {
            // Generic OBD after Toyota KWP groups, before INF. $07 is supplemental only.
            storedGeneric = readGenericObd("7E2", "03", { DtcDecoder.decodeMode03(it) }, notes, raws)
            pendingGeneric = readGenericObd("7E2", "07", { DtcDecoder.decodeMode07(it) }, notes, raws)
        }

        if (gen2Family) readFreezeFrame("7E2", notes, raws)

        val (inf, infResponded) = readInf(notes, raws)
        CaptureLog.log("DTC", "sweep: ${groups.joinToString { "${it.label}=${it.codes.joinToString { c -> c.code }}" }}")
        CaptureLog.log("DTC", "INF tables: $infResponded/5 responded")
        if (inf.isNotEmpty()) {
            CaptureLog.log("DTC", "INF active: ${inf.joinToString { "${it.tableLabel}/${it.code}" }}")
        }
        return DtcReadResult(
            groups = groups,
            infCodes = inf,
            notes = notes,
            rawResponses = raws,
            liveness = liveness,
            storedGenericDtcs = storedGeneric,
            pendingGenericDtcs = pendingGeneric,
            infTablesResponded = infResponded,
        )
    }

    /**
     * §7.1.1 Gen2 7E2 liveness: `0100` under physical `ATSH7E2`.
     *
     * measured: a 2009 Gen2 HV ECU answers `0100` with `4100981A8013`, and answers the
     * extended masks up to `0140`. Generic mode 01 is implemented here, so one probe is enough and
     * an earlier `21C6` fallback has been removed rather than left in as an untested hedge.
     *
     * Only `41…` (positive) or `7F…` (negative response) means the ECU itself answered. Adapter
     * status text and unexpected hex are not "alive".
     */
    private suspend fun checkLiveness(raws: MutableMap<String, String>): EcuLiveness =
        probeLiveness("7E2", "0100", raws) { it.startsWith("41") }

    /**
     * Sends one liveness request under [header] and classifies the reply. [positive] tests the
     * normalized response for this request's expected positive tag.
     */
    private suspend fun probeLiveness(
        header: String,
        cmd: String,
        raws: MutableMap<String, String>,
        positive: (String) -> Boolean,
    ): EcuLiveness =
        try {
            val raw =
                session.withEcu(header) {
                    session.rawCommand(cmd)
                }
            raws[rawKey(header, cmd)] = raw
            classifyLiveness(header, raw, positive)
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            CaptureLog.log("DTC", "liveness exception ($cmd): $msg")
            val detail =
                if (msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("timed out", ignoreCase = true)
                ) {
                    "No response (timeout)"
                } else if (msg.contains("Header") || msg.contains("ATSH") || msg.contains("setup failed")) {
                    "Header setup failed: $msg"
                } else {
                    "No response (timeout)"
                }
            EcuLiveness(header = header, responding = false, detail = detail)
        }

    private fun classifyLiveness(
        header: String,
        raw: String,
        positive: (String) -> Boolean,
    ): EcuLiveness {
        val trimmed = raw.trim()
        if (trimmed.contains('?') ||
            trimmed.contains("STOPPED", ignoreCase = true) ||
            trimmed.contains("UNABLE TO CONNECT", ignoreCase = true)
        ) {
            return EcuLiveness(header, false, "Adapter error: $trimmed")
        }
        if (trimmed.contains("NO DATA", ignoreCase = true) ||
            trimmed.contains("CAN ERROR", ignoreCase = true) ||
            trimmed.contains("BUS ERROR", ignoreCase = true)
        ) {
            return EcuLiveness(header, false, "No response (NO DATA)")
        }
        val norm = Normalize.normalize(raw)
        if (positive(norm)) {
            return EcuLiveness(header, true, "Responding")
        }
        if (norm.startsWith("7F")) {
            val nr = NegativeResponse.parse(norm)
            val detail =
                if (nr != null) {
                    "Negative response: 7F ${nr.service} ${"%02X".format(nr.nrc)} (${nr.meaning})"
                } else {
                    "Negative response: $norm"
                }
            return EcuLiveness(header, true, detail, nr)
        }
        return EcuLiveness(header, false, "Unexpected response: $trimmed")
    }

    /**
     * SAE generic OBD DTC read on [header] with ATH1 so the responder CAN ID is preserved in the
     * raw capture. ATH0 is always restored. Mode $07 is supplemental: Toyota enhanced faults may
     * not appear there.
     *
     * **Single-frame only.** ATH1 puts the responder CAN ID on every line, and this takes the first
     * `7EA` line and strips 3 nibbles of ID + 2 of single-frame PCI. Past about three stored DTCs
     * the reply becomes multi-frame: the first frame's PCI is 4 nibbles, not 2, and the rest of the
     * codes arrive on consecutive-frame lines this never reads. Such a reply is detected and
     * reported as a note instead of being decoded, because a silently truncated DTC list reads as a
     * cleaner car than the one in front of you.
     *
     * TODO: reassemble ISO-TP under ATH1 (or read with ATH0 and take the assembled reply, losing
     * the responder ID) so more than one frame of generic DTCs can be reported.
     */
    private suspend fun readGenericObd(
        header: String,
        cmd: String,
        decoder: (String) -> List<Dtc>,
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ): List<Dtc> {
        return try {
            session.withEcu(header) {
                session.rawCommand("ATH1")
                try {
                    val raw = session.rawCommand(cmd)
                    raws[rawKey(header, cmd)] = raw
                    val line = rawLineContaining(raw, "7EA")
                    if (line == null) {
                        notes += "Generic DTCs ($cmd): no 7EA response line found"
                        return@withEcu emptyList()
                    }
                    // Strip CAN ID (3 hex) + ISO-TP PCI (2 hex) from the 7EA line.
                    var norm = Normalize.normalize(line)
                    if (norm.length < 5) {
                        notes += "Generic DTCs ($cmd): 7EA line too short"
                        return@withEcu emptyList()
                    }
                    // ISO-TP PCI type is the nibble after the CAN ID: 0 = single frame, 1 = first
                    // frame of a multi-frame reply. Only the single-frame form is assembled here.
                    if (norm.getOrNull(3) == '1') {
                        notes +=
                            "Generic DTCs ($cmd): multi-frame response, not decoded " +
                            "(raw kept; more DTCs than one frame holds)"
                        return@withEcu emptyList()
                    }
                    norm = norm.drop(5)
                    if (norm.startsWith("7F")) {
                        val nr = NegativeResponse.parse(norm)
                        notes +=
                            if (nr != null) {
                                "Generic DTCs ($cmd): 7F ${nr.service} ${"%02X".format(nr.nrc)} (${nr.meaning})"
                            } else {
                                "Generic DTCs ($cmd): $norm"
                            }
                        return@withEcu emptyList()
                    }
                    try {
                        decoder(norm)
                    } catch (e: Exception) {
                        notes += "Generic DTCs ($cmd): ${e.message ?: e.toString()}"
                        emptyList()
                    }
                } finally {
                    try {
                        session.rawCommand("ATH0")
                    } catch (_: Exception) {
                        // adapter already in bad state; don't let cleanup failure
                        // propagate and leave ATH1 on for the next sweep
                    }
                }
            }
        } catch (e: Exception) {
            notes += "Generic DTCs ($cmd): ${e.message ?: e.toString()}"
            emptyList()
        }
    }

    /**
     * SAE mode `02` freeze frame: the conditions the ECU recorded at the instant a DTC set.
     *
     * measured on a 2009 Gen2, with `P0571` stored on `7E2`. Frame `00` was empty
     * and frame `01` was fully populated, with `020201` naming `0571` as the causing code and
     * eleven parameters alongside it: coolant 81 °C, 0 rpm, stationary, module voltage 13.69 V,
     * 38 s since start. That is "what was the car doing when this broke", which is the first
     * question anyone asks and which no generic scan tool reads off the hybrid ECU.
     *
     * `0200` gives the supported-PID mask; each supported PID is then read per frame. Frame count
     * is discovered rather than assumed: the car refused `020202` with `7F0212`, so two frames.
     *
     * This is **not** the Toyota INFORMATION 1-5 sub-code display (§7.4). Every PID here is a
     * standard SAE parameter. Worth reading on its own merits; not a route to an INF.
     */
    private suspend fun readFreezeFrame(
        header: String,
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ) {
        try {
            session.withEcu(header) {
                for (frame in 0..MAX_FREEZE_FRAMES) {
                    val fr = "%02X".format(frame)
                    val mask =
                        try {
                            session.rawCommand("0200$fr").also { raws[rawKey(header, "0200$fr")] = it }
                        } catch (e: Exception) {
                            notes += "Freeze frame $fr: ${e.message ?: e.toString()}"
                            return@withEcu
                        }
                    val supported = Normalize.normalize(mask)
                    // A frame that does not exist answers 7F or nothing; stop rather than
                    // hammering the bus for frames the ECU has already declined.
                    if (!supported.startsWith("4200")) return@withEcu
                    for (pid in FREEZE_FRAME_PIDS) {
                        val cmd = "02$pid$fr"
                        try {
                            val raw = session.rawCommand(cmd)
                            if (Normalize.normalize(raw).startsWith("42")) {
                                raws[rawKey(header, cmd)] = raw
                            }
                        } catch (_: Exception) {
                            // An absent PID is not a failure; the mask is advisory and cars
                            // disagree with it. Silence here keeps notes about real problems.
                        }
                    }
                }
            }
        } catch (e: Exception) {
            notes += "Freeze frame on $header: ${e.message ?: e.toString()}"
        }
    }

    /** First non-empty line containing [token] (case-insensitive), for ATH1 multi-line replies. */
    private fun rawLineContaining(
        raw: String,
        token: String,
    ): String? =
        raw
            .split('\r', '\n')
            .map { it.trim() }
            .firstOrNull { it.contains(token, ignoreCase = true) }

    /**
     * §7.1 engine path. Physical ECM `7E0` (never functional `7DF`, §1.2).
     *
     * Gen2 / Gen2-on-7E2: `13B0` (KWP2000 stored mask), response tag `53`.
     * Gen3: `0A` then `13B0`, same services as the HV ECU but on the ECM address.
     */
    private suspend fun readEngineDtcs(
        groups: MutableList<DtcGroup>,
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ) {
        val cmds =
            when (info.model) {
                VehicleModel.GEN3 ->
                    listOf(
                        "0A" to { r: String -> DtcDecoder.decodeMode0A(r) },
                        "13B0" to { r: String -> DtcDecoder.decodeMode13(r) },
                    )
                VehicleModel.GEN2, VehicleModel.GEN2_7E2 ->
                    listOf("13B0" to { r: String -> DtcDecoder.decodeMode13(r) })
                else -> return
            }
        readDtcGroup("Engine ECU (7E0)", "7E0", cmds, groups, notes, raws)
    }

    /** Reads one labeled group: sets the header, runs each command, dedupes its codes. */
    private suspend fun readDtcGroup(
        label: String,
        header: String,
        cmds: List<Pair<String, (String) -> List<Dtc>>>,
        groups: MutableList<DtcGroup>,
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ) {
        val codes = mutableListOf<Dtc>()
        try {
            session.withEcu(header) {
                for ((cmd, parse) in cmds) {
                    codes +=
                        try {
                            val raw = session.command(cmd)
                            raws[rawKey(header, cmd)] = raw
                            parse(raw)
                        } catch (e: Exception) {
                            notes += "$label $cmd: ${e.message ?: e.toString()}"
                            emptyList()
                        }
                }
            }
        } catch (e: Exception) {
            notes += "$label: ${e.message ?: e.toString()}"
        }
        val unique = dedupe(codes)
        if (unique.isNotEmpty()) groups += DtcGroup(label, unique)
    }

    /**
     * §7.4, the five INF detail tables from the HV ECU at `7E2`. Returns active codes and how
     * many tables returned a positive response (did not throw in the per-table try).
     */
    private suspend fun readInf(
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ): Pair<List<InfDetail>, Int> {
        val active = mutableListOf<InfDetail>()
        var responded = 0
        try {
            session.withEcu("7E2") {
                for (table in InfLayout.tables) {
                    try {
                        val raw = session.command(table.request)
                        raws[rawKey("7E2", table.request)] = raw
                        CaptureLog.log("DTC", "INF raw ${table.request} -> $raw")
                        active += InfDecoder.decodeActive(raw, table)
                        responded++
                    } catch (e: NegativeResponseException) {
                        notes += "INF ${table.request} on 7E2: ${e.response.meaning}"
                        // Still store raw if available for export completeness.
                        session.lastRawResponse.takeIf { it.isNotBlank() }?.let {
                            raws.putIfAbsent(rawKey("7E2", table.request), it)
                        }
                    } catch (e: Exception) {
                        notes += "INF ${table.request} on 7E2: ${e.message ?: e.toString()}"
                        session.lastRawResponse.takeIf { it.isNotBlank() }?.let {
                            raws.putIfAbsent(rawKey("7E2", table.request), it)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            notes += "INF read on 7E2: ${e.message ?: e.toString()}"
        }
        return active to responded
    }

    /** Order-preserving dedupe by raw DTC value (§7.3 results are deduped). */
    private fun dedupe(codes: List<Dtc>): List<Dtc> {
        val seen = linkedSetOf<Int>()
        val out = mutableListOf<Dtc>()
        for (d in codes) {
            if (seen.add(d.raw)) out += d
        }
        return out
    }

    companion object {
        /**
         * Frames to try. Inferred as 2 from one early refusal, then corrected: a an on-car read read
         * of the same car answered `020202`, so it holds at least three. The walk stops when the
         * ECU declines a frame, so this is only an upper bound and costs nothing if unused.
         */
        const val MAX_FREEZE_FRAMES = 4

        /**
         * PIDs read per frame. Exactly the ones a 2009 Gen2 answered, plus `02`
         * which names the causing DTC. Kept explicit rather than derived from the `0200` mask:
         * the mask advertised PIDs the car then refused, so the measured list is the honest one.
         */
        val FREEZE_FRAME_PIDS =
            listOf(
                "02", // the DTC that caused this frame
                "04", // calculated engine load
                "05", // engine coolant temperature
                "0C", // engine rpm
                "0D", // vehicle speed
                "0F", // intake air temperature
                "11", // throttle position
                "1F", // run time since engine start
                "31", // distance since codes cleared
                "42", // control module voltage
                "46", // ambient air temperature
                "4E", // time since DTCs cleared
            )

        /** Capture/raw map key that keeps hybrid and engine `13B0` distinct. */
        fun rawKey(
            header: String,
            cmd: String,
        ): String = "$header/$cmd"
    }
}
