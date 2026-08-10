package org.betsy.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles
import org.betsy.ui.theme.ThemeMode
import org.betsy.ui.theme.ThemePrefs
import org.betsy.ui.theme.UnitPrefs
import org.betsy.ui.theme.applyBetsyTheme

/**
 * Settings, from `BETSY.dc.html`: Appearance, Temperature, Keep the screen awake, About.
 *
 * Appearance changes the palette, and views read [DesignTokens] once when they are constructed, so
 * choosing a mode calls [recreate] rather than trying to repaint a live tree. That is the ordinary
 * Android behaviour for a theme switch and keeps every view free of a theme listener.
 */
class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBetsyTheme()
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(DesignTokens.GRAY_1)
            }
        root.addView(header())

        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val p = Surfaces.dp(context, 18)
                setPadding(p, Surfaces.dp(context, 16), p, Surfaces.dp(context, 24))
            }

        column.addView(
            section(
                "Appearance",
                segmented(
                    listOf("Day" to ThemeMode.DAY, "Night" to ThemeMode.NIGHT, "Match phone" to ThemeMode.AUTO),
                    ThemePrefs.mode(this),
                ) { chosen ->
                    if (chosen != ThemePrefs.mode(this)) {
                        ThemePrefs.setMode(this, chosen)
                        recreate()
                    }
                },
            ),
        )

        column.addView(
            section(
                "Temperature",
                segmented(
                    listOf("Celsius" to false, "Fahrenheit" to true),
                    UnitPrefs.fahrenheit(this),
                ) { f -> UnitPrefs.setFahrenheit(this, f) },
            ),
        )

        column.addView(
            section(
                "Keep the screen awake",
                toggleRow("While a scan is running", UnitPrefs.keepAwake(this)) { on ->
                    UnitPrefs.setKeepAwake(this, on)
                },
            ),
        )

        column.addView(section("About Betsy", aboutCard()))

        val scroll = ScrollView(this).apply { addView(column) }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    private fun header(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(DesignTokens.GRAY_2)
            val p = Surfaces.dp(context, 18)
            setPadding(p, Surfaces.dp(context, 20), p, Surfaces.dp(context, 18))
            addView(
                TextView(context).apply {
                    text = "Settings"
                    textSize = DesignTokens.TEXT_5
                    setTextColor(DesignTokens.GRAY_12)
                    setTypeface(Typeface.DEFAULT_BOLD)
                },
            )
        }

    /** A titled group: 15sp bold head, then the control card. */
    private fun section(
        title: String,
        content: View,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextStyles.sectionLabel(context, title).apply {
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.topMargin = Surfaces.dp(context, 20)
                    lp.bottomMargin = Surfaces.dp(context, 10)
                    layoutParams = lp
                },
            )
            addView(content)
        }

    /** Pill segmented control, the mockup's Day / Night / Match phone selector. */
    private fun <T> segmented(
        options: List<Pair<String, T>>,
        selected: T,
        onPick: (T) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background =
                Surfaces.rounded(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_PILL, DesignTokens.cardBorder)
            val pad = Surfaces.dp(context, 5)
            setPadding(pad, pad, pad, pad)
            options.forEach { (label, value) ->
                val on = value == selected
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = DesignTokens.TEXT_2
                        gravity = Gravity.CENTER
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(if (on) DesignTokens.GRAY_12 else DesignTokens.GRAY_11)
                        if (on) {
                            background =
                                Surfaces.rounded(context, DesignTokens.BRAND_TINT, DesignTokens.RADIUS_PILL)
                        }
                        val v = Surfaces.dp(context, 9)
                        setPadding(0, v, 0, v)
                        setOnClickListener { onPick(value) }
                        layoutParams =
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    },
                )
            }
        }

    /** A card with a label and a right-aligned on/off state. */
    private fun toggleRow(
        label: String,
        initial: Boolean,
        onChange: (Boolean) -> Unit,
    ): View {
        var on = initial
        val state =
            TextStyles.body(this, if (on) "On" else "Off", DesignTokens.TEXT_2, DesignTokens.BRAND_SOLID, bold = true)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background =
                Surfaces.rounded(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
            val p = Surfaces.dp(context, 16)
            setPadding(p, p, p, p)
            addView(
                TextStyles.body(context, label, DesignTokens.TEXT_3, DesignTokens.GRAY_12).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(state)
            setOnClickListener {
                on = !on
                state.text = if (on) "On" else "Off"
                onChange(on)
            }
        }
    }

    /**
     * Reads the real version rather than carrying a literal. The mockup shows "Version 2.4", which
     * is placeholder copy, printing it in a shipped build would state a version that does not
     * exist. Taking it from the package means it can never drift from the manifest.
     */
    private fun versionLine(): String {
        val name =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                null
            }
        return "Version ${name ?: "?"} \u00b7 MIT license"
    }

    private fun aboutCard(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background =
                Surfaces.rounded(context, DesignTokens.GRAY_3, DesignTokens.RADIUS_FIELD, DesignTokens.cardBorder)
            val p = Surfaces.dp(context, 16)
            setPadding(p, p, p, p)
            addView(TextStyles.body(context, "Battery, Engine & Toyota Scanner for You", DesignTokens.TEXT_2))
            addView(
                TextStyles.figures(context, versionLine(), DesignTokens.TEXT_2, DesignTokens.GRAY_10).apply {
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.topMargin = Surfaces.dp(context, 6)
                    layoutParams = lp
                },
            )
        }
}
