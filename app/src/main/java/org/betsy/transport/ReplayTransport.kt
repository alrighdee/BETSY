package org.betsy.transport

/**
 * An [ElmTransport] that answers from a fixed script instead of a car (PROTOCOL.md §9.4.0).
 *
 * This exists because the INF detail decode cannot be exercised on a healthy vehicle: every
 * table reads all zeros, so the path a *set* byte takes through the reader, decoder and UI is
 * never executed. [SimulatedCar] supplies a car with a stored fault so that path runs.
 *
 * It is a replay, not an emulator, it does not model ECU state, timing or error behaviour.
 * A green run here means the decode path is wired correctly; it says nothing about whether the
 * byte→code mapping matches a real Toyota. Only a capture from a genuinely faulty car settles
 * that (§9.4.0).
 */
class ReplayTransport(
    private val script: Map<String, String>,
    /** Reply for anything the script does not cover, a silent ECU, as a real one would be. */
    private val fallback: String = "NO DATA",
) : ElmTransport {
    override var readTimeoutMs: Int = 2500

    /** Every command asked for, in order, lets a test assert the exact wire sequence. */
    val sent = mutableListOf<String>()

    private var closed = false

    override suspend fun send(cmd: String): String {
        if (closed) throw TransportException("replay transport is closed (cmd=$cmd)")
        sent += cmd
        // AT configuration is adapter-local; a real ELM327 acknowledges it regardless of the car.
        if (cmd.startsWith("AT")) return script[cmd] ?: "OK"
        return script[cmd] ?: fallback
    }

    override fun close() {
        closed = true
    }
}

/**
 * A scripted Gen2 with a stored `P0AA6` and one INF detail code set in each of two tables,
 * the 5xx/6xx pair that names a failed component (§9.4.0).
 *
 * Live-data responses are real bytes captured from a 2009 Gen2, so the battery screen renders
 * plausible values rather than zeros.
 */
object SimulatedCar {
    private const val PAYLOAD_BYTES = 48

    /** `61<lid>` followed by [PAYLOAD_BYTES] zero bytes, with the given bytes overridden. */
    private fun table(
        lid: Int,
        vararg set: Pair<Int, Int>,
    ): String {
        val b = ByteArray(PAYLOAD_BYTES)
        for ((k, v) in set) b[k] = v.toByte()
        return "61%02X".format(lid) + b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    /** Captured from a real 2009 Gen2, parked and charging (PROTOCOL.md §5.2 worked example). */
    private const val CE_D0_CF =
        "61CE6D810B85ED85EB85E285DF85F085F685F185F385F485F385EB85E585E485E1" +
            "D00E000000000000000085DF0385F6051313131313131313131313131313" +
            "CF8CD180C54EAA000900008C498C1B8C54"

    /**
     * Gen2 with a stored fault.
     *
     * `13B0` reports one code, `0x0AA6` → `P0AA6`, and two detail tables come back with a byte
     * set rather than all-zero. The offsets are arbitrary: what the fixture exercises is that a
     * non-zero table is read and carried through to the capture intact. Naming which slot means
     * what is not something this project can currently demonstrate (§9.4.0).
     */
    val gen2WithStoredFault: Map<String, String> =
        mapOf(
            // detection: Gen3 probe misses, Gen2 probe hits (§3)
            "2181" to "NO DATA",
            "21CE" to "61CE6D810B85ED85EB85E285DF85F085F685F185F385F485F385EB85E585E485E1",
            "21D0" to "61D00E000000000000000085DF0385F6051313131313131313131313131313",
            "21CED0CF" to CE_D0_CF,
            "010C0D" to "410C00000D00",
            // DTC read (§9.1): one stored code on the HV ECU, none on the battery ECU
            "13B0" to "53010AA6",
            "1380" to "5300",
            // INF detail tables (§9.4.0)
            "21C6" to table(0xC6),
            "21C7" to table(0xC7),
            "21C8" to table(0xC8),
            "21C9" to table(0xC9, 7 to 0x01),
            "21CA" to table(0xCA, 11 to 0x01),
        )

    /** A healthy Gen2, every table reads zero, as the real test car does. */
    val gen2Healthy: Map<String, String> =
        gen2WithStoredFault +
            mapOf(
                "13B0" to "5300",
                "21C9" to table(0xC9),
                "21CA" to table(0xCA),
            )
}
