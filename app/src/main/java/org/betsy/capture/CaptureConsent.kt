package org.betsy.capture

import android.content.Context

/**
 * Whether the user has seen what a capture contains and agreed to publish one.
 *
 * Sharing writes a file into a public repository, so the first submission is preceded by a screen
 * saying exactly that. It is one screen with one button, not an account and not a form: the point
 * of this pipeline is that a stranger with a broken car can help in a single tap, and every step
 * added between them and SHARE is a contributor lost.
 *
 * Nothing in [org.betsy.capture] may open a connection while this is false.
 */
object CaptureConsent {
    private const val PREFS = "betsy_capture"
    private const val KEY_ACCEPTED = "disclosure_accepted"

    fun isAccepted(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACCEPTED, false)

    fun accept(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ACCEPTED, true)
            .apply()
    }

    /**
     * What the disclosure promises, kept next to the flag so the two cannot drift apart.
     *
     * The IP entry is about the service rather than the payload: the app sends nothing
     * identifying, but the capture endpoint keys its per-IP rate limit on the caller's address
     * for a couple of hours. The worker is open source, so anyone can read that for themselves;
     * a promise that quietly omitted it would be found rather than believed.
     */
    val SENT =
        listOf(
            "Raw responses from the hybrid ECU (13B0, 21C6-21CA) and engine ECU (7E0/13B0)",
            "App version, detected generation, adapter model",
            "A short extract of this session's diagnostic log",
            "Anything you type in the notes field",
            "Your IP address, briefly, and only to limit abuse",
        )

    val NOT_SENT =
        listOf(
            "VIN",
            "Location",
            "Any device or account identifier",
        )
}
