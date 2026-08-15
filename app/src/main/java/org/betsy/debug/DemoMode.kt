package org.betsy.debug

import org.betsy.BuildConfig
import org.betsy.transport.ReplayDelay
import org.betsy.transport.SimulatedCar

/**
 * One scripted fixture offered on the debug connect screen. Each is a transport, not a parallel
 * view: the production connect → detect → sweep → share path runs against its script unchanged.
 */
enum class DemoScenario(
    val title: String,
    val description: String,
    val script: Map<String, String>,
    /** When true, the local upload stub fails so the share error and retry surfaces can be seen. */
    val shareFails: Boolean = false,
) {
    GEN2_HEALTHY(
        "Gen2 · healthy",
        "Clean Gen2, empty freeze pages",
        SimulatedCar.gen2Healthy,
    ),
    GEN2_STORED_FAULT(
        "Gen2 · stored P0571",
        "Stored P0571 with a readable sub-code",
        SimulatedCar.gen2WithStoredFault,
    ),
    DECODER_MISS(
        "Gen2 · decoder miss",
        "Stored DTC whose freeze pages carry no sub-code",
        SimulatedCar.gen2DecoderMiss,
    ),
    CAPTURE_ONLY(
        "Gen2 · capture-only",
        "Battery data answers on 7E2 — unverified layout",
        SimulatedCar.gen2CaptureOnly,
    ),
    CONNECT_FAIL(
        "Connect fail",
        "ATZ is silent — the adapter phase goes red",
        SimulatedCar.connectFail,
    ),
    SHARE_FAIL(
        "Share fail",
        "Stored P0571; the upload stub reports a failure",
        SimulatedCar.gen2WithStoredFault,
        shareFails = true,
    ),
    ;

    companion object {
        /** Candidate id a fixture is published under on the connect screen. */
        fun byId(id: String): DemoScenario? = entries.firstOrNull { "demo:${it.name}" == id }
    }
}

/**
 * Whether this process is in a scripted demo session, and which scenario if so.
 *
 * Not a second session holder: the session itself lives in `SessionHolder`, this only records the
 * demo flag so `CaptureUploader` can refuse to open a connection. `available()` tracks
 * `BuildConfig.DEBUG`, so a release build can compile this object and still never enter it.
 */
object DemoMode {
    private var scenario: DemoScenario? = null

    fun available(): Boolean = BuildConfig.DEBUG

    fun active(): Boolean = scenario != null

    fun current(): DemoScenario? = scenario

    /** True when the active scenario's upload stub is meant to fail. */
    fun shareFails(): Boolean = scenario?.shareFails ?: false

    fun activate(value: DemoScenario) {
        scenario = value
    }

    fun deactivate() {
        scenario = null
    }
}

/**
 * Delay profile for a demo connect/sweep. Car-like enough to read the phases, not the full §6
 * 2500 ms timeout: a miss-heavy detect would otherwise turn into a minute of dead air.
 */
val DEMO_DELAY = ReplayDelay(atMs = 80, hitMs = 400, missMs = 1600, longMs = 900)
