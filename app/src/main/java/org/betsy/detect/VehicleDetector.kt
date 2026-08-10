package org.betsy.detect

import org.betsy.debug.CaptureLog
import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import kotlin.math.round

/**
 * Protocol layouts the detector can recognize (PROTOCOL.md §3).
 *
 * These are **layouts, not model generations**, and the labels say so. An earlier version called
 * [GEN2_5] "the `7E2` Gen2 layout", which collides with a community term for the 2006-2009 facelift and means
 * something different: BETSY's distinction is only which ECU answers `21CE`. The project's own
 * test car is a 2009, a facelift car by that community naming, and it answers on `7E3`, so it
 * takes the [GEN2] branch. One word doing two incompatible jobs guarantees that an owner reads
 * their capture and concludes the app misidentified their car.
 */
enum class VehicleModel(
    val label: String,
) {
    GEN3("Gen3"),
    GEN2("Gen2"),

    /**
     * A Gen2-era car whose battery data answers on `7E2` instead of `7E3`.
     *
     * Recognised, never decoded. No car has been observed taking this branch, so the payload
     * layout has never been checked against anything, and printing a state of charge from it
     * would be a guess wearing the clothes of a reading. It is detected purely so such a car can
     * contribute the capture that would let it be supported properly.
     */
    GEN2_7E2("Gen2-era, battery on 7E2"),
    GEN4("Gen4"),
    GEN4_5("Gen4.5"),
    GEN1("Gen1"),
    TAHOE("Tahoe"),
}

/** Result of the detection chain + block-count discovery (§3, §3.2). */
data class VehicleInfo(
    val model: VehicleModel,
    val supported: Boolean,
    val blockCount: Int,
    val cellCount: Int,
    val fifteenBlockVariant: Boolean = false,
    val splitPack: Boolean = false,
    val badAdapter: Boolean = false,
    val breadcrumbs: List<String> = emptyList(),
    /**
     * Recognised well enough to read trouble codes and the INF tables and send them, but not
     * well enough to decode live battery values. The capture is the point: it is what turns an
     * unknown variant into a supported one.
     */
    val captureOnly: Boolean = false,
)

/**
 * PROTOCOL.md §3 vehicle detection. A fallback chain, each probe sets the header, issues one
 * command, and looks for its expected tag; on miss it delegates to the next. Entry point is Gen3.
 *
 * Gen4/Gen4.5/Gen1/Tahoe are recognized but reported as unsupported in this build; they are
 * detected so the user sees the correct model name instead of a generic failure.
 */
object VehicleDetector {
    suspend fun detect(session: ElmSession): VehicleInfo {
        val crumbs = mutableListOf<Pair<String, String>>()
        session.commandTimeoutMs = 2500 // Gen2–Gen4.5 timeout (§6)

        // 1. Gen3, ATSH7E2, probe 2181, expect 6181
        if (probe(session, "7E2", "2181", "6181", crumbs)) {
            val n = discoverGen3BlockCount(session, crumbs)
            return finalize(VehicleModel.GEN3, n, session, crumbs)
        }
        // 2. Gen2, ATSH7E3, probe 21CE, expect 61CE
        if (probe(session, "7E3", "21CE", "61CE", crumbs)) {
            val n = discoverGen2BlockCount(session, crumbs)
            return finalize(VehicleModel.GEN2, n, session, crumbs)
        }
        // 3. The same request answered by 7E2 instead. Never observed on a real car, so it is
        // recognised for capture only: read codes and tables, decode no battery values.
        if (probe(session, "7E2", "21CE", "61CE", crumbs)) {
            crumbs += "detect" to "21CE answered on 7E2: unverified layout, capture only"
            return VehicleInfo(
                model = VehicleModel.GEN2_7E2,
                supported = false,
                captureOnly = true,
                blockCount = 0,
                cellCount = 0,
                breadcrumbs = crumbs.map { "${it.first}: ${it.second}" },
            )
        }
        // 4. Gen4, ATSH7D2, probe 221809; 2213 = requestOutOfRange rejects an answering ECU
        if (probeRejecting(session, "7D2", "221809", "1809", crumbs)) {
            return unsupported(VehicleModel.GEN4, crumbs)
        }
        // 5. Gen4.5, ATSH747, probe 221F9A
        if (probeRejecting(session, "747", "221F9A", "1F9A", crumbs)) {
            return unsupported(VehicleModel.GEN4_5, crumbs)
        }
        // 6. Gen1, ATSH84D5F1, probe 01A4, then ATSP3
        if (probe(session, "84D5F1", "01A4", "41A4", crumbs)) {
            return unsupported(VehicleModel.GEN1, crumbs)
        }
        // 7. Tahoe, ATSH7E7, probe 2240e4
        if (probe(session, "7E7", "2240e4", "6240E4", crumbs)) {
            return unsupported(VehicleModel.TAHOE, crumbs)
        }
        throw ElmException(
            "no supported vehicle detected. " +
                crumbs.joinToString("; ") { "${it.first}: ${it.second}" },
        )
    }

    /** Sets the header, sends [cmd], and returns true when the response contains [tag] (§3). */
    private suspend fun probe(
        session: ElmSession,
        header: String,
        cmd: String,
        tag: String,
        crumbs: MutableList<Pair<String, String>>,
    ): Boolean {
        session.setHeader(header)
        return try {
            val r = session.command(cmd)
            val ok = r.contains(tag)
            CaptureLog.log("DETECT", "$header $cmd -> ${if (ok) "hit" else "miss (no $tag)"}")
            if (!ok) crumbs += header to "no $tag"
            ok
        } catch (e: Exception) {
            CaptureLog.logThrowable("DETECT", e)
            crumbs += header to (e.message ?: e.toString())
            false
        }
    }

