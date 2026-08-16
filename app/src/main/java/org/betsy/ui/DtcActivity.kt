package org.betsy.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.BuildConfig
import org.betsy.capture.CaptureConsent
import org.betsy.capture.CaptureData
import org.betsy.capture.CaptureUploader
import org.betsy.capture.PendingCapture
import org.betsy.capture.UploadResult
import org.betsy.debug.CaptureLog
import org.betsy.debug.DemoMode
import org.betsy.dtc.DtcReadResult
import org.betsy.dtc.DtcReader
import org.betsy.dtc.SweepPhase
import org.betsy.dtc.SweepProgress
import org.betsy.dtc.SweepStep
import org.betsy.transport.awaitBlocking
import org.betsy.ui.theme.applyBetsyTheme

/**
 * Read-only DTC / INF screen (PROTOCOL.md §7). Runs one HV + engine DTC + INF sweep on a
 * background thread and renders the decoded groups. No clear/erase action, reporting only. The
 * reads are slow, so they never run inside the fast poll cycle. Each ECU-addressed group goes
 * through ElmSession.withEcu, which is what keeps them from interleaving with the still-running
 * poll loop; the per-exchange lock alone would not, since ATSH is adapter-global state.
 */
class DtcActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var running = true

    private lateinit var bodyText: TextView
    private lateinit var statusText: TextView
    private lateinit var shareButton: Button
    private lateinit var scanSpinner: ProgressBar
    private lateinit var sweepPanel: LinearLayout
    private val phaseLabels = mutableMapOf<SweepPhase, TextView>()
    private val phaseStates = mutableMapOf<SweepPhase, TextView>()
    private lateinit var sweepBar: ProgressBar
    private lateinit var sweepPercent: TextView

    /** The sweep on screen. Sharing submits this rather than reading the car a second time. */
    private var lastResult: DtcReadResult? = null
    private var pendingDialog: CaptureShareDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBetsyTheme()
        setContentView(buildUi())
        read()
    }

    private fun read() {
        CaptureLog.log("UI", "DtcActivity read/refresh")
        // A refresh starts from a clean slate: the previous result is cleared so the screen does
        // not keep showing stale codes while the new sweep runs, and SHARE is disabled until it
        // returns a fresh result. The phase checklist is reset and shown in place of the body.
        bodyText.text = ""
        bodyText.visibility = View.GONE
        lastResult = null
        shareButton.isEnabled = false
        scanSpinner.visibility = View.VISIBLE
        sweepPanel.visibility = View.VISIBLE
        for (phase in SweepPhase.entries) {
            phaseLabels[phase]?.setTextColor(Color.GRAY)
            phaseStates[phase]?.apply {
                text = "·"
                setTextColor(Color.GRAY)
            }
        }
        sweepBar.progress = 0
        sweepPercent.text = "0%"
        statusText.text = "Reading DTC / INF codes…"
        statusText.setTextColor(Color.GRAY)
        Thread {
            if (!running) return@Thread
            try {
                val result =
                    awaitBlocking {
                        DtcReader(SessionHolder.session(), SessionHolder.info()).read(
                            progress =
                                SweepProgress { step ->
                                    handler.post { onSweepStep(step) }
                                },
                        )
                    }
                handler.post { render(result) }
            } catch (e: Exception) {
                CaptureLog.logThrowable("UI", e)
                handler.post {
                    scanSpinner.visibility = View.GONE
                    sweepPanel.visibility = View.GONE
                    bodyText.visibility = View.VISIBLE
                    statusText.text = "DTC read failed: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }.start()
    }

    /** Advances the phase checklist: prior phases tick, the active one shows x/y, and the total
     * bar and "N to go" count track the whole sweep. */
    private fun onSweepStep(step: SweepStep) {
        val activeIndex = SweepPhase.entries.indexOf(step.phase)
        SweepPhase.entries.forEachIndexed { index, phase ->
            val label = phaseLabels[phase]
            val state = phaseStates[phase]
            when {
                index < activeIndex -> {
                    label?.setTextColor(Color.GRAY)
                    state?.apply {
                        text = "✓"
                        setTextColor(Color.GREEN)
                    }
                }
                index == activeIndex -> {
                    label?.setTextColor(Color.WHITE)
                    state?.apply {
                        text = if (step.step >= step.phaseSteps) "✓" else "${step.step}/${step.phaseSteps}"
                        setTextColor(if (step.step >= step.phaseSteps) Color.GREEN else Color.WHITE)
                    }
                }
                else -> {
                    label?.setTextColor(Color.GRAY)
                    state?.apply {
                        text = "·"
                        setTextColor(Color.GRAY)
                    }
                }
            }
        }
        val pct = step.totalStep * 100 / step.totalSteps
        sweepBar.progress = pct
        sweepPercent.text = "$pct%"
        statusText.text = "Reading ${step.label}"
        statusText.setTextColor(Color.GRAY)
    }

    private fun buildUi(): ScrollView {
        val root =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(24), dp(16), dp(16))
            }

        column.addView(
            TextView(this).apply {
                text = "DTC / INF CODES"
                textSize = 18f
                setTextColor(Color.WHITE)
            },
        )

        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        actions.addView(
            Button(this).apply {
                text = "BACK"
                contentDescription = "Back"
                setOnClickListener { finish() }
            },
        )
        actions.addView(
            Button(this).apply {
                text = "REFRESH"
                setOnClickListener { read() }
            },
        )
        shareButton =
            Button(this).apply {
                text = "SHARE THIS SCAN"
                isEnabled = false
                setOnClickListener { share() }
            }
        actions.addView(shareButton)
        val actionsLp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        actionsLp.topMargin = dp(8)
        column.addView(actions, actionsLp)

        sweepPanel = buildSweepPanel()
        column.addView(sweepPanel)

        bodyText =
            TextView(this).apply {
                text = ""
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(16), 0, 0)
                gravity = Gravity.START
            }
        column.addView(bodyText)

        val statusRow =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
            }
        scanSpinner =
            ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                visibility = View.GONE
            }
        statusRow.addView(scanSpinner, LinearLayout.LayoutParams(dp(22), dp(22)))
        statusText =
            TextView(this).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(dp(10), 0, 0, 0)
            }
        statusRow.addView(statusText)
        column.addView(statusRow)

        root.addView(column)
        return root
    }

    /**
     * The five-phase checklist shown while a sweep runs: each phase names what it reads, with a
     * "·" pending, "x/y" while active, and "✓" once done, plus a total bar and "N to go". Hidden
     * once the result renders.
     */
    private fun buildSweepPanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(18), 0, 0)
            for (phase in SweepPhase.entries) {
                val row =
                    LinearLayout(this@DtcActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(5), 0, dp(5))
                    }
                val label =
                    TextView(this@DtcActivity).apply {
                        text = phase.label
                        textSize = 13f
                        setTextColor(Color.GRAY)
                    }
                row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val state =
                    TextView(this@DtcActivity).apply {
                        text = "·"
                        textSize = 13f
                        setTextColor(Color.GRAY)
                        setTypeface(android.graphics.Typeface.MONOSPACE)
                    }
                row.addView(state)
                phaseLabels[phase] = label
                phaseStates[phase] = state
                addView(row)
            }
            val barRow =
                LinearLayout(this@DtcActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(10), 0, 0)
                }
            sweepBar =
                ProgressBar(this@DtcActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = 0
                }
            barRow.addView(sweepBar, LinearLayout.LayoutParams(0, dp(6), 1f))
            sweepPercent =
                TextView(this@DtcActivity).apply {
                    text = "0%"
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(android.graphics.Typeface.MONOSPACE)
                    setPadding(dp(10), 0, 0, 0)
                }
            barRow.addView(sweepPercent)
            addView(barRow)
        }

    /**
     * Offers the sweep already on screen to the project. Routed through the disclosure on first
     * use, and never re-reads the car: [lastResult] is the same data the user is looking at.
     */
    private fun share() {
        val result = lastResult ?: return
        if (!CaptureConsent.isAccepted(this)) {
            CaptureDisclosureDialog(this) { share() }.show()
            return
        }
        val data =
            CaptureData
                .from(
                    result = result,
                    info = SessionHolder.info(),
                    elm = SessionHolder.session().adapterBanner,
                    version = BuildConfig.VERSION_NAME,
                    build = BuildConfig.GIT_HASH,
                ).copy(demo = DemoMode.active())
        val dialog =
            CaptureShareDialog(this, data) { ownerNotes ->
                submit(data.copy(ownerNotes = ownerNotes))
            }
        dialog.show()
        pendingDialog = dialog
    }

    private fun submit(data: CaptureData) {
        val dialog = pendingDialog ?: return
        dialog.setBusy(true)
        // Persisted before the attempt, so a send that dies with the process is still recoverable.
        // A demo share that is not demonstrating the failure path never touches the pending slot,
        // so it cannot clobber a real capture held on disk.
        if (!DemoMode.active() || DemoMode.shareFails()) {
            PendingCapture.save(this, data.toJson())
        }
        Thread {
            val outcome = CaptureUploader.submit(data)
            handler.post {
                if (!running) return@post
                when (outcome) {
                    is UploadResult.Ok -> {
                        PendingCapture.clear(this)
                        dialog.dismiss()
                        pendingDialog = null
                        statusText.text = "Shared. Thank you, that makes BETSY better."
                        statusText.setTextColor(Color.GREEN)
                    }
                    is UploadResult.Failed -> dialog.showError(outcome.reason)
                }
            }
        }.start()
    }

    /**
     * Status sheet: liveness, KWP2000 groups, generic $03/$07, INF k/5, then detail.
     *
     * Never collapses a completed sweep into "No DTCs or INF detail codes." Each category is
     * reported on its own, because a single blanket line cannot distinguish an ECU that answered
     * and had nothing to report from one that never answered at all.
     */
    private fun render(result: DtcReadResult) {
        lastResult = result
        shareButton.isEnabled = true
        scanSpinner.visibility = View.GONE
        sweepPanel.visibility = View.GONE
        bodyText.visibility = View.VISIBLE
        val sb = StringBuilder()

        result.liveness?.let { live ->
            val line =
                when {
                    live.detail == "Responding" -> "HV ECU 7E2: responding"
                    live.detail.startsWith("Negative response:") ->
                        "HV ECU 7E2: negative response — ${live.detail.removePrefix("Negative response: ").trim()}"
                    live.detail.startsWith("No response (timeout)") -> "HV ECU 7E2: no response (timeout)"
                    live.detail.startsWith("No response (NO DATA)") -> "HV ECU 7E2: no response (NO DATA)"
                    live.detail.startsWith("Adapter error:") ->
                        "HV ECU 7E2: adapter error — ${live.detail.removePrefix("Adapter error:").trim()}"
                    live.detail.startsWith("Unexpected response:") ->
                        "HV ECU 7E2: unexpected response — ${live.detail.removePrefix("Unexpected response:").trim()}"
                    else -> "HV ECU 7E2: ${live.detail}"
                }
            sb.append(line).append("\n\n")
        }

        sb.append(DtcTextFormatter.formatGroups(result.groups, result.infResolutions))

        // Generic OBD is a separate observation from Toyota enhanced — always show when present.
        if (result.liveness != null ||
            result.storedGenericDtcs.isNotEmpty() ||
            result.pendingGenericDtcs.isNotEmpty() ||
            result.rawResponses.containsKey("7E2/03") ||
            result.rawResponses.containsKey("7E2/07")
        ) {
            sb
                .append("Generic stored DTCs (\$03): ")
                .append(formatGenericCodes(result.storedGenericDtcs, result.notes, "03"))
                .append("\n")
            sb
                .append("Generic pending DTCs (\$07): ")
                .append(formatGenericCodes(result.pendingGenericDtcs, result.notes, "07"))
                .append("\n")
            sb.append("(generic OBD ≠ Toyota enhanced state)\n\n")
        }

        sb.append(DtcTextFormatter.formatInfEvidence(result.infCodes, result.infTablesResponded))

        CaptureLog.captureFile?.let { f ->
            sb.append("Raw log: ").append(f.name).append("\n")
            sb.append("Build: ").append(BuildConfig.BUILD_LABEL).append("\n")
        }

        if (result.notes.isNotEmpty()) {
            sb.append("\n")
            for (note in result.notes) {
                sb.append("- ").append(note).append("\n")
            }
        }
        bodyText.text = sb.toString()
        statusText.text = "Updated ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
        statusText.setTextColor(Color.GRAY)
    }

    private fun formatGenericCodes(
        codes: List<org.betsy.model.Dtc>,
        notes: List<String>,
        cmd: String,
    ): String {
        val fail = notes.firstOrNull { it.startsWith("Generic DTCs ($cmd):") }
        if (fail != null && codes.isEmpty()) {
            return fail.removePrefix("Generic DTCs ($cmd): ").trim()
        }
        if (codes.isEmpty()) return "0"
        return codes.joinToString(", ") { it.code }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
