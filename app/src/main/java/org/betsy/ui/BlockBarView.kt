package org.betsy.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import org.betsy.model.BatteryModel
import org.betsy.ui.theme.DesignTokens

/**
 * Per-block voltage bar chart, coloured by internal resistance (§4.1 bands):
 * IR raw ≥ 30 mΩ turns the bar yellow (PROTOCOL.md §4.1 bands).
 *
 * Bars grow up from the floor, scaled to the current min..max so a few
 * hundredths of a volt still read. Only the lowest and highest blocks are
 * labelled. The live bar attacks faster than it falls.
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
    private val indexPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.GRAY
            textSize = 10f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.CENTER
        }
    private val waitingPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DesignTokens.GRAY_11
            textSize = 14f * resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.CENTER
        }
    private val waitTrackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            color = DesignTokens.GRAY_5
        }
    private val waitSweepPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            color = DesignTokens.BRAND_SOLID
        }
    private val waitRing = RectF()
    private var displayed = FloatArray(0)
    private var target = FloatArray(0)
    private var pulse: ValueAnimator? = null
    private var sweepDeg = 0f
    private var ticking = false

    private val tick =
        object : Runnable {
            override fun run() {
                if (!isAttachedToWindow) {
                    ticking = false
                    return
                }
                if (displayed.isNotEmpty()) stepMeter()
                postOnAnimation(this)
            }
        }

    /** Latest poll becomes the meter target. */
    fun syncFromModel() {
        val blocks = model.blockVolts
        if (blocks.isEmpty()) return
        if (displayed.size != blocks.size) {
            displayed = blocks.toFloatArray()
            target = displayed.copyOf()
            stopWait()
            startTick()
            invalidate()
            return
        }
        target = blocks.toFloatArray()
        startTick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (displayed.isEmpty()) startWait() else startTick()
    }

    override fun onDetachedFromWindow() {
        ticking = false
        removeCallbacks(tick)
        stopWait()
        super.onDetachedFromWindow()
    }

    private fun startTick() {
        if (ticking) return
        ticking = true
        postOnAnimation(tick)
    }

    private fun stepMeter() {
        for (i in displayed.indices) {
            val d = target[i] - displayed[i]
            displayed[i] += d * if (d >= 0f) ATTACK else RELEASE
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val blocks = if (displayed.isNotEmpty()) displayed else model.blockVolts.toFloatArray()
        if (blocks.isEmpty()) {
            drawWaiting(canvas)
            return
        }

        val ir = model.internalResistance
        val slotW = width / blocks.size.toFloat()
        val topPad = dp(18).toFloat()
        val bottomPad = dp(18).toFloat()
        val floor = height - bottomPad
        val maxBarH = (floor - topPad).coerceAtLeast(1f)
        val dataMin = blocks.min()
        val dataMax = blocks.max()
        val spread = dataMax - dataMin
        val pad = maxOf(0.008f, spread * 0.06f)
        val vMin = dataMin - pad
        val span = (dataMax + pad - vMin).coerceAtLeast(0.001f)
        val minI = blocks.indices.minBy { blocks[it] }
        val maxI = blocks.indices.maxBy { blocks[it] }

        for (i in blocks.indices) {
            val v = blocks[i]
            val frac = ((v - vMin) / span).coerceIn(0f, 1f)
            val resistance = ir.getOrElse(i) { 0 }
            barPaint.color =
                when {
                    resistance >= 30 -> Color.rgb(255, 200, 0)
                    else -> Color.rgb(46, 190, 90)
                }
            val left = i * slotW
            val top = floor - maxBarH * frac
            canvas.drawRect(left + dp(2), top, left + slotW - dp(2), floor, barPaint)

            val cx = left + slotW / 2
            val outlier = i == minI || i == maxI
            if (outlier) {
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(String.format("%.2f", v), cx, top - dp(4), textPaint)
            }
            indexPaint.color = if (outlier) Color.WHITE else Color.GRAY
            canvas.drawText("B${i + 1}", cx, height - dp(4).toFloat(), indexPaint)
        }
    }

    private fun drawWaiting(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - dp(12)
        val r = dp(16).toFloat()
        waitRing.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(waitRing, 0f, 360f, false, waitTrackPaint)
        canvas.drawArc(waitRing, sweepDeg, 84f, false, waitSweepPaint)
        canvas.drawText("Reading voltages…", cx, cy + r + dp(22), waitingPaint)
    }

    private fun startWait() {
        if (pulse?.isRunning == true) return
        pulse =
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    sweepDeg = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
    }

    private fun stopWait() {
        pulse?.cancel()
        pulse = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val ATTACK = 0.42f
        const val RELEASE = 0.14f
    }
}
