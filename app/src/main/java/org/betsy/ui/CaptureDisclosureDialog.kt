package org.betsy.ui

import android.app.Activity
import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.capture.CaptureConsent
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * Shown once, before the first capture ever leaves the phone.
 *
 * A capture is committed to a public repository, and the person tapping SHARE deserves to know
 * that before it happens rather than after. This is the only friction the flow adds: one screen,
 * one button, no account and no form. Nothing in the capture package opens a connection until
 * [CaptureConsent.isAccepted] is true.
 */
class CaptureDisclosureDialog(
    private val activity: Activity,
    private val onAccepted: () -> Unit,
) : Dialog(activity) {
    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(buildUi())
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        window?.setBackgroundDrawable(Surfaces.rounded(activity, DesignTokens.GRAY_1, 0f))
    }

    private fun buildUi(): View {
        val scroll = ScrollView(activity).apply { isFillViewport = true }
        val column =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad(20), pad(28), pad(20), pad(20))
            }

        column.addView(
            TextStyles.hero(activity, "Help make BETSY better", DesignTokens.TEXT_6),
        )
        column.addView(
            TextStyles
                .body(
                    activity,
                    "BETSY can read your hybrid battery, but the part that names a fault's sub-code " +
                        "has never been checked against a car that is genuinely broken. Sharing one " +
                        "scan is what fixes that, for everyone.",
                    DesignTokens.TEXT_3,
                    DesignTokens.GRAY_12,
                ).apply { setPadding(0, pad(14), 0, 0) },
        )
        column.addView(
            TextStyles
                .body(
                    activity,
                    "Your scan is published to BETSY's public repository on GitHub, where it is used " +
                        "to check the decoder against real faults.",
                ).apply { setPadding(0, pad(12), 0, 0) },
        )

        column.addView(group("WHAT IS SENT", CaptureConsent.SENT, DesignTokens.GREEN_TEXT))
        column.addView(group("WHAT IS NOT SENT", CaptureConsent.NOT_SENT, DesignTokens.RED_TEXT))

        column.addView(
            TextStyles
                .body(
                    activity,
                    "No account, no sign-in. You will not see this screen again.",
                    DesignTokens.TEXT_1,
                    DesignTokens.GRAY_10,
                ).apply { setPadding(0, pad(16), 0, 0) },
        )

        val row =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, pad(24), 0, 0)
            }
        row.addView(
            Button(activity).apply {
                text = "NOT NOW"
                setTextColor(DesignTokens.GRAY_10)
                background = null
                setOnClickListener { dismiss() }
            },
        )
        row.addView(
            Button(activity).apply {
                text = "COUNT ME IN"
                setTextColor(DesignTokens.GRAY_1)
                background = Surfaces.rounded(activity, DesignTokens.BRAND_SOLID, DesignTokens.RADIUS_2)
                setOnClickListener {
                    CaptureConsent.accept(activity)
                    dismiss()
                    onAccepted()
                }
            },
        )
        column.addView(row)

        scroll.addView(column)
        return scroll
    }

    private fun group(
        heading: String,
        items: List<String>,
        markColor: Int,
    ): View {
        val box =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, pad(18), 0, 0)
            }
        box.addView(
            TextStyles.figures(activity, heading, DesignTokens.TEXT_TINY, DesignTokens.GRAY_10),
        )
        for (item in items) {
            val line =
                LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, pad(6), 0, 0)
                }
            line.addView(
                TextView(activity).apply {
                    text = "•"
                    textSize = DesignTokens.TEXT_2
                    setTextColor(markColor)
                    setPadding(0, 0, pad(8), 0)
                },
            )
            line.addView(TextStyles.body(activity, item))
            box.addView(line)
        }
        return box
    }

    private fun pad(v: Int): Int = Surfaces.dp(activity, v)
}
