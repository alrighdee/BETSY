package org.betsy.ui.connect

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * The connecting state: the target adapter with a progress ring, then the four named phases with the
 * time each one actually took. The point of naming them is that a failure says where it failed, so a
 * failed phase is marked red and carries the error instead of the whole screen going to a toast.
 */
class ConnectingPanel(
    context: Context,
    onCancel: () -> Unit,
) : LinearLayout(context) {
    private val adapterName: TextView
    private val adapterAddress: TextView
    private val ring = ProgressRingView(context)
    private val percentLabel: TextView
    private val stepRows = LinearLayout(context).apply { orientation = VERTICAL }
    private val errorText: TextView

    init {
        orientation = VERTICAL

        val header =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(Surfaces.dp(context, 15f), Surfaces.dp(context, 15f), Surfaces.dp(context, 15f), Surfaces.dp(context, 15f))
                background =
                    Surfaces.rounded(context, DesignTokens.GRAY_3, DesignTokens.RADIUS_4, DesignTokens.BRAND_SOLID)
            }
        val ringWrap =
            FrameLayout(context).apply {
                addView(ring, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
        percentLabel =
            TextStyles.figures(context, "0%", 11f, DesignTokens.BRAND_SOLID).apply {
                gravity = Gravity.CENTER
            }
        ringWrap.addView(
            percentLabel,
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { gravity = Gravity.CENTER },
        )
        header.addView(
            ringWrap,
            LayoutParams(Surfaces.dp(context, 40f), Surfaces.dp(context, 40f)).apply {
                rightMargin = Surfaces.dp(context, 14f)
            },
        )

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        adapterName = TextStyles.body(context, "", DesignTokens.TEXT_3, DesignTokens.GRAY_12, bold = true)
        adapterAddress = TextStyles.figures(context, "", 11f, DesignTokens.GRAY_10)
        column.addView(adapterName)
        column.addView(adapterAddress)
        header.addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(header, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        addView(
            stepRows,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = Surfaces.dp(context, 18f)
                leftMargin = Surfaces.dp(context, 4f)
                rightMargin = Surfaces.dp(context, 4f)
            },
        )

        errorText =
            TextStyles.body(context, "", DesignTokens.TEXT_2, DesignTokens.RED_TEXT).apply {
                visibility = GONE
            }
        addView(
            errorText,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 14f) },
        )

        val cancel =
            TextStyles.body(context, "Cancel", DesignTokens.TEXT_2, DesignTokens.GRAY_11).apply {
                gravity = Gravity.CENTER
                background =
                    Surfaces.rounded(context, Color.TRANSPARENT, DesignTokens.RADIUS_3, DesignTokens.ghostBorder, 1f)
                setOnClickListener { onCancel() }
            }
        addView(
            cancel,
            LayoutParams(MATCH_PARENT, Surfaces.dp(context, 46f)).apply {
                topMargin = Surfaces.dp(context, 18f)
            },
        )
    }

    fun bindTarget(candidate: AdapterCandidate) {
        adapterName.text = candidate.name
        adapterAddress.text = candidate.address
    }

    fun update(snapshot: ConnectSnapshot) {
        ring.percent = snapshot.percent
        ring.failed = snapshot.failed
        percentLabel.text = "${snapshot.percent}%"

        stepRows.removeAllViews()
        snapshot.steps.forEach { stepRows.addView(stepRow(it)) }
    }

    fun showError(message: String) {
        errorText.text = message
        errorText.visibility = VISIBLE
    }

    fun clearError() {
        errorText.visibility = GONE
    }

    private fun stepRow(step: ConnectStep): LinearLayout {
        val row =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, Surfaces.dp(context, 7f), 0, Surfaces.dp(context, 7f))
            }
        val dot =
            TextView(context).apply {
                text =
                    when (step.state) {
                        StepState.DONE -> "✓"
                        StepState.FAILED -> "✕"
                        else -> ""
                    }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = Surfaces.circle(context, dotColor(step.state))
            }
        row.addView(
            dot,
            LinearLayout.LayoutParams(Surfaces.dp(context, 20f), Surfaces.dp(context, 20f)).apply {
                rightMargin = Surfaces.dp(context, 12f)
            },
        )
        row.addView(
            TextStyles.body(context, step.text, DesignTokens.TEXT_2, labelColor(step.state)),
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
        )
        row.addView(
            TextStyles.figures(context, step.elapsedMs?.let { "$it ms" } ?: "", 10f, DesignTokens.GRAY_10),
        )
        return row
    }

    private fun dotColor(state: StepState): Int =
        when (state) {
            StepState.DONE -> DesignTokens.GREEN_SOLID
            StepState.ACTIVE -> DesignTokens.BRAND_SOLID
            StepState.FAILED -> DesignTokens.RED_TEXT
            StepState.PENDING -> DesignTokens.GRAY_4
        }

    private fun labelColor(state: StepState): Int = if (state == StepState.PENDING) DesignTokens.GRAY_9 else DesignTokens.GRAY_12
}
