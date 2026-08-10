package org.betsy.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/**
 * TextView factories for the B.E.T.S.Y. redesign.
 *
 * **No monospace anywhere**, per the mockup. The old revision set MAC addresses, host:port and step
 * timings in `Typeface.MONOSPACE`, which read as a terminal. Those now use the same sans as
 * everything else with **tabular figures** (`tnum`), so columns of numbers still line up without the
 * typeface shouting about it. [figures] replaces the old `mono` for exactly that reason.
 *
 * Section heads are sentence-case and bold rather than uppercase and letter-spaced, "How should I
 * reach your car?" rather than "TRANSPORT".
 */
object TextStyles {
    /** Section head above a group: 15sp bold ink, sentence case. */
    fun sectionLabel(
        context: Context,
        text: String,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = DesignTokens.TEXT_3
            setTextColor(DesignTokens.GRAY_12)
            setTypeface(Typeface.DEFAULT_BOLD)
        }

    /**
     * Numeric metadata, MAC addresses, host:port, voltages, timings. Sans with tabular figures so
     * digits align in a column; `tnum` is honoured by Roboto on API 21+.
     */
    fun figures(
        context: Context,
        text: String,
        sizeSp: Float = DesignTokens.TEXT_2,
        color: Int = DesignTokens.GRAY_11,
        bold: Boolean = false,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            fontFeatureSettings = "tnum"
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
        }

    fun body(
        context: Context,
        text: String,
        sizeSp: Float = DesignTokens.TEXT_2,
        color: Int = DesignTokens.GRAY_11,
        bold: Boolean = false,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(Typeface.DEFAULT_BOLD)
            setLineSpacing(0f, 1.4f)
        }

    /** A big readout, the SOC and current figures on the battery screen. */
    fun hero(
        context: Context,
        text: String,
        sizeSp: Float = DesignTokens.TEXT_HERO,
        color: Int = DesignTokens.GRAY_12,
    ): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            setTypeface(Typeface.DEFAULT_BOLD)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
        }
}
