package org.betsy.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.BuildConfig
import org.betsy.capture.CaptureConsent
import org.betsy.capture.CaptureData
import org.betsy.capture.CaptureUploader
import org.betsy.capture.PendingCapture
import org.betsy.capture.UploadResult
import org.betsy.debug.CaptureLog
import org.betsy.dtc.DtcReadResult
import org.betsy.dtc.DtcReader
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
        statusText.text = "Reading DTC / INF codes…"
        statusText.setTextColor(Color.GRAY)
        Thread {
            if (!running) return@Thread
            try {
                val result = awaitBlocking { DtcReader(SessionHolder.session(), SessionHolder.info()).read() }
                handler.post { render(result) }
            } catch (e: Exception) {
                CaptureLog.logThrowable("UI", e)
                handler.post {
                    statusText.text = "DTC read failed: ${e.message}"
                    statusText.setTextColor(Color.RED)
                }
            }
        }.start()
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

        bodyText =
            TextView(this).apply {
                text = ""
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(16), 0, 0)
                gravity = Gravity.START
            }
        column.addView(bodyText)

        statusText =
            TextView(this).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, dp(16), 0, 0)
            }
        column.addView(statusText)

        root.addView(column)
        return root
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
            CaptureData.from(
                result = result,
                info = SessionHolder.info(),
                elm = SessionHolder.session().adapterBanner,
                version = BuildConfig.VERSION_NAME,
                build = BuildConfig.GIT_HASH,
                logTail = CaptureLog.tail(CaptureData.LOG_TAIL_LINES),
            )
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
        PendingCapture.save(this, data.toJson())
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
     * Never collapses a completed sweep into "No DTCs or INF detail codes."
     * (openspec gen2-diagnostics R5).
     */
    private fun render(result: DtcReadResult) {
        lastResult = result
        shareButton.isEnabled = true
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

        if (result.groups.isNotEmpty()) {
            for (group in result.groups) {
                sb.append(group.label).append(":\n")
                sb.append("  ").append(group.codes.joinToString(", ") { it.code }).append("\n\n")
            }
        } else {
            sb.append("Toyota enhanced DTCs (KWP2000): none reported\n\n")
        }

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

        sb.append("INF tables: ${result.infTablesResponded}/5 responded\n\n")

        if (result.infCodes.isNotEmpty()) {
            // A fault reports a code from more than one table, and the combination is what
            // names the failed component (PROTOCOL.md §7.4). Flattening these into one list
            // throws that away, so group by table.
            sb.append("INF DETAIL CODES:\n")
            for ((table, codes) in result.infCodes.groupBy { it.tableLabel }.toSortedMap()) {
                sb.append("  ").append(table).append(": ")
                sb.append(codes.joinToString(", ") { it.code.toString() }).append("\n")
            }
            val pair = result.infCodes.map { it.code }.sorted()
            if (pair.size > 1) {
                sb.append("  → ").append(pair.joinToString("-")).append("\n")
            }
            sb.append("  (mapping unverified, see docs/PROTOCOL.md §7.4)\n")
            sb.append("\n")
        }

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
