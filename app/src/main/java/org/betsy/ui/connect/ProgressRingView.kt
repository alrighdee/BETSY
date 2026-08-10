package org.betsy.ui.connect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces

/**
 * The connecting panel's progress ring. The mockup draws it as a `conic-gradient` masked to a 3 px
 * annulus; here it is the same annulus as a stroked arc sweeping from twelve o'clock.
 */
class ProgressRingView(
    context: Context,
) : View(context) {
    private val strokeWidth = Surfaces.dp(context, 3f).toFloat()
    private val bounds = RectF()

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = this@ProgressRingView.strokeWidth
            color = DesignTokens.GRAY_5
        }

    private val progressPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = this@ProgressRingView.strokeWidth
            strokeCap = Paint.Cap.ROUND
            color = DesignTokens.BRAND_SOLID
        }

    var percent: Int = 0
        set(value) {
            field = value.coerceIn(0, 100)
            invalidate()
        }

    /** Turns the ring red so a failed attempt reads as failed at a glance, not merely stalled. */
    var failed: Boolean = false
        set(value) {
            field = value
            progressPaint.color = if (value) DesignTokens.RED_TEXT else DesignTokens.BRAND_SOLID
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val inset = strokeWidth / 2f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(bounds, 0f, 360f, false, trackPaint)
        if (percent > 0) {
            canvas.drawArc(bounds, START_ANGLE, 360f * percent / 100f, false, progressPaint)
        }
    }

    private companion object {
        /** Twelve o'clock, matching the mockup's conic gradient origin. */
        const val START_ANGLE = -90f
    }
}
