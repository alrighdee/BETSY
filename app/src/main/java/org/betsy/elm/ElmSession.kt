package org.betsy.elm

import org.betsy.debug.CaptureLog
import org.betsy.transport.ElmTransport
import java.util.concurrent.locks.ReentrantLock

/** Session-level failure: an AT command did not get its expected answer (PROTOCOL.md §1.1). */
class ElmException(
    message: String,
) : Exception(message)

/**
 * ELM327 session: §1.1 init sequence, §1.2 ECU addressing, and OBD requests returning the
 * §2-normalized hex string R.
 */
class ElmSession(
    val transport: ElmTransport,
) {
    /** Serializes command exchanges. The poll loop and the one-shot DTC/INF read run on different
     * threads but share one transport; without this they would garble each other's bytes.
     *
     * It guards one exchange, so on its own it does **not** stop two threads interleaving at the
     * *group* level: `ATSH` is global adapter state, and another thread's header can land between a
     * header and the commands that depend on it. Use [withEcu] for anything addressed to a specific
     * ECU. Reentrant because our suspend calls run inline (blocking I/O, no real suspension), so
     * [withEcu] can hold it across nested exchanges (§1.2, §6). */
    private val lock = ReentrantLock()

    /** Inter-command timeout, §6: 2500 ms for Gen2–Gen4.5, 8000 ms for Gen1 and Tahoe. */
    var commandTimeoutMs: Int = 2500

    /** Raw text of the most recent response, kept for §3-style diagnostic breadcrumbs. */
    var lastRawResponse: String = ""
        private set

    /**
     * Firmware banner the adapter answered ATZ with, e.g. `ELM327 v2.2`. Empty until [reset] runs.
     * It is the only firmware identity available, so the connect screen caches it per adapter to
     * tell a genuine ELM327 from a clone before the next connection.
     */
    var adapterBanner: String = ""
        private set

    /** §1.1 session setup, in order; each step is retried once, then aborts naming the command. */
    suspend fun initialize() {
        reset()
        configure()
    }

    /** §1.1 step 1: ATZ answers with the adapter banner, not OK; the banner names the firmware. */
    suspend fun reset() {
        expect("ATZ", "ELM327")
        adapterBanner = parseBanner(lastRawResponse)
    }

    /** §1.1 steps 2-7: echo/linefeed/space/headers off, auto protocol, maximum timeout. */
    suspend fun configure() {
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATSTFF")) {
            expect(cmd, "OK")
        }
    }

    /** §1.2: select the target ECU before each command group. */
    suspend fun setHeader(header: String) {
        expect("ATSH$header", "OK")
    }

    /**
     * §1.2: addresses [header] and runs [block] as one critical section.
     *
     * `ATSH` is adapter-global, so a header plus the commands that depend on it must be indivisible.
     * Locking per exchange is not enough: the 2 Hz poll loop and a DTC sweep run concurrently, and
     * the sweep's `ATSH7E2` landing between the poller's `ATSH7E3` and its `21CED0CF` sends a Gen2
     * battery read to the HV ECU. Every ECU-addressed group goes through here.
     */
    suspend fun <T> withEcu(
        header: String,
        block: suspend () -> T,
    ): T {
        lock.lock()
        try {
            setHeader(header)
            return block()
        } finally {
            lock.unlock()
        }
    }

    /** Issues an OBD request and returns the §2-normalized response R. */
    suspend fun command(cmd: String): String {
        val raw = sendRaw(cmd)
        if (raw.contains('?')) {
            val e = ElmException("adapter rejected command '$cmd'")
            CaptureLog.logThrowable("ELM", e)
            throw e
        }
        if (raw.contains("NO DATA") ||
            raw.contains("CAN ERROR") ||
            raw.contains("BUS ERROR") ||
            raw.contains("UNABLE TO CONNECT")
        ) {
            val e = NoDataException("$cmd: ${raw.trim()}")
            CaptureLog.logThrowable("ELM", e)
            throw e
        }
        val r = Normalize.normalize(raw)
        NegativeResponse.parse(r)?.let { refusal ->
            // A refusal is not data. Naming it here keeps "mode 22 not supported" from reaching a
            // decoder and being reported as a missing tag.
            val e = NegativeResponseException(refusal, cmd)
            CaptureLog.logThrowable("ELM", e)
            throw e
        }
        CaptureLog.log("ELM", "== $r")
        return r
    }

    private suspend fun expect(
        cmd: String,
        token: String,
    ) {
        repeat(2) {
            // §1.1: retry once, then abort and report the failing command
            if (sendRaw(cmd).contains(token)) return
        }
        throw ElmException("ELM327 setup failed at $cmd (last response: ${lastRawResponse.trim()})")
    }

    private suspend fun sendRaw(cmd: String): String {
        lock.lock()
        try {
            transport.readTimeoutMs = commandTimeoutMs
            CaptureLog.log("ELM", ">> $cmd")
            val raw = transport.send(cmd)
            lastRawResponse = raw
            CaptureLog.log("ELM", "<< ${raw.trim()}")
            return raw
        } catch (e: Exception) {
            CaptureLog.logThrowable("ELM", e)
            throw e
        } finally {
            lock.unlock()
        }
    }

    companion object {
        /**
         * Picks the banner line out of an ATZ response (§1.1). The reply carries the command echo
         * and blank lines around it, so the banner is the line that actually names the chip.
         */
        fun parseBanner(raw: String): String =
            raw
                .split('\r', '\n')
                .map { it.trim() }
                .firstOrNull { it.contains("ELM327", ignoreCase = true) }
                ?: ""
    }
}
