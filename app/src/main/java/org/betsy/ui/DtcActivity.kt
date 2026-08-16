package org.betsy.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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
import org.betsy.decode.DtcMeaning
import org.betsy.decode.InfMeaning
import org.betsy.dtc.DtcReadResult
import org.betsy.dtc.DtcReader
import org.betsy.dtc.SweepPhase
import org.betsy.dtc.SweepProgress
import org.betsy.dtc.SweepStep
import org.betsy.model.Dtc
import org.betsy.transport.awaitBlocking
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles
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

    private lateinit var resultsHost: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var shareButton: TextView
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
        resultsHost.removeAllViews()
        resultsHost.visibility = View.GONE
        lastResult = null
        setShareEnabled(false)
        scanSpinner.visibility = View.VISIBLE
        sweepPanel.visibility = View.VISIBLE
        for (phase in SweepPhase.entries) {
            phaseLabels[phase]?.setTextColor(DesignTokens.GRAY_10)
            phaseStates[phase]?.apply {
                text = "·"
                setTextColor(DesignTokens.GRAY_10)
            }
        }
        sweepBar.progress = 0
        sweepPercent.text = "0%"
        statusText.text = "Reading codes…"
        statusText.setTextColor(DesignTokens.GRAY_11)
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
                    resultsHost.visibility = View.VISIBLE
                    resultsHost.removeAllViews()
                    resultsHost.addView(metaCard("Couldn't read codes", e.message ?: e.toString()))
                    statusText.text = "Read failed"
                    statusText.setTextColor(DesignTokens.RED_TEXT)
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
                    label?.setTextColor(DesignTokens.GRAY_10)
                    state?.apply {
                        text = "✓"
                        setTextColor(DesignTokens.GREEN_TEXT)
                    }
                }
                index == activeIndex -> {
                    label?.setTextColor(DesignTokens.GRAY_12)
                    state?.apply {
                        text = if (step.step >= step.phaseSteps) "✓" else "${step.step}/${step.phaseSteps}"
                        setTextColor(
                            if (step.step >= step.phaseSteps) DesignTokens.GREEN_TEXT else DesignTokens.GRAY_12,
                        )
                    }
                }
                else -> {
                    label?.setTextColor(DesignTokens.GRAY_10)
                    state?.apply {
                        text = "·"
                        setTextColor(DesignTokens.GRAY_10)
                    }
                }
            }
        }
        val pct = step.totalStep * 100 / step.totalSteps
        sweepBar.progress = pct
        sweepPercent.text = "$pct%"
        statusText.text = "Reading ${step.label}"
        statusText.setTextColor(DesignTokens.GRAY_11)
    }

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(DesignTokens.GRAY_1)
            }
        root.addView(header())

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = Surfaces.dp(context, 18)
                setPadding(p, Surfaces.dp(context, 16), p, Surfaces.dp(context, 24))
            }

        val actions =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        actions.addView(
            actionButton("Refresh") { read() },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) },
        )
        shareButton = actionButton("Contribute scan data") { share() }
        setShareEnabled(false)
        actions.addView(shareButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        column.addView(actions)

        sweepPanel = buildSweepPanel()
        column.addView(sweepPanel)

        resultsHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(resultsHost)

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
            TextStyles.body(this, "", DesignTokens.TEXT_2, DesignTokens.GRAY_11).apply {
                setPadding(dp(10), 0, 0, 0)
            }
        statusRow.addView(statusText)
        column.addView(statusRow)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun header(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(DesignTokens.GRAY_2)
            setPadding(dp(18), dp(20), dp(18), dp(18))
            addView(
                TextView(this@DtcActivity).apply {
                    text = "Codes"
                    textSize = DesignTokens.TEXT_5
                    setTextColor(DesignTokens.GRAY_12)
                    setTypeface(Typeface.DEFAULT_BOLD)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextStyles.body(context, "Done", DesignTokens.TEXT_3, DesignTokens.BRAND_SOLID, bold = true).apply {
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    contentDescription = "Back"
                    setPadding(dp(12), dp(8), 0, dp(8))
                    setOnClickListener { finish() }
                },
            )
        }

    /**
     * The five-phase checklist shown while a sweep runs: each phase names what it reads, with a
     * "·" pending, "x/y" while active, and "✓" once done, plus a total bar. Hidden once the
     * result renders.
     */
    private fun buildSweepPanel(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(20), 0, 0)
            for (phase in SweepPhase.entries) {
                val row =
                    LinearLayout(this@DtcActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                val label =
                    TextStyles.body(this@DtcActivity, phase.label, DesignTokens.TEXT_2, DesignTokens.GRAY_10)
                row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val state =
                    TextStyles.figures(this@DtcActivity, "·", DesignTokens.TEXT_2, DesignTokens.GRAY_10)
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
                TextStyles.figures(this@DtcActivity, "0%", DesignTokens.TEXT_2, DesignTokens.GRAY_12).apply {
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
                        statusText.setTextColor(DesignTokens.GREEN_TEXT)
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
        setShareEnabled(true)
        scanSpinner.visibility = View.GONE
        sweepPanel.visibility = View.GONE
        resultsHost.visibility = View.VISIBLE
        resultsHost.removeAllViews()

        result.liveness?.let { live ->
            val (line, ok) =
                when {
                    live.detail == "Responding" -> "HV ECU 7E2 responding" to true
                    live.detail.startsWith("Negative response:") ->
                        "HV ECU 7E2 negative response — ${live.detail.removePrefix("Negative response: ").trim()}" to
                            false
                    live.detail.startsWith("No response (timeout)") -> "HV ECU 7E2 no response (timeout)" to false
                    live.detail.startsWith("No response (NO DATA)") -> "HV ECU 7E2 no response (NO DATA)" to false
                    live.detail.startsWith("Adapter error:") ->
                        "HV ECU 7E2 adapter error — ${live.detail.removePrefix("Adapter error:").trim()}" to false
                    live.detail.startsWith("Unexpected response:") ->
                        "HV ECU 7E2 unexpected — ${live.detail.removePrefix("Unexpected response:").trim()}" to false
                    else -> "HV ECU 7E2 ${live.detail}" to live.responding
                }
            resultsHost.addView(chipRow(line, ok))
        }

        val exactByParent =
            result.infResolutions
                .filterIsInstance<InfMeaning.Resolution.Exact>()
                .groupBy { it.dtc }

        if (result.groups.isEmpty()) {
            resultsHost.addView(
                metaCard("Toyota enhanced", "No stored codes reported."),
            )
        } else {
            for (group in result.groups) {
                for (dtc in group.codes) {
                    resultsHost.addView(faultCard(group.label, dtc, exactByParent[dtc.code].orEmpty()))
                }
            }
        }

        for (shared in result.infResolutions.filterIsInstance<InfMeaning.Resolution.Shared>()) {
            resultsHost.addView(sharedCard(shared))
        }

        if (result.liveness != null ||
            result.storedGenericDtcs.isNotEmpty() ||
            result.pendingGenericDtcs.isNotEmpty() ||
            result.rawResponses.containsKey("7E2/03") ||
            result.rawResponses.containsKey("7E2/07")
        ) {
            val stored = formatGenericCodes(result.storedGenericDtcs, result.notes, "03")
            val pending = formatGenericCodes(result.pendingGenericDtcs, result.notes, "07")
            resultsHost.addView(
                metaCard(
                    "Generic OBD",
                    "Stored (\$03): $stored\nPending (\$07): $pending\nGeneric OBD is not the Toyota enhanced state.",
                ),
            )
        }

        val evidence = StringBuilder()
        evidence.append("INF pages ").append(result.infTablesResponded).append("/5 responded")
        if (result.infCodes.isNotEmpty()) {
            evidence.append("\n")
            for ((table, codes) in result.infCodes.groupBy { it.tableLabel }.toSortedMap()) {
                evidence
                    .append(table)
                    .append(": ")
                    .append(codes.joinToString(", ") { it.code.toString() })
                    .append("\n")
            }
            evidence.append("Raw values retained with the scan.")
        }
        CaptureLog.captureFile?.let { f ->
            evidence.append("\n").append(f.name)
            evidence.append("\n").append(BuildConfig.BUILD_LABEL)
        }
        if (result.notes.isNotEmpty()) {
            evidence.append("\n")
            for (note in result.notes) evidence.append("\n").append(note)
        }
        resultsHost.addView(metaCard("Scan detail", evidence.toString().trim()))

        statusText.text = "Updated ${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())}"
        statusText.setTextColor(DesignTokens.GRAY_10)
    }

    private fun faultCard(
        ecu: String,
        dtc: Dtc,
        exact: List<InfMeaning.Resolution.Exact>,
    ): View {
        val meaning = DtcMeaning.forWire(dtc.raw)
        val card = card()
        card.addView(TextStyles.body(this, ecu, DesignTokens.TEXT_1, DesignTokens.GRAY_10))
        card.addView(
            TextView(this).apply {
                text = dtc.code
                textSize = DesignTokens.TEXT_6
                setTextColor(DesignTokens.GRAY_12)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, dp(4), 0, 0)
            },
        )
        when (exact.size) {
            0 -> card.addView(bodyLine("No sub-code read", muted = true, top = 6))
            1 -> card.addView(subcodeLine(exact.single().inf))
            else -> {
                for (resolution in exact) {
                    card.addView(subcodeLine(resolution.inf))
                }
            }
        }
        meaning?.let { card.addView(severityChip(it.severity)) }

        when (exact.size) {
            1 -> {
                val detail = exact.single().detail
                card.addView(bodyLine(detail.narrows, top = 12))
                if (detail.area.isNotBlank()) {
                    card.addView(bodyLine("Look at: ${detail.area}", muted = true, top = 8))
                }
            }
            else -> {
                for (resolution in exact) {
                    card.addView(bodyLine("Sub-code ${resolution.inf}: ${resolution.detail.narrows}", top = 8))
                    if (resolution.detail.area.isNotBlank()) {
                        card.addView(bodyLine("Look at: ${resolution.detail.area}", muted = true, top = 4))
                    }
                }
            }
        }

        meaning?.let {
            card.addView(bodyLine("${it.severity.advice} ${it.what}".trim(), top = 12))
            card.addView(bodyLine(it.usually, muted = true, top = 8))
        }
        return wrapCard(card)
    }

    private fun sharedCard(shared: InfMeaning.Resolution.Shared): View {
        val card = card()
        card.addView(
            TextStyles.body(
                this,
                "Shared sub-code ${shared.inf}",
                DesignTokens.TEXT_3,
                DesignTokens.GRAY_12,
                bold = true,
            ),
        )
        card.addView(bodyLine(shared.dtcs.joinToString(" / "), muted = true, top = 4))
        card.addView(bodyLine(shared.detail.narrows, top = 10))
        if (shared.detail.area.isNotBlank()) {
            card.addView(bodyLine("Look at: ${shared.detail.area}", muted = true, top = 8))
        }
        return wrapCard(card)
    }

    private fun chipRow(
        text: String,
        ok: Boolean,
    ): View {
        val ink = if (ok) DesignTokens.GREEN_TEXT else DesignTokens.AMBER_TEXT
        val fill = if (ok) DesignTokens.GREEN_BG else DesignTokens.AMBER_BG
        return TextStyles.body(this, text, DesignTokens.TEXT_2, ink, bold = true).apply {
            background = Surfaces.rounded(context, fill, DesignTokens.RADIUS_PILL)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            lp.topMargin = dp(18)
            layoutParams = lp
        }
    }

    private fun subcodeLine(inf: Int): View {
        val row =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                lp.topMargin = dp(8)
                layoutParams = lp
            }
        row.addView(
            TextStyles.body(this, "Sub-code", DesignTokens.TEXT_2, DesignTokens.BRAND_SOLID, bold = true),
        )
        row.addView(
            TextView(this).apply {
                text = inf.toString()
                textSize = DesignTokens.TEXT_STAT
                setTextColor(DesignTokens.BRAND_SOLID)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(dp(8), 0, 0, 0)
            },
        )
        return row
    }

    private fun severityChip(severity: DtcMeaning.Severity): View {
        val (ink, fill, label) =
            when (severity) {
                DtcMeaning.Severity.URGENT ->
                    Triple(DesignTokens.RED_TEXT, DesignTokens.RED_BG, "Stop driving")
                DtcMeaning.Severity.SERIOUS ->
                    Triple(DesignTokens.AMBER_TEXT, DesignTokens.AMBER_BG, "Look at this soon")
                DtcMeaning.Severity.MINOR ->
                    Triple(DesignTokens.GREEN_TEXT, DesignTokens.GREEN_BG, "No hurry")
            }
        return TextStyles.body(this, label, DesignTokens.TEXT_1, ink, bold = true).apply {
            background = Surfaces.rounded(context, fill, DesignTokens.RADIUS_PILL)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            val lp =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
    }

    private fun metaCard(
        title: String,
        body: String,
    ): View {
        val card = card()
        card.addView(TextStyles.body(this, title, DesignTokens.TEXT_2, DesignTokens.GRAY_11, bold = true))
        card.addView(bodyLine(body, muted = true, top = 8))
        return wrapCard(card)
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                Surfaces.rounded(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
            val p = dp(16)
            setPadding(p, p, p, p)
        }

    private fun wrapCard(card: View): View {
        val lp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        lp.topMargin = dp(14)
        card.layoutParams = lp
        return card
    }

    private fun bodyLine(
        text: String,
        muted: Boolean = false,
        top: Int = 0,
    ): TextView =
        TextStyles
            .body(
                this,
                text,
                DesignTokens.TEXT_2,
                if (muted) DesignTokens.GRAY_11 else DesignTokens.GRAY_12,
            ).apply {
                if (top > 0) {
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.topMargin = dp(top)
                    layoutParams = lp
                }
            }

    private fun actionButton(
        label: String,
        onClick: () -> Unit,
    ): TextView =
        TextStyles.body(this, label, DesignTokens.TEXT_3, DesignTokens.GRAY_12, bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background =
                Surfaces.ripple(
                    context,
                    DesignTokens.GRAY_2,
                    DesignTokens.RADIUS_CARD,
                    DesignTokens.cardBorder,
                    rippleColor = DesignTokens.BRAND_SOLID,
                )
            setOnClickListener { onClick() }
        }

    private fun setShareEnabled(on: Boolean) {
        shareButton.isEnabled = on
        shareButton.alpha = if (on) 1f else 0.4f
    }

    private fun formatGenericCodes(
        codes: List<Dtc>,
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
