package org.betsy.capture

import org.betsy.debug.CaptureLog
import org.betsy.debug.DemoMode
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of one submission attempt. */
sealed class UploadResult {
    /** Committed. [url] is the file in the repository, shown so the contribution is concrete. */
    data class Ok(
        val url: String,
    ) : UploadResult()

    /** Not committed. The capture is kept and the action stays available. */
    data class Failed(
        val reason: String,
    ) : UploadResult()
}

/**
 * Posts a [CaptureData] to the capture worker, which commits it to the project repository.
 *
 * No credentials, no account, no browser: the worker holds the only token. That is a deliberate
 * trade, and it is why the worker rate-limits and size-caps rather than trusting its callers.
 * This endpoint's URL ships inside a public APK and must be assumed known to hostile parties.
 */
object CaptureUploader {
    private const val ENDPOINT = "https://betsy-capture.betsy-data-capture.workers.dev"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Short pause before a demo submit answers, so the "Sending…" state is visible. */
    private const val DEMO_SUBMIT_MS = 250L

    /** The `"demo":true` field a demo capture serializes, so a cold-start retry is recognised. */
    private const val DEMO_MARKER = "\"demo\":true"

    /** Blocking; call from a background thread. */
    fun submit(data: CaptureData): UploadResult {
        if (DemoMode.active() || data.demo) return demoResult()
        return submitJson(data.toJson())
    }

    /**
     * Sends an already-serialised payload. This is what a retry uses: a capture held on disk is
     * resent exactly as it was collected, so recovering one never involves the car.
     */
    fun submitJson(json: String): UploadResult {
        // A demo session never leaves the device. The worker URL stays a compile-time constant
        // used only by the real path; a fixture must not land in the public repository under any
        // flag, so the short-circuit returns a local result after a short wait and opens nothing.
        if (DemoMode.active() || json.contains(DEMO_MARKER)) return demoResult()
        val body = json.toByteArray(Charsets.UTF_8)
        CaptureLog.log("CAPTURE", "submitting ${body.size} bytes")
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json")
                    setFixedLengthStreamingMode(body.size)
                }
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val text = conn.readBodyText()
            if (code in 200..299) {
                val url =
                    Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                        .find(text)
                        ?.groupValues
                        ?.get(1)
                        .orEmpty()
                CaptureLog.log("CAPTURE", "accepted: $url")
                UploadResult.Ok(url)
            } else {
                CaptureLog.log("CAPTURE", "rejected $code: ${text.take(200)}")
                UploadResult.Failed(describe(code, text))
            }
        } catch (e: Exception) {
            CaptureLog.logThrowable("CAPTURE", e)
            UploadResult.Failed(describeOffline(e.message ?: e.javaClass.simpleName))
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * The local answer for a demo capture: no connection, and the success/error surface still
     * renders. A demo capture retried outside a live demo session is refused outright rather than
     * sent under any flag.
     */
    private fun demoResult(): UploadResult {
        Thread.sleep(DEMO_SUBMIT_MS)
        CaptureLog.log("CAPTURE", "demo submit short-circuited, shareFails=${DemoMode.shareFails()}")
        return when {
            !DemoMode.active() -> UploadResult.Failed("This is a demo scan; it is not sent.")
            DemoMode.shareFails() -> UploadResult.Failed("Demo: the capture service could not be reached.")
            else -> UploadResult.Ok("demo://accepted")
        }
    }

    /**
     * A failure message has to answer two questions the user actually has: is this my fault, and
     * what do I do now. "Try again later" answered neither, and until the retry existed it asked
     * for something the app could not do.
     */
    private fun describe(
        code: Int,
        body: String,
    ): String =
        when (code) {
            413 -> "This scan is too big to send. Nothing you can do about it, please report it."
            429 -> "Too many scans sent from this connection in the last hour. Try again later."
            in 500..599 ->
                "Nothing wrong with your car or adapter, the problem is at our end. " +
                    "Your scan is saved and BETSY will offer to send it next time you open the app."
            in 400..499 ->
                "The capture service rejected this scan. Your scan is saved; please report this."
            else -> body.take(120).ifBlank { "HTTP $code" }
        }

    /** Network-layer failure, as opposed to a rejection by the worker. */
    fun describeOffline(reason: String): String =
        "Couldn't reach the capture service: $reason. Your scan is saved and BETSY will offer " +
            "to send it next time you open the app."

    private fun HttpURLConnection.readBodyText(): String =
        try {
            (if (responseCode in 200..299) inputStream else errorStream)
                ?.bufferedReader()
                ?.use(BufferedReader::readText)
                .orEmpty()
        } catch (_: Exception) {
            ""
        }
}
