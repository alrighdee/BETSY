package org.betsy.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import org.betsy.capture.CaptureData
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * Shows what is about to be shared and collects the one thing the bytes cannot supply.
 *
 * Three states, and the middle one matters most: DTCs read but no sub-code recognised means the
 * bit mapping is wrong, which is the single most useful thing a stranger's car can tell this
 * project. It is presented as worth sending, not as an error.
 *
 * The scan is never re-run here. The result is already in hand, so a failed send can be retried
 * without touching the car again.
 */
class CaptureShareDialog(
    private val activity: Activity,
    private val data: CaptureData,
    private val onSubmit: (ownerNotes: String) -> Unit,
) : Dialog(activity) {
    private lateinit var notesField: EditText
    private lateinit var submitButton: Button
    private lateinit var statusLine: android.widget.TextView

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildUi())
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window?.setGravity(Gravity.BOTTOM)
        window?.setBackgroundDrawable(Surfaces.rounded(activity, DesignTokens.GRAY_2, DesignTokens.RADIUS_4))
    }

    private fun buildUi(): View {
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val column =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad(20), pad(20), pad(20), pad(18))
            }

        when {
            data.dtcs.isEmpty() -> buildNoCodes(column)
            data.codes.isEmpty() -> buildDecoderMiss(column)
            else -> buildDecoded(column)
        }

        if (data.raw.isNotEmpty()) column.addView(rawBlock())

        // Only worth asking when there is a fault to describe.
        if (data.dtcs.isNotEmpty()) {
            column.addView(
                TextStyles
                    .figures(activity, "Anything you already know about this fault?", DesignTokens.TEXT_1)
                    .apply { setPadding(0, pad(16), 0, pad(6)) },
            )
            notesField =
                EditText(activity).apply {
                    hint = "Dealer report, workshop verdict, symptoms. Optional."
                    setTextColor(DesignTokens.GRAY_12)
                    setHintTextColor(DesignTokens.GRAY_10)
                    textSize = DesignTokens.TEXT_2
                    background = Surfaces.rounded(activity, DesignTokens.GRAY_4, DesignTokens.RADIUS_2)
                    setPadding(pad(12), pad(10), pad(12), pad(10))
                }
            column.addView(notesField)
        }

        statusLine =
            TextStyles.body(activity, "", DesignTokens.TEXT_1, DesignTokens.GRAY_10).apply {
                setPadding(0, pad(12), 0, 0)
                visibility = View.GONE
            }
        column.addView(statusLine)

        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, pad(18), 0, 0)
            }
        row.addView(
            Button(activity).apply {
                text = "NOT NOW"
                setTextColor(DesignTokens.GRAY_10)
                background = null
                setOnClickListener { dismiss() }
            },
        )
        submitButton =
            Button(activity).apply {
                text = if (data.dtcs.isEmpty()) "SEND TEST CAPTURE" else "SHARE SCAN"
                setTextColor(DesignTokens.GRAY_1)
                background = Surfaces.rounded(activity, DesignTokens.BRAND_SOLID, DesignTokens.RADIUS_2)
                setOnClickListener {
                    val notes = if (::notesField.isInitialized) notesField.text.toString().trim() else ""
                    onSubmit(notes)
                }
            }
        row.addView(submitButton)
        column.addView(row)

        scroll.addView(column)
        return scroll
    }

    private fun buildDecoded(column: LinearLayout) {
        column.addView(header("${data.dtcs.size} STORED CODE${if (data.dtcs.size == 1) "" else "S"}"))
        for (d in data.dtcs) column.addView(TextStyles.figures(activity, d, DesignTokens.TEXT_4, DesignTokens.GRAY_12, bold = true))
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Sub-codes: " + data.codes.joinToString(", ") { "${it.table} ${it.code}" },
                    DesignTokens.TEXT_2,
                    DesignTokens.GREEN_TEXT,
                ).apply { setPadding(0, pad(8), 0, 0) },
        )
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Share it and make BETSY better. Your scan confirms the decoder read this correctly.",
                ).apply { setPadding(0, pad(10), 0, 0) },
        )
    }

    private fun buildDecoderMiss(column: LinearLayout) {
        column.addView(header("${data.dtcs.size} STORED CODE${if (data.dtcs.size == 1) "" else "S"}"))
        for (d in data.dtcs) column.addView(TextStyles.figures(activity, d, DesignTokens.TEXT_4, DesignTokens.GRAY_12, bold = true))
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Sub-code not recognised",
                    DesignTokens.TEXT_2,
                    DesignTokens.AMBER_TEXT,
                ).apply { setPadding(0, pad(8), 0, 0) },
        )
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Please share this one. A fault BETSY cannot name is exactly the data needed to " +
                        "teach it, and the raw bytes go up either way.",
                ).apply { setPadding(0, pad(10), 0, 0) },
        )
    }

    private fun buildNoCodes(column: LinearLayout) {
        column.addView(header("NO STORED CODES"))
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Nothing is wrong with this car, which is what a healthy pack should look like.",
                    DesignTokens.TEXT_3,
                    DesignTokens.GRAY_12,
                ).apply { setPadding(0, pad(6), 0, 0) },
        )
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Sending it anyway still helps: it proves the reader works on your adapter, which " +
                        "is how a faulty car's empty result can later be told apart from a failed read.",
                ).apply { setPadding(0, pad(10), 0, 0) },
        )
    }

    private fun rawBlock(): View {
        val box =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad(12), pad(10), pad(12), pad(10))
                background = Surfaces.rounded(activity, DesignTokens.GRAY_4, DesignTokens.RADIUS_2)
            }
        val lp =
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = pad(14) }
        box.layoutParams = lp
        for ((req, resp) in data.raw) {
            box.addView(
                TextStyles
                    .figures(
                        activity,
                        "$req  ${resp.take(48)}",
                        DesignTokens.TEXT_TINY,
                        DesignTokens.GRAY_11,
                    ).apply { setSingleLine(true) },
            )
        }
        for (note in data.notes.take(3)) {
            box.addView(
                TextStyles.figures(activity, note, DesignTokens.TEXT_TINY, DesignTokens.AMBER_TEXT),
            )
        }
        val wrapper = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        wrapper.addView(box, lp)
        return wrapper
    }

    private fun header(text: String) =
        TextStyles
            .figures(activity, text, DesignTokens.TEXT_1, DesignTokens.GRAY_10)
            .apply { setPadding(0, 0, 0, pad(6)) }

    /** Called from the activity while a submission is in flight. */
    fun setBusy(busy: Boolean) {
        submitButton.isEnabled = !busy
        statusLine.visibility = if (busy) View.VISIBLE else statusLine.visibility
        if (busy) {
            statusLine.text = "Sending…"
            statusLine.setTextColor(DesignTokens.GRAY_10)
        }
    }

    /** Failure keeps the capture and leaves SHARE available; nothing has to be read again. */
    fun showError(message: String) {
        // The message already says what happened and what becomes of the scan; appending a
        // blanket reassurance here is how "try again later" ended up promising a retry that did
        // not exist.
        submitButton.isEnabled = true
        statusLine.visibility = View.VISIBLE
        statusLine.text = message
        statusLine.setTextColor(DesignTokens.RED_TEXT)
    }

    private fun pad(v: Int): Int = Surfaces.dp(activity, v)
}
