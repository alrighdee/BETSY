package org.betsy.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.betsy.debug.CaptureLog
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.poll.Poller
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.betsy.ui.theme.DesignTokens
import org.betsy.ui.theme.Surfaces
import org.betsy.ui.theme.TextStyles
import org.betsy.ui.theme.UnitPrefs
import org.betsy.ui.theme.applyBetsyTheme

/**
 * The one live battery-monitor screen for Gen2/the `7E2` Gen2 layout/Gen3. Runs a fast-mode poll loop on a
 * background thread, renders into programmatically-built TextViews + [BlockBarView].
 */
class BatteryActivity : Activity() {
    /** "Gen 2 · 14 blocks · 28 cells", filled in once detection is known. */
    private val vehicleText: TextView by lazy {
        TextStyles.body(this, "", DesignTokens.TEXT_2, DesignTokens.GRAY_11)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val model = BatteryModel()
    private var running = true

    private lateinit var socText: TextView
    private lateinit var currentText: TextView
    private lateinit var packText: TextView
    private lateinit var diffText: TextView
    private lateinit var auxText: TextView
    private lateinit var chargeText: TextView
    private lateinit var dischargeText: TextView
    private lateinit var tempsText: TextView
    private lateinit var speedText: TextView
    private lateinit var barsView: BlockBarView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBetsyTheme()
        setContentView(buildUi())
        CaptureLog.log("UI", "BatteryActivity created")
        val info = SessionHolder.info()
        // The mockup puts the vehicle summary in the header, dot-separated, not in the body.
        vehicleText.text = "${info.model.label} \u00b7 ${info.blockCount} blocks \u00b7 ${info.cellCount} cells"

        val poller = Poller(SessionHolder.session(), info)
        Thread {
            var consecutiveFailures = 0
            while (running) {
                try {
                    awaitBlocking { poller.poll(model) }
                    consecutiveFailures = 0
                    handler.post { render() }
                } catch (_: NoDataException) {
                    // transient, keep showing last good frame
                } catch (_: TransportException) {
                    if (++consecutiveFailures >= 3) {
                        CaptureLog.log("UI", "adapter disconnected after $consecutiveFailures failures")
                        handler.post {
                            statusText.text = "Adapter disconnected."
                            statusText.setTextColor(Color.RED)
                            statusText.visibility = View.VISIBLE
                        }
                        running = false
                        break
                    }
                } catch (e: Exception) {
                    if (++consecutiveFailures >= 3) {
                        CaptureLog.logThrowable("UI", e)
                        handler.post {
                            statusText.text = "Polling error: ${e.message}"
                            statusText.visibility = View.VISIBLE
                        }
                        running = false
                        break
                    }
                }
                Thread.sleep(500)
            }
        }.start()
    }

    private fun buildUi(): LinearLayout {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(DesignTokens.GRAY_1)
            }
        root.addView(batteryHeader())

