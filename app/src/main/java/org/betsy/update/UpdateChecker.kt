package org.betsy.update

import org.betsy.BuildConfig
import org.betsy.debug.CaptureLog
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the latest published GitHub release and compares it to the installed [BuildConfig.VERSION_NAME].
 *
 * Unauthenticated on purpose: the APK cannot hold a token. `/latest` already skips drafts and
 * pre-releases. This is a GET of a public page the README already links; it never downloads an APK.
 */
object UpdateChecker {
    const val ENDPOINT = "https://api.github.com/repos/alrighdee/BETSY/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Blocking; call from a background thread. */
    fun fetch(installed: String = BuildConfig.VERSION_NAME): UpdateStatus =
        try {
            val (code, body) = get()
            interpret(code, body, installed)
        } catch (e: Exception) {
            CaptureLog.logThrowable("UPDATE", e)
            UpdateStatus.Unknown("Couldn't reach GitHub")
        }

    /**
     * Maps an HTTP result onto [UpdateStatus] without touching the network. Tests supply a body;
     * [fetch] is the only caller that opens a socket.
     */
    fun interpret(
        code: Int,
        body: String,
        installed: String,
    ): UpdateStatus {
        if (code !in 200..299) return UpdateStatus.Unknown("Couldn't reach GitHub")
        return try {
            val obj = JSONObject(body)
            val tag = obj.optString("tag_name").trim()
            val url = obj.optString("html_url").trim()
            if (tag.isEmpty() || url.isEmpty()) {
                UpdateStatus.Unknown("Couldn't read the latest release.")
            } else {
                evaluate(tag, url, installed)
            }
        } catch (_: Exception) {
            UpdateStatus.Unknown("Couldn't read the latest release.")
        }
    }

    /** Compare a release tag to the installed name. Dismiss is applied by the caller, not here. */
    fun evaluate(
        tagName: String,
        htmlUrl: String,
        installed: String,
    ): UpdateStatus {
        val latest = normalize(tagName)
        val here = normalize(installed)
        return if (compare(latest, here) > 0) {
            UpdateStatus.Available(latest, htmlUrl)
        } else {
            UpdateStatus.Current(here)
        }
    }

    /**
     * The banner shows [status] only when it is [UpdateStatus.Available] and that version has not
     * been dismissed. Settings does not go through here, so a dismissed update is still named.
     */
    fun visibleBanner(
        status: UpdateStatus,
        dismissed: String?,
    ): UpdateStatus.Available? {
        val available = status as? UpdateStatus.Available ?: return null
        return if (dismissed != null && compare(available.version, dismissed) == 0) null else available
    }

    /** Strip a leading `v` so `v0.0.4` and `0.0.4` compare as the same name. */
    fun normalize(tag: String): String {
        val trimmed = tag.trim()
        return if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed.substring(1) else trimmed
    }

    /**
     * Dotted integer segments, then an optional `-` pre-release suffix. `0.0.5-pre-release` is
     * newer than `0.0.4` and older than `0.0.5`. A missing segment is 0 (`0.0` equals `0.0.0`).
     * A segment that is not an integer is older than one that is, so a junk tag cannot outrank
     * a real one.
     */
    fun compare(
        a: String,
        b: String,
    ): Int {
        val left = parse(a)
        val right = parse(b)
        val n = maxOf(left.core.size, right.core.size)
        for (i in 0 until n) {
            val l = if (i < left.core.size) left.core[i] else 0
            val r = if (i < right.core.size) right.core[i] else 0
            when {
                l != null && r != null -> {
                    val c = l.compareTo(r)
                    if (c != 0) return c
                }
                l == null && r == null -> continue
                l == null -> return -1
                else -> return 1
            }
        }
        return when {
            left.pre == null && right.pre == null -> 0
            left.pre == null -> 1
            right.pre == null -> -1
            else -> left.pre.compareTo(right.pre)
        }
    }

    private data class Parsed(
        val core: List<Int?>,
        val pre: String?,
    )

    private fun parse(raw: String): Parsed {
        val name = normalize(raw)
        val dash = name.indexOf('-')
        val corePart = if (dash >= 0) name.substring(0, dash) else name
        val pre = if (dash >= 0) name.substring(dash + 1).ifEmpty { null } else null
        return Parsed(corePart.split('.').map { segment(it) }, pre)
    }

    private fun segment(raw: String?): Int? {
        if (raw == null || raw.isEmpty()) return 0
        return raw.toIntOrNull()
    }

    private fun get(): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn =
                (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    instanceFollowRedirects = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "BETSY/${BuildConfig.VERSION_NAME}")
                }
            val code = conn.responseCode
            val text = conn.readBodyText()
            CaptureLog.log("UPDATE", "latest $code ${text.take(80)}")
            code to text
        } finally {
            conn?.disconnect()
        }
    }

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
