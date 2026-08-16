package org.betsy.poll

import org.betsy.debug.CaptureLog
import org.betsy.decode.Gen2Decoder
import org.betsy.decode.Gen3Decoder
import org.betsy.decode.SpeedRpmDecoder
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import java.util.Locale

/**
 * PROTOCOL.md §6 polling, fast mode: one combined multi-DID request per cycle plus `010C0D` for
 * speed/RPM. Gen2 and Gen3 layouts only, Gen1 and Tahoe always run slow mode, and Gen4/4.5 are not
 * supported in this build.
 */
class Poller(
    private val session: ElmSession,
    private val info: VehicleInfo,
) {
    /** Decodes one full poll cycle into [m]. Throws on a transport/session failure. */
    suspend fun poll(m: BatteryModel) {
        val header: String
        val cmd: String
        val decode: (String) -> Unit
        when (info.model) {
            VehicleModel.GEN3 -> {
                header = "7E2"
                cmd = "210181958798"
                decode = { Gen3Decoder.decodeCombined(it, m) }
            }
            VehicleModel.GEN2 -> {
                header = "7E3"
                cmd = "21CED0CF"
                decode = { Gen2Decoder.decodeCombined(it, m, info.fifteenBlockVariant) }
            }
            else -> throw UnsupportedOperationException(
                "${info.model.label} is not supported in this build",
            )
        }
        // One critical section: the combined read and the §5.6 speed/RPM read both depend on this
        // header, and speedRpm sends no ATSH of its own.
        session.withEcu(header) {
            // Count has to be on the model before decode: the combined reply is sliced
            // by N, and a first poll with N still 0 paints an empty pack.
            m.blockCount = info.blockCount
            m.cellCount = info.cellCount
            decode(session.command(cmd))
            speedRpm(m)
        }
        CaptureLog.log(
            "POLL",
            String.format(
                Locale.US,
                "soc=%.1f cur=%.1fA pack=%.1fV diff=%.3f temp1=%.1fC",
                m.soc,
                m.currentAmps,
                m.packVolts(),
                m.voltDiff(),
                m.temps.firstOrNull() ?: 0f,
            ),
        )
    }

    /** §5.6 generic speed/RPM. Optional, a non-answering ECU leaves last values stale. */
    private suspend fun speedRpm(m: BatteryModel) {
        val r =
            try {
                session.command("010C0D")
            } catch (_: NoDataException) {
                return
            }
        SpeedRpmDecoder.decode(r, m)
    }
}
