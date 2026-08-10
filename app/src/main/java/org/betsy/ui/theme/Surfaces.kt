package org.betsy.ui.theme

import android.content.Context
import android.graphics.drawable.GradientDrawable
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
