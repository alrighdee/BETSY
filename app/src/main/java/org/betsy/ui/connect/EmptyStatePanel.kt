package org.betsy.ui.connect

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles

/**
 * The Bluetooth empty state. The old screen put "(no paired devices)" in a spinner and left the user
 * stuck; this one explains why the list is empty and offers the two things that actually resolve it,
 * pair in Android settings, or switch to a Wi-Fi adapter.
 */
class EmptyStatePanel(
    context: Context,
    onOpenBluetoothSettings: () -> Unit,
    onUseWifi: () -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL

        val card =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val h = Surfaces.dp(context, 18f)
                setPadding(h, Surfaces.dp(context, 22f), h, Surfaces.dp(context, 22f))
                background =
                    Surfaces.rounded(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_4, DesignTokens.ghostBorder, 1f)
                addView(
                    TextStyles
                        .body(context, "I can't see any adapters yet", DesignTokens.TEXT_3, DesignTokens.GRAY_12, bold = true)
                        .apply { gravity = Gravity.CENTER },
                )
                addView(
                    TextStyles
                        .body(
                            context,
                            "Bluetooth dongles have to be paired once in Android settings before any app " +
                                "before any app can see them.",
                        ).apply { gravity = Gravity.CENTER },
                    LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 6f) },
                )
            }
        addView(card, LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val primary =
            TextStyles.body(context, "Open Bluetooth settings", 17f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background =
                    Surfaces.ripple(
                        context,
                        DesignTokens.BRAND_SOLID,
                        DesignTokens.RADIUS_CARD,
                        DesignTokens.BRAND_SOLID,
                    )
                setOnClickListener { onOpenBluetoothSettings() }
            }
        Surfaces.raise(primary, 8f)
        addView(
            primary,
            LayoutParams(MATCH_PARENT, Surfaces.dp(context, 56f)).apply {
                topMargin = Surfaces.dp(context, 14f)
            },
        )

        val secondary =
            TextStyles.body(context, "Use a Wi-Fi adapter instead", DesignTokens.TEXT_2, DesignTokens.GRAY_11).apply {
                gravity = Gravity.CENTER
                background =
                    Surfaces.ripple(context, Color.TRANSPARENT, DesignTokens.RADIUS_3, DesignTokens.ghostBorder, 1f)
                setOnClickListener { onUseWifi() }
            }
        addView(
            secondary,
            LayoutParams(MATCH_PARENT, Surfaces.dp(context, 48f)).apply {
                topMargin = Surfaces.dp(context, 10f)
            },
        )

        val hint =
            TextStyles.body(context, "", 11f, DesignTokens.GRAY_11).apply {
                text = pairingHint()
                val h = Surfaces.dp(context, 15f)
                setPadding(h, Surfaces.dp(context, 13f), h, Surfaces.dp(context, 13f))
                background =
                    Surfaces.rounded(context, DesignTokens.subtleFill, DesignTokens.RADIUS_3, DesignTokens.ghostBorder, 1f)
            }
        addView(
            hint,
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = Surfaces.dp(context, 14f) },
        )
    }

    /** The two PINs cheap ELM327 dongles actually ship with. */
    private fun pairingHint(): CharSequence {
        val builder = SpannableStringBuilder("Pairing code is usually ")
        appendCode(builder, "1234")
        builder.append(" or ")
        appendCode(builder, "0000")
        builder.append(". Clones below ELM327 v1.5 cannot read Toyota battery blocks.")
        return builder
    }

    private fun appendCode(
        builder: SpannableStringBuilder,
        code: String,
    ) {
        val start = builder.length
        builder.append(code)
        builder.setSpan(TypefaceSpan("monospace"), start, builder.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(
            ForegroundColorSpan(DesignTokens.GRAY_12),
            start,
            builder.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
}
