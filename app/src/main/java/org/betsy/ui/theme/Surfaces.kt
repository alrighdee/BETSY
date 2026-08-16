package org.betsy.ui.theme

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View

/**
 * Background builders for the connect-screen redesign. The mockup styles every surface as a rounded
 * rect with an optional 1.5 px accent border, so one builder covers cards, fields, chips, menus and
 * buttons instead of each view rolling its own [GradientDrawable].
 */
object Surfaces {
    /** Rounded-rect fill with an optional border, both taken straight from [DesignTokens]. */
    fun rounded(
        context: Context,
        fill: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 1.5f,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = dp(context, radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(context, strokeDp), strokeColor)
        }

    /**
     * [rounded] wrapped in a ripple, for anything pressable. A bare [rounded] background is a
     * static drawable with no pressed state, so a tap gives no feedback; this masks the ripple to
     * the rounded rect and lets it tint the surface while the finger is down. The mask is opaque
     * regardless of [fill], so even a transparent option row still shows a clipped ripple.
     */
    fun ripple(
        context: Context,
        fill: Int,
        radiusDp: Float,
        strokeColor: Int? = null,
        strokeDp: Float = 1.5f,
        rippleColor: Int? = null,
    ): RippleDrawable {
        val content = rounded(context, fill, radiusDp, strokeColor, strokeDp)
        val mask = rounded(context, Color.WHITE, radiusDp)
        val tint =
            rippleColor
                ?: if (DesignTokens.palette.isDark) Color.argb(51, 255, 255, 255) else Color.argb(36, 13, 27, 62)
        return RippleDrawable(ColorStateList.valueOf(tint), content, mask)
    }

    /** Circle fill, used by the step dots and the selected-adapter check. */
    fun circle(
        context: Context,
        fill: Int,
        strokeColor: Int? = null,
        strokeDp: Float = 1.5f,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fill)
            if (strokeColor != null) setStroke(dp(context, strokeDp), strokeColor)
        }

    /**
     * Applies the mockup's violet glow as elevation. Android cannot render an arbitrary coloured
     * box-shadow on a drawable, so raised surfaces use elevation for depth and keep the violet
     * border as the visual signature.
     */
    fun raise(
        view: View,
        elevationDp: Float,
    ) {
        view.elevation = dp(view.context, elevationDp).toFloat()
    }

    fun dp(
        context: Context,
        value: Float,
    ): Int = (value * context.resources.displayMetrics.density).toInt()

    fun dp(
        context: Context,
        value: Int,
    ): Int = dp(context, value.toFloat())
}
