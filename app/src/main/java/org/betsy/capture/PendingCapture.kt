package org.betsy.capture

import android.content.Context
import org.betsy.debug.CaptureLog
import java.io.File

/**
 * Holds a capture that has been collected but not yet accepted by the worker.
 *
 * This is a car app. It is used in underground garages, workshops and tunnels, where a send fails
 * for no better reason than there being no signal. Keeping the capture only in memory would mean
 * "try again" worked solely while the screen stayed open, and a scan lost that way is a
 * contributor lost with it: the car has to be read again to get it back.
 *
 * One slot. A second capture replaces the first, on the grounds that the newer read is the one the
 * owner just chose to share.
 */
object PendingCapture {
    private const val FILENAME = "pending-capture.json"

    private fun file(context: Context) = File(context.filesDir, FILENAME)

    fun save(
        context: Context,
        json: String,
    ) {
        try {
            file(context).writeText(json)
            CaptureLog.log("CAPTURE", "held for retry (${json.length} chars)")
        } catch (e: Exception) {
            // A capture we cannot persist is still worth trying to send; never crash over it.
            CaptureLog.logThrowable("CAPTURE", e)
        }
    }

    /** The stored payload, or null when there is nothing waiting. */
    fun load(context: Context): String? =
        try {
            file(context).takeIf { it.exists() && it.length() > 0 }?.readText()
        } catch (e: Exception) {
            CaptureLog.logThrowable("CAPTURE", e)
            null
        }

    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            CaptureLog.logThrowable("CAPTURE", e)
        }
    }

    fun exists(context: Context): Boolean = load(context) != null
}
