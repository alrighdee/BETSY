package org.betsy.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.betsy.model.BatteryModel

/**
 * Per-block voltage bar chart, coloured by internal resistance (§4.1 bands):
 * IR raw ≥ 30 mΩ turns the bar yellow (PROTOCOL.md §4.1 bands).
 */
class BlockBarView(
    context: Context,
    private val model: BatteryModel,
) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11f * resources.displayMetrics.scaledDensity
        }
    private val gridPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            strokeWidth = 1f
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val blocks = model.blockVolts
        if (blocks.isEmpty()) return

        val ir = model.internalResistance
        val slotW = width / blocks.size.toFloat()
        val chartH = height - dp(20).toFloat()
        // 12.0–20.0 V per 2-module block is the sane band (Appendix A); map it to full height.
        val vMin = 12.0f
        val vMax = 20.0f

        for (i in blocks.indices) {
            val v = blocks[i]
            val frac = ((v - vMin) / (vMax - vMin)).coerceIn(0f, 1f)
            val resistance = ir.getOrElse(i) { 0 }
            barPaint.color =
                when {
                    resistance >= 30 -> Color.rgb(255, 200, 0) // yellow ≥ 30 mΩ (§4.1)
                    else -> Color.rgb(46, 190, 90)
                }
            val left = i * slotW
            val top = chartH * (1 - frac)
            canvas.drawRect(left + dp(2), top, left + slotW - dp(2), chartH, barPaint)

            // block index + voltage above each bar
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("B${i + 1}", left + slotW / 2, chartH - top + dp(26), textPaint)
            canvas.drawText(String.format("%.2f", v), left + slotW / 2, chartH - top + dp(40), textPaint)
        }

        // optional gridline at 14.4 V nominal
        val nominalFrac = (14.4f - vMin) / (vMax - vMin)
        canvas.drawLine(0f, chartH * (1 - nominalFrac), width.toFloat(), chartH * (1 - nominalFrac), gridPaint)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
