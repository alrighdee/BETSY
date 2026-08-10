package org.betsy.ui.theme

import android.content.Context

/**
 * The user's appearance choice, persisted. Settings offers Day / Night / Match phone, matching the
 * mockup's `theme` prop (`Day`, `Night`, `Auto`).
 *
 * [apply] must run before any view is constructed, because views read [DesignTokens] once at build
 * time rather than observing it. Changing the mode therefore recreates the activity, which is the
 * ordinary Android behaviour for a theme switch and avoids every view needing a listener.
 */
object ThemePrefs {
    private const val FILE = "betsy.prefs"
    private const val KEY_THEME = "theme_mode"

    fun mode(context: Context): ThemeMode =
        ThemeMode.from(
            context
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getString(KEY_THEME, ThemeMode.AUTO.name),
        )

    fun setMode(
        context: Context,
        mode: ThemeMode,
    ) {
        context
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }

    /** Resolves the stored mode against the system and installs it into [DesignTokens]. */
    fun apply(context: Context): Palette {
        val palette = Palettes.resolve(context, mode(context))
        DesignTokens.palette = palette
        return palette
    }
}

/**
 * Installs the stored palette and paints the system bars to match.
 *
 * The bar *icon* colour cannot come from the XML theme any more: the palette is chosen at runtime,
 * so `windowLightStatusBar` would be fixed at whichever value was compiled in. This sets the
 * decor-view flags instead, which is the runtime equivalent.
 *
 * Call from `onCreate` **before** `setContentView`, views read [DesignTokens] once, at build time.
 */
fun android.app.Activity.applyBetsyTheme() {
    val palette = ThemePrefs.apply(this)
    window.statusBarColor = palette.screen
    window.navigationBarColor = palette.screen
    val decor = window.decorView
    var flags = decor.systemUiVisibility
    // Light bars want DARK icons, so the flag is set when the palette is NOT dark.
    flags =
        if (palette.isDark) {
            flags and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    .inv() and
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                    .inv()
        } else {
            flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    decor.systemUiVisibility = flags
}
