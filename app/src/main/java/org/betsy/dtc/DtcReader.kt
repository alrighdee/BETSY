package org.betsy.dtc

import org.betsy.debug.CaptureLog
import org.betsy.decode.DtcDecoder
import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmSession
import org.betsy.elm.NegativeResponseException
import org.betsy.model.Dtc
import org.betsy.model.InfDetail

/** One labeled DTC group from one ECU's read (PROTOCOL.md §9.1). */
data class DtcGroup(
    val label: String,
    val codes: List<Dtc>,
)

/** Result of one DTC/INF sweep: labeled DTC groups, active INF codes, and per-read failures. */
data class DtcReadResult(
    val groups: List<DtcGroup>,
    val infCodes: List<InfDetail>,
    val notes: List<String>,
    /**
     * Every request issued during the sweep mapped to its verbatim response, e.g.
     * `"21C6" -> "61C6 00 00 …"`. [infCodes] is this data run through a bit mapping that has
     * never been exercised against a real fault (§9.4.0), so the bytes are kept beside the
     * interpretation rather than replaced by it: a capture has to be able to disagree with the
     * decoder, or it cannot be used to correct it.
     */
    val rawResponses: Map<String, String> = emptyMap(),
) {
    val hasCodes: Boolean
        get() = groups.any { it.codes.isNotEmpty() } || infCodes.isNotEmpty()

    /** True when the ECU reported stored DTCs, regardless of what the INF decoder made of them. */
    val hasStoredDtcs: Boolean
        get() = groups.any { it.codes.isNotEmpty() }
}

/**
 * PROTOCOL.md §7.1, the HV/hybrid DTC read for the Gen2 and Gen3 layouts plus the five INF detail-code
 * tables (`21C6`..`21CA`, §9.4.0) on the HV ECU. Reads are one-shot and slow, so they run on a separate
 * screen, never inside the fast poll cycle. The engine read (`b(e)`, §9.1) is out of scope
 * for this build. Failed individual reads become notes, never a silent empty list (§2).
 */
class DtcReader(
    private val session: ElmSession,
    private val info: VehicleInfo,
) {
    /** Runs the HV DTC reads for this generation, then the INF DID, and decodes both. */
    suspend fun read(): DtcReadResult {
        val groups = mutableListOf<DtcGroup>()
        val notes = mutableListOf<String>()
        val raws = linkedMapOf<String, String>()
        when (info.model) {
            VehicleModel.GEN3 ->
                readMode13Group(
                    "HV ECU (7E2)",
                    "7E2",
                    listOf("0A" to { DtcDecoder.decodeMode0A(it) }, "13B0" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
            VehicleModel.GEN2 -> {
                readMode13Group(
                    "HV ECU (7E2)",
                    "7E2",
                    listOf("13B0" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
                readMode13Group(
                    "Battery ECU (7E3)",
                    "7E3",
                    listOf("1380" to { DtcDecoder.decodeMode13(it) }),
                    groups,
                    notes,
                    raws,
                )
            }
            VehicleModel.GEN2_7E2 ->
                readMode13Group(
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
        // Read unconditionally, including on a car with nothing stored. An earlier version skipped
        // this when no DTC was present, on the theory that the ECU would refuse a detail request
        // with nothing to detail. It does not: a fault-free Gen2 answers all five tables with
        // all-zero payloads, and a table that ever does refuse is caught per-table below and
        // recorded as a note.
        //
        // The all-zero answers are the point. The byte→code mapping has never been exercised
        // (§9.4.0), so when a genuinely faulty car eventually reads back as all-zero, the only way
        // to tell "the mapping is wrong" from "the read failed on that adapter" is a body of
        // healthy captures proving the read path returns well-formed zeros.
        val inf = readInf(notes, raws)
        CaptureLog.log("DTC", "sweep: ${groups.joinToString { "${it.label}=${it.codes.joinToString { it.code }}" }}")
        if (inf.isNotEmpty()) {
            CaptureLog.log("DTC", "INF active: ${inf.joinToString { "${it.tableLabel}/${it.code}" }}")
        }
        return DtcReadResult(groups, inf, notes, raws)
    }

    /** Reads one labeled group: sets the header, runs each command, dedupes its codes. */
    private suspend fun readMode13Group(
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
                            raws[cmd] = raw
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
     * §9.4.0, the five INF detail tables from the HV ECU at `7E2`, read with KWP2000 service
     * `0x21` and a single-byte local identifier: `21C6`..`21CA`.
     *
     * Five separate requests, not one batched `21C6C7C8C9CA`, `7E2` rejects the batched form
     * with `7F2112` (subFunctionNotSupported), unlike `7E3` where `21CED0CF` works. They share
     * one [ElmSession.withEcu] block so the header and all five reads are a single critical
     * section (§9.4.0, and the `ATSH` discipline in §1.2).
     *
     * `7E2` is the only candidate: `7E3` is silent to these identifiers, and mode 22 is absent
     * from this ECU entirely, both `2205CA` and `22F186` answer `7F2211`.
     *
     * **Every raw response is captured when codes are stored.** Nothing has ever been observed
     * with a bit set, so the byte→code mapping is unexercised; a capture from a car with a real
     * fault is the one thing that closes it (§9.4.0). Cheap to log, impossible to reconstruct
     * later.
     */
    private suspend fun readInf(
        notes: MutableList<String>,
        raws: MutableMap<String, String>,
    ): List<InfDetail> {
        val active = mutableListOf<InfDetail>()
        try {
            session.withEcu("7E2") {
                for (table in InfLayout.tables) {
                    try {
                        val raw = session.command(table.request)
                        raws[table.request] = raw
                        CaptureLog.log("DTC", "INF raw ${table.request} -> $raw")
                        active += InfDecoder.decodeActive(raw, table)
                    } catch (e: NegativeResponseException) {
                        notes += "INF ${table.request} on 7E2: ${e.response.meaning}"
                    } catch (e: Exception) {
                        notes += "INF ${table.request} on 7E2: ${e.message ?: e.toString()}"
                    }
                }
            }
        } catch (e: Exception) {
            notes += "INF read on 7E2: ${e.message ?: e.toString()}"
        }
        return active
    }

    /** Order-preserving dedupe by raw DTC value (§9.3 results are deduped). */
    private fun dedupe(codes: List<Dtc>): List<Dtc> {
        val seen = linkedSetOf<Int>()
        val out = mutableListOf<Dtc>()
        for (d in codes) {
            if (seen.add(d.raw)) out += d
        }
        return out
    }
}
