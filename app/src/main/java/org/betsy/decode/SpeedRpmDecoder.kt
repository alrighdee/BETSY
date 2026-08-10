package org.betsy.decode

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
        val i = Normalize.requireTag(r, "410C")
        m.rpm = Normalize.u16(r, i + 4) / 4
        val j = Normalize.requireTag(r, "0D", i + 8)
        m.speedMph = Normalize.u8(r, j + 2) * MPH_PER_KMH
    }
}