        val body =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
            }

        val stats =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
        socText = statColumn(stats, "SOC")
        currentText = statColumn(stats, "CURRENT")
        body.addView(stats)

        val stats2 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        packText = statColumn(stats2, "PACK V")
        diffText = statColumn(stats2, "VOLT DIFF")
        body.addView(stats2)

        val stats3 =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
        auxText = statColumn(stats3, "12V AUX")
        chargeText = statColumn(stats3, "MAX CHG")
        dischargeText = statColumn(stats3, "MAX DIS")
        body.addView(stats3)

        tempsText = sectionLabel("BATT TEMP")
        body.addView(tempsText)

        speedText = sectionLabel("SPEED / RPM")
        body.addView(speedText)

        barsView = BlockBarView(this, model)
        body.addView(
            barsView,
            LinearLayout
                .LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ).apply { topMargin = dp(10) },
        )

        statusText =
            TextView(this).apply {
                text = ""
                textSize = 13f
                setTextColor(Color.GRAY)
                visibility = View.GONE
            }
        body.addView(statusText)

        val dtcLp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            )
        dtcLp.topMargin = dp(8)
        body.addView(
            actionButton("READ DTC / INF CODES") {
                startActivity(Intent(this@BatteryActivity, DtcActivity::class.java))
            },
            dtcLp,
        )
        body.addView(
            actionButton("DEBUG LOG") {
                startActivity(Intent(this@BatteryActivity, DebugLogActivity::class.java))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply {
                topMargin = dp(8)
            },
        )

        root.addView(
            body,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        return root
    }

    /** Battery-screen header: title, vehicle summary and a live pill. */
    private fun batteryHeader(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(DesignTokens.GRAY_2)
            setPadding(dp(18), dp(14), dp(18), dp(14))

            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, 0, dp(12), 0)
                    addView(
                        TextView(context).apply {
                            text = "Battery health"
                            textSize = DesignTokens.TEXT_5
                            setTextColor(DesignTokens.GRAY_12)
                            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        },
                    )
                    addView(vehicleText)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )

            addView(livePill())
        }

    /** Green dot plus "Live", on the tone-matched chip ground. */
    private fun livePill(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Surfaces.rounded(context, DesignTokens.GREEN_BG, DesignTokens.RADIUS_PILL)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            addView(
                android.view.View(context).apply {
                    background = Surfaces.circle(context, DesignTokens.GREEN_SOLID)
                    layoutParams = LinearLayout.LayoutParams(dp(7), dp(7))
                },
            )
            addView(
                TextStyles.body(context, "Live", DesignTokens.TEXT_2, DesignTokens.GREEN_TEXT, bold = true).apply {
                    val lp =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        )
                    lp.leftMargin = dp(7)
                    layoutParams = lp
                },
            )
        }

    private fun statColumn(
        root: LinearLayout,
        label: String,
    ): TextView {
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(2), dp(16), dp(6))
            }
        column.addView(
            TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(Color.GRAY)
            },
        )
        val value =
            TextView(this).apply {
                text = "--"
                textSize = 20f
                setTextColor(Color.WHITE)
            }
        column.addView(value)
        root.addView(column)
        return value
    }

    private fun actionButton(
        label: String,
        onClick: () -> Unit,
    ): TextView =
        TextStyles.body(this, label, DesignTokens.TEXT_3, DesignTokens.GRAY_12, bold = true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            background =
                Surfaces.ripple(
                    context,
                    DesignTokens.GRAY_2,
                    DesignTokens.RADIUS_CARD,
                    DesignTokens.cardBorder,
                    rippleColor = DesignTokens.BRAND_SOLID,
                )
            setOnClickListener { onClick() }
        }

    private fun sectionLabel(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
        }

    private fun render() {
        socText.text = String.format("%.1f %%", model.soc)
        currentText.text = String.format("%.1f A", model.currentAmps)
        packText.text = String.format("%.1f V", model.packVolts())
        diffText.text = String.format("%.3f V", model.voltDiff())
        auxText.text = String.format("%.2f V", model.aux12V)
        chargeText.text = String.format("%.1f HP", model.maxChargeHp)
        dischargeText.text = String.format("%.1f HP", model.maxDischargeHp)
        val tempUnit = if (UnitPrefs.fahrenheit(this)) "F" else "C"
        tempsText.text =
            "BATT TEMP  " +
            model.temps.joinToString("  ") {
                String.format("%.1f%s", UnitPrefs.temperature(this, it), tempUnit)
            }
        val kmh = model.speedMph / 0.625f
        speedText.text =
            "SPEED ${String.format("%.0f %s", UnitPrefs.speed(this, kmh), UnitPrefs.speedUnit(this))}   RPM ${model.rpm}"
        barsView.syncFromModel()
    }

    override fun onDestroy() {
        running = false
        CaptureLog.close()
        SessionHolder.close()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
