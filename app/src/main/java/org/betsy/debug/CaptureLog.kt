package org.betsy.debug

import android.content.Context
import android.os.Build
import org.betsy.BuildConfig
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Car-test capture instrumentation (M2): a file-backed, thread-safe log of every ELM327
 * command, raw response, normalized result, decode summary, detection step, and failure.
 * It runs for the whole session, so a phone taken into the car (dongle attached) can be
 * brought back and the capture pulled to this laptop for analysis:
 *
 *     adb pull /sdcard/Android/data/org.betsy/files/captures/<session>.log
 *
 * [DebugLogActivity] shows the tail live for in-car sanity checks.
 */
object CaptureLog {
    private const val MAX_LINES = 50_000
    private val lock = Object()
    private val ring = ArrayDeque<String>()
    private var writer: BufferedWriter? = null
    private var startMonoMs = 0L
    private var lineCount = 0

    /** The current session's capture file; null until [start]. */
    var captureFile: File? = null
        private set

    /** Begins a capture session in the app's external files dir; safe to call once. */
    fun start(appContext: Context) {
        synchronized(lock) {
            if (writer != null) return
            val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val dir = File(base, "captures").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "session-$stamp.log")
            writer = file.bufferedWriter()
            captureFile = file
            startMonoMs = System.nanoTime() / 1_000_000
            writeLine(
                "CAPTURE",
                "session started $stamp (${BuildConfig.VERSION_NAME}, api ${Build.VERSION.SDK_INT}, ${Build.MODEL})",
            )
            writeLine("CAPTURE", "capture file: ${file.absolutePath}")
        }
    }

    fun log(
        tag: String,
        message: String,
    ) {
        synchronized(lock) { writeLine(tag, message) }
    }

    fun logThrowable(
        tag: String,
        t: Throwable,
    ) {
        synchronized(lock) {
            writeLine(tag, "${t.javaClass.simpleName}: ${t.message}")
            for (el in t.stackTrace.take(6)) {
                writeLine(tag, "  at $el")
            }
        }
    }

    /** Most recent lines, newest last, for the in-app viewer. */
    fun tail(maxLines: Int): List<String> = synchronized(lock) { ring.takeLast(maxLines).toList() }

    /** Flushes and closes the capture file. */
    fun close() {
        synchronized(lock) {
            writer?.let {
                writeLine("CAPTURE", "session ended")
                it.flush()
                it.close()
            }
            writer = null
        }
    }

    private fun writeLine(
        tag: String,
        message: String,
    ) {
        val w = writer ?: return
        if (lineCount >= MAX_LINES) return
        lineCount += 1
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val mono = (System.nanoTime() / 1_000_000) - startMonoMs
        val line = "$stamp [+${mono}ms] $tag $message"
        ring.addLast(line)
        while (ring.size > 4096) ring.removeFirst()
        w.append(line).append('\n')
        w.flush()
    }
}
