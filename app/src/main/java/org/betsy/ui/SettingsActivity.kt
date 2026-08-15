package org.betsy.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.betsy.BuildConfig
import org.betsy.capture.CaptureConsent
import org.betsy.capture.CaptureData
import org.betsy.capture.CaptureUploader
import org.betsy.capture.PendingCapture
import org.betsy.capture.UploadResult
import org.betsy.debug.DemoFixtures
import org.betsy.debug.DemoMode
import org.betsy.debug.DemoScenario
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
    private val handler = Handler(Looper.getMainLooper())

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

        if (BuildConfig.DEBUG) {
            column.addView(section("Developer", developerCard()))
        }

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
                        background =
                            Surfaces.ripple(
                                context,
                                if (on) DesignTokens.BRAND_TINT else Color.TRANSPARENT,
                                DesignTokens.RADIUS_PILL,
                            )
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
                Surfaces.ripple(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
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
     * Real version + burned git identity rather than a mockup literal. The hero shows the same
     * build label; Settings spells it out with the license so both places stay honest about what
     * is installed.
     */
    private fun versionLine(): String {
        val name =
            try {
                packageManager.getPackageInfo(packageName, 0).versionName
            } catch (_: Exception) {
                null
            }
        return "Version ${name ?: "?"} · ${BuildConfig.GIT_HASH} · ${BuildConfig.BUILD_TIME} · MIT license"
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

    // ── Developer (debug builds only) ──

    /**
     * Opens every share surface without a session, on fixture [CaptureData]. Each action builds the
     * fixture by running detection and a sweep over a zero-delay replay, so the dialogs show the
     * production render of real script bytes rather than hand-written placeholders.
     */
    private fun developerCard(): View {
        val gap = Surfaces.dp(this, 10)
        val actions =
            listOf(
                "Reset disclosure" to { CaptureConsent.clear(this) },
                "Open disclosure" to { CaptureDisclosureDialog(this) {}.show() },
                "Share sheet · no codes" to { showSharePreview(DemoScenario.GEN2_HEALTHY) },
                "Share sheet · decoder miss" to { showSharePreview(DemoScenario.DECODER_MISS) },
                "Share sheet · decoded" to { showSharePreview(DemoScenario.GEN2_STORED_FAULT) },
                "Open pending-retry dialog" to { showPendingPreview() },
                "Simulate send success" to { simulateSend(DemoScenario.GEN2_STORED_FAULT) },
                "Simulate send failure" to { simulateSend(DemoScenario.SHARE_FAIL) },
            )
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            actions.forEachIndexed { index, (label, onClick) ->
                val lp =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                if (index > 0) lp.topMargin = gap
                addView(devRow(label, onClick), lp)
            }
        }
    }

    private fun devRow(
        label: String,
        onClick: () -> Unit,
    ): View =
        TextStyles.body(this, label, DesignTokens.TEXT_2, DesignTokens.GRAY_12).apply {
            background =
                Surfaces.ripple(context, DesignTokens.GRAY_2, DesignTokens.RADIUS_CARD, DesignTokens.cardBorder)
            val p = Surfaces.dp(context, 16)
            setPadding(p, p, p, p)
            setOnClickListener { onClick() }
        }

    private fun showSharePreview(scenario: DemoScenario) {
        Thread {
            val data = DemoFixtures.capture(scenario)
            handler.post {
                var dialog: CaptureShareDialog? = null
                dialog =
                    CaptureShareDialog(this, data) { notes ->
                        dialog?.let { submitPreview(it, data.copy(ownerNotes = notes), scenario) }
                    }
                dialog.show()
            }
        }.start()
    }

    /** Runs a preview submit through the demo stub so the sending/error surfaces render locally. */
    private fun submitPreview(
        dialog: CaptureShareDialog,
        data: CaptureData,
        scenario: DemoScenario,
    ) {
        dialog.setBusy(true)
        DemoMode.activate(scenario)
        Thread {
            val outcome = CaptureUploader.submit(data)
            handler.post {
                DemoMode.deactivate()
                when (outcome) {
                    is UploadResult.Ok -> {
                        dialog.dismiss()
                        Toast.makeText(this, "Demo: send succeeded.", Toast.LENGTH_LONG).show()
                    }
                    is UploadResult.Failed -> dialog.showError(outcome.reason)
                }
            }
        }.start()
    }

    private fun showPendingPreview() {
        Thread {
            val data = DemoFixtures.capture(DemoScenario.GEN2_STORED_FAULT)
            PendingCapture.save(this, data.toJson())
            handler.post {
                // Demo stays active for the life of the dialog so SEND NOW short-circuits; a
                // fixture must never be POSTed by a preview.
                DemoMode.activate(DemoScenario.GEN2_STORED_FAULT)
                PendingCaptureDialog(this, onDismiss = { DemoMode.deactivate() }).show()
            }
        }.start()
    }

    private fun simulateSend(scenario: DemoScenario) {
        Thread {
            val data = DemoFixtures.capture(scenario)
            DemoMode.activate(scenario)
            val outcome = CaptureUploader.submit(data)
            DemoMode.deactivate()
            handler.post {
                val message =
                    when (outcome) {
                        is UploadResult.Ok -> "Demo: send succeeded."
                        is UploadResult.Failed -> "Demo: send failed — ${outcome.reason}"
                    }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.start()
    }
}
