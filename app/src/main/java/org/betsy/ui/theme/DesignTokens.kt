package org.betsy.ui.theme

import android.graphics.Color

/**
 * Design tokens for B.E.T.S.Y., transcribed from `BETSY.dc.html`.
 *
 * The design ships **one layout in two palettes**. Day and Night swap about twenty colours and
 * nothing else, no structure, copy, type ramp or radius differs, so the colour tokens delegate to
 * a swappable [Palette] while the type and radius scales stay constant.
 *
 * [palette] is installed by [ThemePrefs.apply] in `Activity.onCreate` **before any view is built**,
 * because views read these once at construction rather than observing them. A theme change
 * therefore recreates the activity.
 *
 * The Radix-conventional scale names are kept: they stay semantically true across a flip, since
 * `GRAY_1` is the app background in either palette and `GRAY_12` the highest-contrast text.
 */
object DesignTokens {
    /** Current palette. Defaults to Night so a view built before [ThemePrefs.apply] is not white. */
    @JvmStatic
    var palette: Palette = Palettes.NIGHT

    // ── Ground and ink ──

    /** App/screen background, `--b-screen`. */
    val GRAY_1: Int get() = palette.screen

    /** Raised card, field, menu and secondary-button fill, `--b-panel`. */
    val GRAY_2: Int get() = palette.panel

    /** Recessed hint panel, `--b-tint2`. */
    val GRAY_3: Int get() = palette.tint2

    /** Neutral icon-badge fill, `--b-well`. */
    val GRAY_4: Int get() = palette.well

    /** Track behind the progress ring and bars, `--b-tint`. */
    val GRAY_5: Int get() = palette.tint

    /** Idle step dot, `--b-line2`. */
    val GRAY_9: Int get() = palette.line2

    /** Tertiary text, `--b-dim`. */
    val GRAY_10: Int get() = palette.dim

    /** Secondary text, `--b-muted`. */
    val GRAY_11: Int get() = palette.muted

    /** Primary text, `--b-ink`. */
    val GRAY_12: Int get() = palette.ink

    // ── Brand: every pressable thing, one colour ──

    val BRAND_SOLID: Int get() = palette.brand
    val BRAND_PRESSED: Int get() = palette.brandPressed
    val BRAND_TINT: Int get() = palette.tint
    val BRAND_BORDER: Int get() = palette.line
    val PANEL_BORDER: Int get() = palette.dash

    // ── Status tones ──

    val GREEN_SOLID: Int get() = palette.greenDot
    val GREEN_TEXT: Int get() = palette.green
    val GREEN_BG: Int get() = palette.greenChip
    val AMBER_SOLID: Int get() = palette.amberBar
    val AMBER_TEXT: Int get() = palette.amber
    val AMBER_BG: Int get() = palette.amberChip

    /** No red in either palette's token set; derived so a failure state still reads. */
    val RED_TEXT: Int get() = if (palette.isDark) 0xFFFF9592.toInt() else 0xFFCE2C31.toInt()
    val RED_BG: Int get() = Color.argb(if (palette.isDark) 46 else 30, 229, 72, 77)

    // ── Type scale, in sp. Constant across palettes. ──
    const val TEXT_TINY = 11f
    const val TEXT_1 = 12f
    const val TEXT_2 = 13f
    const val TEXT_3 = 15f
    const val TEXT_4 = 17f
    const val TEXT_5 = 19f
    const val TEXT_STAT = 20f
    const val TEXT_6 = 26f
    const val TEXT_HERO = 38f

    // ── Radii, in dp. Constant across palettes, the shape language of the wordmark. ──
    const val RADIUS_2 = 8f
    const val RADIUS_3 = 14f
    const val RADIUS_4 = 16f
    const val RADIUS_FIELD = 18f
    const val RADIUS_SELECT = 20f
    const val RADIUS_CARD = 22f
    const val RADIUS_PILL = 999f

    // ── Hairlines and surfaces ──

    /** Default card and field edge, `--b-line`. */
    val cardBorder: Int get() = palette.line

    /** Lighter edge on stat cells, `--b-line2`. */
    val hairline: Int get() = palette.line2

    val badgeFill: Int get() = palette.well
    val cardFill: Int get() = palette.panel
    val cardFillSelected: Int get() = palette.panel
    val cardBorderSelected: Int get() = palette.brand
    val badgeFillSelected: Int get() = palette.tint
    val menuFill: Int get() = palette.panel
    val optionSelected: Int get() = palette.tint
    val ghostBorder: Int get() = palette.line
    val subtleFill: Int get() = palette.tint2

    /** Tone-matched chip ground for the firmware badges. */
    fun chipFill(tone: Int): Int =
        when (tone) {
            GREEN_TEXT, GREEN_SOLID -> GREEN_BG
            AMBER_TEXT, AMBER_SOLID -> AMBER_BG
            RED_TEXT -> RED_BG
            BRAND_SOLID -> BRAND_TINT
            else -> GRAY_4
        }
}
