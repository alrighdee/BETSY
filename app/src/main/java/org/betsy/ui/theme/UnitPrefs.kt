package org.betsy.ui.theme

import android.content.Context

/**
 * Display preferences from the mockup's Settings screen: temperature unit and keep-awake.
 *
 * Temperature is a *display* concern only. `BatteryModel` stores °C throughout (PROTOCOL.md §5.2)
 * and the conversion happens at render time, so flipping this never touches decoded values.
 */
object UnitPrefs {
    private const val FILE = "betsy.prefs"
    private const val KEY_FAHRENHEIT = "temp_fahrenheit"
    private const val KEY_KEEP_AWAKE = "keep_awake"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Default is metric, matching the mockup's Celsius-first ordering. */
    fun fahrenheit(context: Context): Boolean = prefs(context).getBoolean(KEY_FAHRENHEIT, false)

    fun setFahrenheit(
        context: Context,
        value: Boolean,
    ) = prefs(context).edit().putBoolean(KEY_FAHRENHEIT, value).apply()

    fun keepAwake(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_AWAKE, true)

    fun setKeepAwake(
        context: Context,
        value: Boolean,
    ) = prefs(context).edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    /** °C in, display value out. */
    fun temperature(
        context: Context,
        celsius: Float,
    ): Float = if (fahrenheit(context)) celsius * 9f / 5f + 32f else celsius

    fun temperatureUnit(context: Context): String = if (fahrenheit(context)) "°F" else "°C"

    /** Speed follows the same preference, imperial units travel together. */
    fun speed(
        context: Context,
        kmh: Float,
    ): Float = if (fahrenheit(context)) kmh * 0.621371f else kmh

    fun speedUnit(context: Context): String = if (fahrenheit(context)) "mph" else "km/h"
}
