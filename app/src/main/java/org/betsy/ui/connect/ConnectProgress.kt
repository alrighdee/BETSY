package org.betsy.ui.connect

/**
 * The four phases the connect screen names while a session comes up. They line up one-to-one with
 * the real work: opening the transport, ATZ (§1.1), the remaining AT configuration (§1.1), and
 * vehicle detection (§3). Naming them is the point, a failure says which phase failed.
 */
enum class ConnectPhase {
    LINK,
    ADAPTER,
    HANDSHAKE,
    VEHICLE,
}

enum class StepState {
    PENDING,
    ACTIVE,
    DONE,
    FAILED,
}

/** One rendered row in the connecting panel. */
data class ConnectStep(
    val phase: ConnectPhase,
    val text: String,
    val state: StepState,
    val elapsedMs: Long?,
)

/**
 * An immutable view of a connection attempt. The attempt runs on a worker thread while the panel
 * renders on the main one, so progress is handed over as a snapshot rather than a shared mutable.
 */
data class ConnectSnapshot(
    val percent: Int,
    val steps: List<ConnectStep>,
) {
    val failed: Boolean get() = steps.any { it.state == StepState.FAILED }
}

/**
 * Tracks which phase a connection attempt has reached and turns that into the mockup's step list and
 * ring percentage. Pure state, no Android types, so the progression is unit-testable.
 */
class ConnectProgress(
    private val wifi: Boolean,
) {
    private val elapsed = LinkedHashMap<ConnectPhase, Long>()
    private var active: ConnectPhase? = null
    private var failed: ConnectPhase? = null
    private var banner: String? = null

    fun begin(phase: ConnectPhase) {
        active = phase
    }

    fun complete(
        phase: ConnectPhase,
        elapsedMs: Long,
    ) {
        elapsed[phase] = elapsedMs
        if (active == phase) active = null
    }

    fun fail(phase: ConnectPhase) {
        failed = phase
        active = null
    }

    /** Records the ATZ banner so the adapter step can name the firmware it actually found. */
    fun adapterDetected(banner: String) {
        this.banner = banner.takeIf { it.isNotBlank() }
    }

    /** Immutable hand-off to the main thread. */
    fun snapshot(): ConnectSnapshot = ConnectSnapshot(percent(), steps())

    /** 0-100, one quarter per completed phase; drives the conic ring. */
    fun percent(): Int = elapsed.size * (100 / ConnectPhase.entries.size)

    fun steps(): List<ConnectStep> =
        ConnectPhase.entries.map { phase ->
            ConnectStep(
                phase = phase,
                text = label(phase),
                state =
                    when {
                        failed == phase -> StepState.FAILED
                        elapsed.containsKey(phase) -> StepState.DONE
                        active == phase -> StepState.ACTIVE
                        else -> StepState.PENDING
                    },
                elapsedMs = elapsed[phase],
            )
        }

    private fun label(phase: ConnectPhase): String =
        when (phase) {
            ConnectPhase.LINK -> if (wifi) "Saying hello over Wi-Fi" else "Saying hello over Bluetooth"
            ConnectPhase.ADAPTER -> banner?.let { "Checking the adapter \u00b7 $it" } ?: "Checking the adapter"
            ConnectPhase.HANDSHAKE -> "ISO 15765-4 handshake"
            ConnectPhase.VEHICLE -> "Reading your car details"
        }
}
