package org.betsy.ui.connect

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import org.betsy.BuildConfig
import org.betsy.R
import org.betsy.ui.theme.DesignTokens

/**
 * Full-bleed B.E.T.S.Y. header banner, in the artwork matching the active palette.
 *
 * **No fade mask, deliberately.** The original mockup artwork was a neon wordmark on a black
 * backdrop, so it could be dissolved into the near-black page ([ui.theme.DesignTokens.GRAY_1],
 * `#121113`) with a vertical alpha gradient and read as part of the page rather than a card.
 *
 * The BETSY banner is the opposite: light throughout, and its lower half carries actual subject
 * matter, the OBD dongle and the car's wheels sit roughly 60–90% down. The old gradient started
 * fading at 46% and reached transparent at the bottom edge, which would have erased them. A light
 * banner meeting a dark UI at a hard edge is the correct treatment here; a gradient would just look
 * like a rendering fault.
 *
 * Height is still derived from the artwork's own aspect ratio rather than fixed, so replacing the
 * asset cannot silently letterbox or crop it.
 *
 * A small build label (`version · git · date time`) is drawn bottom-right so an installed APK can
 * be matched to a tree and a build moment without digging through Settings. It stays out of the
 * wordmark area.
 */
class LogoHeaderView(
    context: Context,
) : View(context) {
    private val source: Bitmap? =
        BitmapFactory.decodeResource(
            resources,
            // Two artworks, one per palette: the day banner is pale blue, the night one near-black
            // with a glowing wordmark. Picking by palette rather than by system night mode keeps it
            // correct when the user has forced Day or Night in Settings.
            if (DesignTokens.palette.isDark) R.drawable.logo_header_night else R.drawable.logo_header_day,
        )
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val labelOutlinePaint =
        labelPaint(Color.BLACK).apply {
            style = Paint.Style.STROKE
            strokeWidth =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP,
                    1.2f,
                    resources.displayMetrics,
                )
        }
    private val labelPaint = labelPaint(Color.WHITE)
    private val buildLabel: String = BuildConfig.BUILD_LABEL

    /** Shared setup for the two label passes (outline + fill), so the two never drift apart. */
    private fun labelPaint(color: Int): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.RIGHT
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    10f,
                    resources.displayMetrics,
                )
        }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val art = source
        val height = if (art != null && art.width > 0) width * art.height / art.width else width / 3
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val art = source ?: return
        canvas.drawBitmap(art, null, Rect(0, 0, width, height), paint)
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, resources.displayMetrics)
        // Baseline near the bottom edge; monospace keeps hash columns stable across builds.
        // Outline drawn under the fill so the white text keeps a black edge on either artwork.
        val x = width - pad
        val y = height - pad
        canvas.drawText(buildLabel, x, y, labelOutlinePaint)
        canvas.drawText(buildLabel, x, y, labelPaint)
    }
}
