package org.betsy.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Byte-level link to an ELM327-compatible adapter (PROTOCOL.md §1).
 * Commands are ASCII lines terminated by '\r'; the adapter's '>' prompt ends each response.
 */
interface ElmTransport {
    /** Read timeout while waiting for the '>' prompt (§6: 2500 ms for Gen2–Gen4.5, 8000 ms Gen1/Tahoe). */
    var readTimeoutMs: Int

    /** Sends one command and returns the raw ASCII response up to (excluding) the '>' prompt. */
    suspend fun send(cmd: String): String

    fun close()
}

class TransportException(
    message: String,
) : Exception(message)

/** Shared prompt-delimited command exchange for stream-based transports (§1). */
abstract class StreamTransport : ElmTransport {
    protected abstract val input: InputStream
    protected abstract val output: OutputStream

    /** Applies [readTimeoutMs] to the underlying stream, if the transport supports it. */
    protected abstract fun applyTimeout(ms: Int)

    final override var readTimeoutMs: Int = 2500
        set(value) {
            field = value
            applyTimeout(value)
        }

    final override suspend fun send(cmd: String): String {
        output.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        output.flush()
        val sb = StringBuilder()
        while (true) {
            val b =
                try {
                    input.read()
                } catch (e: SocketTimeoutException) {
                    throw TransportException("timeout after $readTimeoutMs ms waiting for '>' (cmd=$cmd)")
                }
            if (b < 0) throw TransportException("adapter closed the connection (cmd=$cmd)")
            if (b == '>'.code) break // §1: '>' is the response delimiter
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}

/**
 * Runs a suspend transport/session call on the calling thread and returns its result.
 * Milestone 1 has no third-party dependencies (no kotlinx-coroutines); our suspend functions
 * never actually suspend, they do blocking I/O inline, so a plain latch is sufficient.
 */
fun <T> awaitBlocking(block: suspend () -> T): T {
    val latch = CountDownLatch(1)
    var result: Result<T>? = null
    block.startCoroutine(
        object : Continuation<T> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(value: Result<T>) {
                result = value
                latch.countDown()
            }
        },
    )
    latch.await()
    return result!!.getOrThrow()
}
