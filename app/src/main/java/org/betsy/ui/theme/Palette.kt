package org.betsy.ui.theme

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * The two palettes from `BETSY.dc.html`. Day and Night swap these ~20 values and nothing else,
 * no layout, type ramp, radius or copy differs between them, which is why the whole theme lives in
 * one object and every view reads tokens rather than literals.
 *
 * Names follow the mockup's CSS custom properties (`--b-screen`, `--b-ink`, …) so a value can be
 * traced back to the design without a lookup table.
 */
data class Palette(
    /** `--b-screen`, the app background. */
    val screen: Int,
    /** `--b-panel`, raised card, field, menu and secondary-button fill. */
    val panel: Int,
    /** `--b-ink`, primary text. */
    val ink: Int,
    /** `--b-muted`, supporting copy. */
    val muted: Int,
    /** `--b-dim`, block numbers, timings, inactive labels. */
    val dim: Int,
    /** `--b-well`, neutral icon-badge fill. */
    val well: Int,
    /** `--b-line`, default hairline on cards and fields. */
    val line: Int,
    /** `--b-line2`, the lighter edge on stat cells. */
    val line2: Int,
    /** `--b-dash`, dashed median line across the block chart. */
    val dash: Int,
    /** `--b-tint`, behind selected rows, count pills, icon badges, the caret. */
    val tint: Int,
    /** `--b-tint2`, recessed hint panels. */
    val tint2: Int,
    /** `--b-tint4` / `--b-tint5`, tinted panel borders. */
    val tint4: Int,
    val tint5: Int,
    /** `--b-green` label, `--b-green-dot` solid, `--b-green-t` chip ground. */
    val green: Int,
    val greenDot: Int,
    val greenChip: Int,
    /** `--b-amber` label, `--b-amber-bar` solid, `--b-amber-t` chip ground. */
    val amber: Int,
    val amberBar: Int,
    val amberChip: Int,
    /** `--b-brand`, every pressable thing, one colour. */
    val brand: Int,
    /** Pressed state. Day goes darker, Night goes lighter. */
    val brandPressed: Int,
    /** Whether this palette wants light system-bar icons. */
    val isDark: Boolean,
)

/** Which palette to use. [AUTO] follows the system's night mode. */
enum class ThemeMode {
    DAY,
    NIGHT,
    AUTO,
    ;

    companion object {
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

object Palettes {
    val DAY =
        Palette(
            screen = 0xFFF2F6FF.toInt(),
            panel = 0xFFFFFFFF.toInt(),
            ink = 0xFF0D1B3E.toInt(),
            muted = 0xFF60646C.toInt(),
            dim = 0xFF8B8D98.toInt(),
            well = Color.argb(15, 13, 27, 62),
            line = Color.argb(33, 13, 27, 62),
            line2 = Color.argb(26, 13, 27, 62),
            dash = Color.argb(51, 13, 27, 62),
            tint = 0xFFE4ECFF.toInt(),
            tint2 = 0xFFF4F7FF.toInt(),
            tint4 = 0xFFD2DDFF.toInt(),
            tint5 = 0xFFC1D0FF.toInt(),
            green = 0xFF218358.toInt(),
            greenDot = 0xFF30A46C.toInt(),
            greenChip = 0xFFE2F6EC.toInt(),
            amber = 0xFFAB6400.toInt(),
            amberBar = 0xFFFFC53D.toInt(),
            amberChip = 0xFFFFEECB.toInt(),
            brand = 0xFF1B57EF.toInt(),
            brandPressed = 0xFF1140C4.toInt(),
            isDark = false,
        )

    val NIGHT =
        Palette(
            screen = 0xFF0A101C.toInt(),
            panel = 0xFF111B2E.toInt(),
            ink = 0xFFE9F0FF.toInt(),
            muted = 0xFF94A6C8.toInt(),
            dim = 0xFF6F809F.toInt(),
            well = Color.argb(18, 255, 255, 255),
            line = Color.argb(51, 140, 175, 255),
            line2 = Color.argb(41, 140, 175, 255),
            dash = Color.argb(82, 160, 190, 255),
            tint = Color.argb(51, 43, 127, 255),
            tint2 = Color.argb(26, 43, 127, 255),
            tint4 = Color.argb(77, 43, 127, 255),
            tint5 = Color.argb(128, 43, 127, 255),
            green = 0xFF4ADE9B.toInt(),
            greenDot = 0xFF3DD68C.toInt(),
            greenChip = Color.argb(46, 61, 214, 140),
            amber = 0xFFF7C85A.toInt(),
            amberBar = 0xFFFFC53D.toInt(),
            amberChip = Color.argb(46, 255, 197, 61),
            brand = 0xFF2B7FFF.toInt(),
            brandPressed = 0xFF79AAFF.toInt(),
            isDark = true,
        )

    /** Resolves [mode] against the system configuration; [ThemeMode.AUTO] follows night mode. */
    fun resolve(
        context: Context,
        mode: ThemeMode,
    ): Palette =
        when (mode) {
            ThemeMode.DAY -> DAY
            ThemeMode.NIGHT -> NIGHT
            ThemeMode.AUTO -> {
                val night =
                    context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                        Configuration.UI_MODE_NIGHT_YES
                if (night) NIGHT else DAY
            }
        }
}
