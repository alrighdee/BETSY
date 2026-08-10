package org.betsy.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.betsy.debug.CaptureLog
import org.betsy.ui.theme.applyBetsyTheme

/**
 * Live tail of the car-test capture (M2). Every ELM327 command, response, decode summary,
 * and failure is written to a file on the app's external storage; this screen shows the
 * most recent lines so a phone test run in the car can be sanity-checked on the spot.
 *
 * Retrieval after the car run (phone plugged into this laptop, USB debugging on):
 *
 *     adb pull /sdcard/Android/data/org.betsy/files/captures/<session>.log
 */
class DebugLogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var bodyText: TextView
    private lateinit var pathText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyBetsyTheme()
        setContentView(buildUi())
        render()
    }

    private fun buildUi(): ScrollView {
        val root =
            ScrollView(this).apply {
                isFillViewport = true
            }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(24), dp(16), dp(16))
            }

        column.addView(
            TextView(this).apply {
                text = "CAPTURE LOG"
                textSize = 18f
                setTextColor(Color.WHITE)
            },
        )

        val refresh =
            Button(this).apply {
                text = "REFRESH"
                setOnClickListener { render() }
            }
        val refreshLp =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        refreshLp.topMargin = dp(8)
        column.addView(refresh, refreshLp)

        pathText =
            TextView(this).apply {
                text = ""
                textSize = 12f
                setTextColor(Color.GRAY)
                setPadding(0, dp(12), 0, 0)
            }
        column.addView(pathText)

        bodyText =
            TextView(this).apply {
                text = ""
                textSize = 11f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(8), 0, 0)
            }
        column.addView(bodyText)

        root.addView(column)
        return root
    }

    private fun render() {
        val file = CaptureLog.captureFile
        pathText.text =
            if (file == null) {
                "No capture file yet, connect and start a session."
            } else {
                "adb pull /sdcard/Android/data/org.betsy/files/captures/${file.name}"
            }
        val lines = CaptureLog.tail(400)
        bodyText.text = if (lines.isEmpty()) "(capture empty)" else lines.joinToString("\n")
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
