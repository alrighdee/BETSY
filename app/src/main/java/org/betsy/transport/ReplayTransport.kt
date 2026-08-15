package org.betsy.transport

/**
 * Per-command delay profile for a [ReplayTransport]. A single delay for every send is the wrong
 * shape: the monitor polls two commands at 2 Hz, connect is a handful of `AT*` plus a few probes,
 * and a Gen2 sweep is forty-plus exchanges. One number either stutters the bars or makes the sweep
 * instant. Any field left at 0 means no sleep for that kind.
 */
data class ReplayDelay(
    /** Adapter-local `AT*` commands, acknowledged quickly regardless of the car. */
    val atMs: Long = 0,
    /** A scripted, answering non-AT request. */
    val hitMs: Long = 0,
    /** A miss: fallback / `NO DATA`. */
    val missMs: Long = 0,
    /** Multi-frame pages (`21C6`–`21CA`, `21CED0CF`), which a car answers slowly. */
    val longMs: Long = 0,
)

/**
 * An [ElmTransport] that answers from a fixed script instead of a car (PROTOCOL.md §7.4).
 *
 * This exists because a healthy vehicle returns empty freeze pages, so the path a transmitted INF
 * value takes through the reader, decoder and UI is not exercised. [SimulatedCar] supplies a car
 * with a stored fault so that path runs.
 *
 * It is a replay, not an emulator, it does not model ECU state, timing or error behaviour.
 * A green run here means the decode path is wired correctly. The field position and one known
 * DTC/sub-code value are independently pinned from a Gen2 response; other vehicles and faults
 * still require their own evidence.
 */
class ReplayTransport(
    private val script: Map<String, String>,
    /** Reply for anything the script does not cover, a silent ECU, as a real one would be. */
    private val fallback: String = "NO DATA",
    /** Optional wall-clock delay per command; defaults to zero so tests stay instant. */
    private val delay: ReplayDelay = ReplayDelay(),
) : ElmTransport {
    override var readTimeoutMs: Int = 2500

    /** Every command asked for, in order, lets a test assert the exact wire sequence. */
    val sent = mutableListOf<String>()

    private var closed = false

    /**
     * The last `ATSH` header set, tracked so a script can answer one request differently per ECU
     * (`"7E2/21CE"` vs `"7E3/21CE"`). Plain keys still work: a header-qualified key is only an
     * override, looked up before the bare command.
     */
    private var header: String? = null

    override suspend fun send(cmd: String): String {
        if (closed) throw TransportException("replay transport is closed (cmd=$cmd)")
        sent += cmd
        if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")

        val at = cmd.startsWith("AT")
        val keyed = header?.let { script["$it/$cmd"] }
        val plain = script[cmd]
        val reply =
            when {
                at -> plain ?: "OK"
                keyed != null -> keyed
                else -> plain ?: fallback
            }

        val sleepMs =
            when {
                at -> delay.atMs
                cmd in LONG_PAGES -> delay.longMs
                keyed != null || plain != null -> delay.hitMs
                else -> delay.missMs
            }
        if (sleepMs > 0) Thread.sleep(sleepMs)
        return reply
    }

    override fun close() {
        closed = true
    }

    private companion object {
        val LONG_PAGES = setOf("21C6", "21C7", "21C8", "21C9", "21CA", "21CED0CF")
    }
}

