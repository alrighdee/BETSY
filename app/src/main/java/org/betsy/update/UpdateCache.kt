package org.betsy.update

import android.content.Context

/**
 * Last successful look at `/releases/latest`, plus the version the user hid.
 *
 * Written only after a successful parse so a failed check does not start the 24-hour clock.
 * [dismissed] is per version: hiding `0.0.4` must not swallow `0.0.5`.
 */
class UpdateCache(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val checked = prefs.getLong(KEY_CHECKED_AT, 0L)
        return checked > 0L && nowMs - checked < FRESH_MS
    }

    fun latest(): String? = prefs.getString(KEY_LATEST, null)?.takeIf { it.isNotBlank() }

    fun url(): String? = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() }

    fun dismissed(): String? = prefs.getString(KEY_DISMISSED, null)?.takeIf { it.isNotBlank() }

    fun remember(
        latest: String,
        url: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        prefs
            .edit()
            .putLong(KEY_CHECKED_AT, nowMs)
            .putString(KEY_LATEST, latest)
            .putString(KEY_URL, url)
            .apply()
    }

    fun dismiss(version: String) {
        prefs.edit().putString(KEY_DISMISSED, version).apply()
    }

    /** Starts the freshness clock without changing the stored tag, used when `/latest` matches. */
    fun touch(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_CHECKED_AT, nowMs).apply()
    }

    companion object {
        const val FRESH_MS = 24L * 60L * 60L * 1000L
        private const val PREFS = "betsy_update"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_LATEST = "latest"
        private const val KEY_URL = "url"
        private const val KEY_DISMISSED = "dismissed"
    }
}
