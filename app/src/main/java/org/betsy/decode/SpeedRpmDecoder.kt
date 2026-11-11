package org.betsy.decode

import org.betsy.elm.NoDataException
import org.betsy.elm.Normalize
import org.betsy.model.BatteryModel

/**
 * PROTOCOL.md §5.6 generic speed / RPM from the combined `010C0D` request, any model, mode 01.
 *
 * Speed is read as **one** byte. §5.6 notes that the original app's combined decoder reads it as a
 * u16 while its own standalone `010D` decoder reads a u8, and that PID `0x0D` is a single byte per
 * SAE J1979, so the u16 form is simply wrong and is not reproduced here. Reading it as a u16
 * runs off the end of a minimal `410C00000D00` response and throws.
 */
object SpeedRpmDecoder {
    /** km/h → mph (0.6214), rounded up slightly, as §5.6 specifies. */
    private const val MPH_PER_KMH = 0.625f

    fun decode(
        r: String,
        m: BatteryModel,
    ) {
        val response = message(r)
        Normalize.requireLength(response, 8, "010C0D RPM")
        val speedPid = if (response.startsWith("410D", 8)) 10 else 8
        if (!response.startsWith("0D", speedPid)) throw NoDataException("010C0D: expected speed PID after RPM")
        Normalize.requireLength(response, speedPid + 4, "010C0D speed")
        val rpm = Normalize.u16(response, 4) / 4
        val speed = Normalize.u8(response, speedPid + 2) * MPH_PER_KMH
        m.rpm = rpm
        m.speedMph = speed
    }

    /** Bound an assembled response before interpreting either value. */
    private fun message(r: String): String {
        if (r.startsWith("410C")) {
            Normalize.requireWholeBytes(r, "010C0D")
            return r
        }
        if (!r.startsWith("410C", 3)) throw NoDataException("010C0D: unexpected response service or PID")
        val declared = r.take(3).toIntOrNull(16)
        if (declared == null || declared < 4) throw NoDataException("010C0D: invalid response length prefix")
        val end = 3 + declared * 2
        Normalize.requireLength(r, end, "010C0D")
        return r.substring(3, end)
    }
}
