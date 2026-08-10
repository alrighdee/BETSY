package org.betsy.ui.connect

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/** Which link the adapter is reached over (PROTOCOL.md §1). */
enum class Transport {
    BLUETOOTH,
    WIFI,
}

/**
 * The transport control. The mockup keeps it a dropdown but styles it as a card with a rotating
 * caret and overlays the options rather than pushing the adapter list down, so this uses a
 * [PopupWindow] anchored to the box instead of an inline expander.
 */
class TransportSelect(
    context: Context,
    private val onChange: (Transport) -> Unit,
) : LinearLayout(context) {
    private val label: TextView
    private val caret: TextView
    private var popup: PopupWindow? = null
    private var transport: Transport = Transport.BLUETOOTH

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(Surfaces.dp(context, 15f), 0, Surfaces.dp(context, 8f), 0)

        label = TextStyles.body(context, "Bluetooth", DesignTokens.TEXT_3, DesignTokens.GRAY_12)
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        caret =
            TextView(context).apply {
                text = "▾"
                // A thin glyph at 13sp on the brand tint was brand-blue on brand-blue, and read
                // as an artefact rather than a control. Larger, bolder, and on the neutral badge
                // fill so the accent colour has something to contrast against.
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(DesignTokens.BRAND_SOLID)
                background = Surfaces.rounded(context, DesignTokens.badgeFill, 9f)
            }
        addView(caret, LayoutParams(Surfaces.dp(context, 34f), Surfaces.dp(context, 34f)))

        setOnClickListener { toggle() }
        applyClosedStyle()
    }

    fun current(): Transport = transport

    fun select(value: Transport) {
        transport = value
        label.text = if (value == Transport.WIFI) "Wi-Fi" else "Bluetooth"
    }

    private fun toggle() {
        if (popup?.isShowing == true) {
            popup?.dismiss()
            return
        }
        val menu =
            LinearLayout(context).apply {
                orientation = VERTICAL
                val pad = Surfaces.dp(context, 5f)
                setPadding(pad, pad, pad, pad)
                background =
                    Surfaces.rounded(context, DesignTokens.menuFill, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
                addView(option("Bluetooth", Transport.BLUETOOTH))
                addView(option("Wi-Fi", Transport.WIFI))
            }
        popup =
            PopupWindow(menu, width, LayoutParams.WRAP_CONTENT, true).apply {
                elevation = Surfaces.dp(context, 14f).toFloat()
                setOnDismissListener { applyClosedStyle() }
                showAsDropDown(this@TransportSelect, 0, Surfaces.dp(context, 6f))
            }
        applyOpenStyle()
    }

    private fun option(
        text: String,
        value: Transport,
    ): View {
        val selected = transport == value
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Surfaces.dp(context, 44f)
            setPadding(Surfaces.dp(context, 12f), 0, Surfaces.dp(context, 12f), 0)
            background =
                Surfaces.rounded(
                    context,
                    if (selected) DesignTokens.optionSelected else Color.TRANSPARENT,
                    9f,
                )
            addView(
                TextStyles.body(
                    context,
                    text,
                    DesignTokens.TEXT_3,
                    if (selected) Color.WHITE else DesignTokens.GRAY_11,
                ),
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                TextView(context).apply {
                    this.text = "✓"
                    textSize = 13f
                    setTextColor(if (selected) DesignTokens.BRAND_SOLID else Color.TRANSPARENT)
                },
            )
            setOnClickListener {
                popup?.dismiss()
                if (!selected) {
                    select(value)
                    onChange(value)
                }
            }
        }
    }

    private fun applyOpenStyle() {
        background =
            Surfaces.rounded(context, DesignTokens.cardFill, DesignTokens.RADIUS_CARD, DesignTokens.BRAND_SOLID)
        caret.rotation = 180f
    }

    private fun applyClosedStyle() {
        background =
            Surfaces.rounded(context, DesignTokens.cardFill, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
        caret.rotation = 0f
    }
}