    /** Same as [probe] but also rejects responses carrying the `2213` negative-response code. */
    private suspend fun probeRejecting(
        session: ElmSession,
        header: String,
        cmd: String,
        tag: String,
        crumbs: MutableList<Pair<String, String>>,
    ): Boolean {
        session.setHeader(header)
        return try {
            val r = session.command(cmd)
            val ok = r.contains(tag) && !r.contains("2213")
            CaptureLog.log("DETECT", "$header $cmd -> ${if (ok) "hit" else "miss (no $tag or requestOutOfRange)"}")
            if (!ok) crumbs += header to "no $tag (or requestOutOfRange)"
            ok
        } catch (e: Exception) {
            CaptureLog.logThrowable("DETECT", e)
            crumbs += header to (e.message ?: e.toString())
            false
        }
    }

    /** Gen2-layout block count: 21D0 byte 0 is the ECU-reported count (§5.2, §3.2 correction). */
    private suspend fun discoverGen2BlockCount(
        session: ElmSession,
        crumbs: MutableList<Pair<String, String>>,
    ): Int {
        val r = session.command("21D0")
        val i = Normalize.requireTag(r, "61D0")
        val fromByte = Normalize.u8(r, i + 4)
        if (fromByte in 1..100) return fromByte
        crumbs += "21D0" to "byte-0 count implausible ($fromByte); scanning IR"
        return scanIr(r, i + 34, "21D0")
    }

    /** Gen3 block count: scan 2195 IR bytes; bound 255 not 60 (§3.2 correction). */
    private suspend fun discoverGen3BlockCount(
        session: ElmSession,
        crumbs: MutableList<Pair<String, String>>,
    ): Int {
        val r = session.command("2195")
        val i = Normalize.requireTag(r, "6195")
        return scanIr(r, i + 4, "2195")
    }

    /**
     * §3.2 scan: walk 2-hex-char IR bytes until an implausible value. The app's `>= 60` cutoff is
     * too tight for NiMH (official normal band runs to raw 100), prefer ~255.
     */
    private fun scanIr(
        r: String,
        start: Int,
        what: String,
    ): Int {
        var k = start
        var n = 0
        while (n < 100 && k + 2 <= r.length) {
            val v = Normalize.u8(r, k)
            if (v == 0 || v >= 255) break
            n += 1
            k += 2
        }
        if (n == 0) throw NoDataException("$what: no plausible IR bytes found")
        return n
    }

    /** §3.2 post-processing: bad-adapter fallback, fifteen-block variant, cell count. */
    private suspend fun finalize(
        model: VehicleModel,
        n: Int,
        session: ElmSession,
        crumbs: MutableList<Pair<String, String>>,
    ): VehicleInfo {
        var blockCount = n
        var badAdapter = false
        if (n < 7) {
            badAdapter = true
            blockCount = 10 // §3.2: assume the adapter is dropping bytes
        }
        val fifteen = blockCount == 15
        val cellCount = if (fifteen) blockCount * 2 else blockCount * 2 // §3.2 C = N × 2
        val splitPack =
            if (fifteen) {
                detectSplitPack(session, model, blockCount, crumbs)
            } else {
                false
            }
        CaptureLog.log(
            "DETECT",
            "resolved ${model.label} n=$blockCount cells=$cellCount fifteen=$fifteen splitPack=$splitPack badAdapter=$badAdapter",
        )
        return VehicleInfo(
            model = model,
            supported = true,
            blockCount = blockCount,
            cellCount = cellCount,
            fifteenBlockVariant = fifteen,
            splitPack = splitPack,
            badAdapter = badAdapter,
            breadcrumbs = crumbs.map { "${it.first}: ${it.second}" },
        )
    }

    /** §3.3: split-pack detection runs only when N == 15. */
    private suspend fun detectSplitPack(
        session: ElmSession,
        model: VehicleModel,
        n: Int,
        crumbs: MutableList<Pair<String, String>>,
    ): Boolean {
        val cmd: String
        val tag: String
        val blockAt: (String, Int) -> Float
        when (model) {
            VehicleModel.GEN3 -> {
                cmd = "2181"
                tag = "6181"
                blockAt = { r, idx ->
                    round(Normalize.u16(r, 4 + idx * 4) * 0.122f) / 100f
                }
            }
            else -> {
                cmd = "21CE"
                tag = "61CE"
                blockAt = { r, idx ->
                    Normalize.u16(r, 10 + idx * 4) / 100f - 327.68f
                }
            }
        }
        return try {
            val r = session.command(cmd)
            val i = Normalize.requireTag(r, tag)
            val v1 = blockAt(r.substring(i), 1)
            val vLast = blockAt(r.substring(i), n - 1)
            v1 > 18.0f && vLast < 18.0f // §3.3: first 6 blocks one set of thresholds, rest another
        } catch (e: Exception) {
            crumbs += "splitPack" to (e.message ?: e.toString())
            false
        }
    }

    private fun unsupported(
        model: VehicleModel,
        crumbs: List<Pair<String, String>>,
    ): VehicleInfo =
        VehicleInfo(
            model = model,
            supported = false,
            blockCount = 0,
            cellCount = 0,
            breadcrumbs = crumbs.map { "${it.first}: ${it.second}" },
        )
}