/**
 * A scripted Gen2 with stored `P0571` and its transmitted INF detail code, `115` (§7.4).
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

    /**
     * `21C7` exactly as a 2009 Gen2 returned it with `P0571` stored, all 48 payload bytes.
     *
     * Byte-for-byte identical across an ignition cycle, so this page is a snapshot written when
     * the fault sets rather than live data. Bytes 29-30 are
     * `00 73`, decimal 115, which is `P0571`'s documented sub-code. The run of `0x80` at the start
     * is offset-binary midpoints, a car at rest; treating those as flags is what produced 35
     * phantom sub-codes under the old model.
     */
    private const val REAL_PAGE_C7_P0571 =
        "03261C7808080800000049A417E00615F5B5D70820000A0AF00000000000000" +
            "010073636B4A02615F5C639E6C665D659E9A801C0000000000"

    /** The four pages no DTC wrote, exactly as the same car returned them. */
    private const val EMPTY_PAGE_C7 =
        "03261C7" +
            "000000000000000000000000000000000000000000000000" +
            "0000000000000000000000000000000000000000000000000"
    private const val EMPTY_PAGE_C6 =
        "03261C6" + "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    private const val EMPTY_PAGE_C8 =
        "03261C8" + "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    private const val EMPTY_PAGE_C9 =
        "03261C9" + "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    private const val EMPTY_PAGE_CA =
        "03261CA" + "0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"

    /** Captured from a real 2009 Gen2, parked and charging (PROTOCOL.md §5.2 worked example). */
    private const val CE_D0_CF =
        "61CE6D810B85ED85EB85E285DF85F085F685F185F385F485F385EB85E585E485E1" +
            "D00E000000000000000085DF0385F6051313131313131313131313131313" +
            "CF8CD180C54EAA000900008C498C1B8C54"

    /**
     * Gen2 with a stored fault. **Every response below was measured on a real car.**
     *
     * Recorded from a 2009 Gen2 across an ignition cycle, with `P0571` deliberately stored
     * on `7E2`, framed exactly as the car speaks it. The five freeze pages are the real ones: page
     * 2 populated, the other four all zero. Its sub-code, 115, is not written here as a constant;
     * it is carried inside the page bytes at 29-30 and has to be decoded to be seen, which is the
     * point of the fixture.
     *
     * This replaced a synthetic fixture that had bytes set at arbitrary offsets to exercise "a
     * non-zero table is carried through". That fixture could not have caught the decoder being
     * wrong, because it did not encode anything true. Measured bytes can.
     */
    val gen2WithStoredFault: Map<String, String> =
        mapOf(
            // adapter phase: ATZ must name the firmware or ElmSession.reset() aborts before the
            // car is ever asked anything.
            "ATZ" to "ELM327 v2.2",
            // detection: Gen3 probe misses, Gen2 probe hits (§3)
            "2181" to "NO DATA",
            "21CE" to "61CE6D810B85ED85EB85E285DF85F085F685F185F385F485F385EB85E585E485E1",
            "21D0" to "61D00E000000000000000085DF0385F6051313131313131313131313131313",
            "21CED0CF" to CE_D0_CF,
            "010C0D" to "410C00000D00",
            // Liveness + generic OBD (PROTOCOL.md §7.1.1, §7.1.2)
            "0100" to "4100FFE0FFE0",
            // Mode 02 freeze frame: frame 00 exists, frame 01 is declined, matching the
            // on-car behavior where the car held two frames and refused the third.
            "020000" to "4200007E1F8803",
            "020200" to "4202000000",
            "020201" to "4202010571",
            "020500" to "42050079",
            "024200" to "4242357B",
            "020001" to "7F0212",
            "ATH1" to "OK",
            "ATH0" to "OK",
            // Generic $03 with one stored code; $07 clean (supplemental)
            "03" to "7EA 04 43 01 0A A6 ",
            "07" to "7EA 02 47 00 ",
            // DTC read (§7.1): one stored code on the HV ECU; battery and engine clean
            // (engine uses the same 13B0 command on 7E0; ReplayTransport is not call-order
            // aware, so both ECUs see P0AA6 unless overridden. Healthy fixture zeros 13B0.)
            "13B0" to "53010571",
            "1380" to "5300",
            // Freeze pages, verbatim from the car (§7.4.2). Page 2 carries the sub-code at
            // bytes 29-30; the rest are untouched because only one DTC is stored.
            "21C6" to EMPTY_PAGE_C6,
            "21C7" to REAL_PAGE_C7_P0571,
            "21C8" to EMPTY_PAGE_C8,
            "21C9" to EMPTY_PAGE_C9,
            "21CA" to EMPTY_PAGE_CA,
        )

    /** A healthy Gen2, every INF page reads zero, as the real test car does. */
    val gen2Healthy: Map<String, String> =
        gen2WithStoredFault +
            mapOf(
                "13B0" to "5300",
                "03" to "7EA 02 43 00 ",
                "07" to "7EA 02 47 00 ",
                // Every page zero. Must be spelled out: this map is built from the faulted one,
                // so a page left unmentioned would inherit that car's populated C7 and make a
                // "healthy" fixture report a sub-code.
                "21C6" to EMPTY_PAGE_C6,
                "21C7" to EMPTY_PAGE_C7,
                "21C8" to EMPTY_PAGE_C8,
                "21C9" to EMPTY_PAGE_C9,
                "21CA" to EMPTY_PAGE_CA,
            )

    /**
     * A stored DTC whose freeze pages carry no sub-code: `13B0` reports `P0571` but every
     * `21C6`–`21CA` page is empty, so the sweep has stored codes and no readable INF value. That
     * is the "sub-code not recognised" share sheet, and it is worth showing because a fault with
     * no readable value is exactly the data the project needs.
     */
    val gen2DecoderMiss: Map<String, String> =
        gen2WithStoredFault +
            mapOf(
                "21C6" to EMPTY_PAGE_C6,
                "21C7" to EMPTY_PAGE_C7,
                "21C8" to EMPTY_PAGE_C8,
                "21C9" to EMPTY_PAGE_C9,
                "21CA" to EMPTY_PAGE_CA,
            )

    /**
     * A Gen2-era layout whose battery data answers on `7E2` instead of `7E3`. `21CE` misses on
     * `7E3` and hits on `7E2`, which detection files as capture-only. The header-qualified keys
     * are what let one script answer the same request differently per ECU header.
     */
    val gen2CaptureOnly: Map<String, String> =
        gen2WithStoredFault +
            mapOf(
                "7E3/21CE" to "NO DATA",
                "7E2/21CE" to "61CE6D810B85ED85EB85E285DF85F085F685F185F385F485F385EB85E585E485E1",
            )

    /** A demo connect that never reaches the car: ATZ answers nothing a real adapter would. */
    val connectFail: Map<String, String> = mapOf("ATZ" to "")
}
