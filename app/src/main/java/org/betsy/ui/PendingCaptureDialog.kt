package org.betsy.ui

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import org.betsy.capture.CaptureConsent
import org.betsy.capture.CaptureUploader
import org.betsy.capture.PendingCapture
import org.betsy.capture.UploadResult
import org.betsy.debug.CaptureLog
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * Offers a capture that failed to send, held on disk. Shown on the launch screen after a cold
 * start, and previewable from the debug Settings. Resending never involves the car: it is a POST
 * of bytes already collected.
 */
class PendingCaptureDialog(
    private val activity: Activity,
    private val onDismiss: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())

    /** Shows the dialog when a pending capture exists and disclosure has been accepted. */
    fun show() {
        if (!CaptureConsent.isAccepted(activity)) return
        val json = PendingCapture.load(activity) ?: return
        CaptureLog.log("CAPTURE", "pending capture found, offering to resend")

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setOnDismissListener { onDismiss() }
        val pad = Surfaces.dp(activity, 20)
        val column =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        column.addView(
            TextStyles.body(activity, "One scan is waiting to be sent", DesignTokens.TEXT_4, DesignTokens.GRAY_12, bold = true),
        )
        column.addView(
            TextStyles
                .body(activity, "It was read from your car but could not be uploaded at the time. Sending it does not need the adapter.")
                .apply { setPadding(0, Surfaces.dp(activity, 10), 0, 0) },
        )
        val status =
            TextStyles.body(activity, "", DesignTokens.TEXT_1, DesignTokens.GRAY_10).apply {
                setPadding(0, Surfaces.dp(activity, 12), 0, 0)
                visibility = View.GONE
            }
        column.addView(status)

        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, Surfaces.dp(activity, 16), 0, 0)
            }
        val discard =
            Button(activity).apply {
                text = "DISCARD"
                setTextColor(DesignTokens.GRAY_10)
                background = null
                setOnClickListener {
                    PendingCapture.clear(activity)
                    CaptureLog.log("CAPTURE", "pending capture discarded by user")
                    dialog.dismiss()
                }
            }
        val send =
            Button(activity).apply {
                text = "SEND NOW"
                setTextColor(DesignTokens.GRAY_1)
                background = Surfaces.ripple(activity, DesignTokens.BRAND_SOLID, DesignTokens.RADIUS_2)
            }
        send.setOnClickListener {
            send.isEnabled = false
            status.visibility = View.VISIBLE
            status.text = "Sending…"
            Thread {
                val outcome = CaptureUploader.submitJson(json)
                handler.post {
                    when (outcome) {
                        is UploadResult.Ok -> {
                            PendingCapture.clear(activity)
                            dialog.dismiss()
                            Toast.makeText(activity, "Scan sent. Thank you.", Toast.LENGTH_LONG).show()
                        }
                        is UploadResult.Failed -> {
                            // Kept, not discarded: a second failure is not a reason to lose it.
                            send.isEnabled = true
                            status.text = outcome.reason
                            status.setTextColor(DesignTokens.RED_TEXT)
                        }
                    }
                }
            }.start()
        }
        row.addView(discard)
        row.addView(send)
        column.addView(row)

        dialog.setContentView(column)
        dialog.window?.setBackgroundDrawable(
            Surfaces.rounded(activity, DesignTokens.GRAY_2, DesignTokens.RADIUS_4),
        )
        dialog.show()
    }
}
