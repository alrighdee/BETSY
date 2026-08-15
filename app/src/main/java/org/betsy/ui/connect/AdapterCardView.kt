package org.betsy.ui.connect

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * One adapter card. Replaces the old spinner row: a raised surface carrying the name, MAC, firmware
 * grade and selection state, so a genuine ELM327 can be told from a clone before connecting.
 */
class AdapterCardView(
    context: Context,
) : LinearLayout(context) {
    private val badge: TextView
    private val name: TextView
    private val lastUsedTag: TextView
    private val address: TextView
    private val firmwareChip: TextView
    private val check: TextView

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = Surfaces.dp(context, 15f)
        setPadding(pad, Surfaces.dp(context, 14f), pad, Surfaces.dp(context, 14f))

        badge =
            TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
            }
        addView(
            badge,
            LayoutParams(Surfaces.dp(context, 38f), Surfaces.dp(context, 38f)).apply {
                rightMargin = Surfaces.dp(context, 13f)
            },
        )

        val column = LinearLayout(context).apply { orientation = VERTICAL }
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        val titleRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
        name = TextStyles.body(context, "", DesignTokens.TEXT_3, DesignTokens.GRAY_12, bold = true)
        titleRow.addView(name)
        lastUsedTag =
            TextView(context).apply {
                text = "LAST USED"
                textSize = 10f
                setTextColor(DesignTokens.BRAND_SOLID)
                setTypeface(Typeface.DEFAULT_BOLD)
                letterSpacing = 0.04f
                background =
                    Surfaces.rounded(context, DesignTokens.chipFill(DesignTokens.BRAND_SOLID), DesignTokens.RADIUS_2)
                val h = Surfaces.dp(context, 6f)
                setPadding(h, Surfaces.dp(context, 2f), h, Surfaces.dp(context, 2f))
            }
        titleRow.addView(
            lastUsedTag,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = Surfaces.dp(context, 7f)
            },
        )
        column.addView(titleRow)

        address = TextStyles.figures(context, "", 11f, DesignTokens.GRAY_10)
        column.addView(
            address,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = Surfaces.dp(context, 3f)
            },
        )

        firmwareChip =
            TextStyles.figures(context, "", 10f, DesignTokens.GRAY_11).apply {
                val h = Surfaces.dp(context, 7f)
                setPadding(h, Surfaces.dp(context, 2f), h, Surfaces.dp(context, 2f))
            }
        column.addView(
            firmwareChip,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = Surfaces.dp(context, 6f)
            },
        )

        check =
            TextView(context).apply {
                text = "✓"
                textSize = 12f
                gravity = Gravity.CENTER
            }
        addView(check, LayoutParams(Surfaces.dp(context, 22f), Surfaces.dp(context, 22f)))
    }

    fun bind(
        candidate: AdapterCandidate,
        selected: Boolean,
        wifi: Boolean,
    ) {
        name.text = candidate.name
        address.text = candidate.address
        lastUsedTag.visibility = if (candidate.lastUsed) VISIBLE else GONE

        badge.text = glyph(wifi)
        badge.setTextColor(if (selected) DesignTokens.BRAND_SOLID else DesignTokens.GRAY_11)
        badge.background =
            Surfaces.rounded(
                context,
                if (selected) DesignTokens.badgeFillSelected else DesignTokens.badgeFill,
                DesignTokens.RADIUS_3,
            )

        // A banner is only known after a successful connect, so on a first run every card would
        // carry an identical "Firmware unknown" chip: a row of noise that distinguishes nothing.
        // Shown only when there is something to say. An unparseable banner still counts as
        // something, so this keys on the banner's absence rather than on the grading.
        val hasFirmware = !candidate.firmware.isNullOrBlank()
        firmwareChip.visibility = if (hasFirmware) VISIBLE else GONE
        if (hasFirmware) {
            val tone = toneColor(candidate.firmwareTone)
            firmwareChip.text = candidate.firmwareLabel
            firmwareChip.setTextColor(tone)
            firmwareChip.background = Surfaces.rounded(context, DesignTokens.chipFill(tone), DesignTokens.RADIUS_2)
        }

        check.setTextColor(if (selected) Color.WHITE else Color.TRANSPARENT)
        check.background =
            if (selected) {
                Surfaces.circle(context, DesignTokens.BRAND_SOLID)
            } else {
                Surfaces.circle(context, Color.TRANSPARENT, DesignTokens.cardBorder)
            }

        background =
            Surfaces.ripple(
                context,
                if (selected) DesignTokens.cardFillSelected else DesignTokens.cardFill,
                DesignTokens.RADIUS_4,
                if (selected) DesignTokens.BRAND_SOLID else DesignTokens.cardBorder,
            )
        Surfaces.raise(this, if (selected) 6f else 3f)
    }

    private fun toneColor(tone: FirmwareTone): Int =
        when (tone) {
            FirmwareTone.GOOD -> DesignTokens.GREEN_TEXT
            FirmwareTone.WEAK -> DesignTokens.AMBER_TEXT
            FirmwareTone.UNKNOWN -> DesignTokens.GRAY_11
        }

    /**
     * The mockup badges Bluetooth with ⬡ and Wi-Fi with ◉. Geometric shapes are not guaranteed to be
     * in the system font, so fall back to shapes that are rather than risk a tofu box.
     */
    private fun glyph(wifi: Boolean): String {
        val preferred = if (wifi) "◉" else "⬡"
        val fallback = if (wifi) "●" else "◇"
        return if (badge.paint.hasGlyph(preferred)) preferred else fallback
    }
}
