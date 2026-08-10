package org.betsy.ui.connect

import android.content.Context

/**
 * Remembers which adapter was used last and what ATZ banner each one answered with. Both are needed
 * before a connection exists: the mockup's "Last used" badge and firmware chips are only honest if
 * they come from a previous successful session rather than being inferred from the device name.
 */
class AdapterMemory(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("connect_adapters", Context.MODE_PRIVATE)

    var lastUsedId: String?
        get() = prefs.getString(KEY_LAST_USED, null)
        set(value) = prefs.edit().putString(KEY_LAST_USED, value).apply()

    /** ATZ banner cached for [id], or null if we have never completed a session with it. */
    fun firmware(id: String): String? = prefs.getString(KEY_FIRMWARE + id, null)

    fun rememberFirmware(
        id: String,
        banner: String,
    ) {
        if (banner.isBlank()) return
        prefs.edit().putString(KEY_FIRMWARE + id, banner).apply()
    }

    private companion object {
        const val KEY_LAST_USED = "last_used_id"
        const val KEY_FIRMWARE = "firmware_"
    }
}
