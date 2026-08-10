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
 * Read-only DTC / INF screen (PROTOCOL.md §9). Runs one HV DTC + INF sweep on a background
 * thread and renders the decoded groups. No clear/erase action, reporting only. The reads
 * are slow, so they never run inside the fast poll cycle. Each ECU-addressed group goes through
 * ElmSession.withEcu, which is what keeps them from interleaving with the still-running poll
 * loop, the per-exchange lock alone would not, since ATSH is adapter-global state.
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

    private fun render(result: DtcReadResult) {
        lastResult = result
        shareButton.isEnabled = true
        val sb = StringBuilder()
        for (group in result.groups) {
            sb.append(group.label).append(":\n")
            sb.append("  ").append(group.codes.joinToString(", ") { it.code }).append("\n\n")
        }
        if (result.infCodes.isNotEmpty()) {
            // A fault reports a code from more than one table, and the combination is what
            // names the failed component (PROTOCOL.md §9.4.0). Flattening these into one list
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
            // The byte→code mapping has never been checked against a car with a real fault, so
            // say so here rather than presenting a derived number as a diagnosis.
            sb.append("  (mapping unverified, see docs/PROTOCOL.md §9.4.0)\n")
            sb.append("\n")
        }
        if (!result.hasCodes) {
            sb.append("No DTCs or INF detail codes.\n")
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

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
