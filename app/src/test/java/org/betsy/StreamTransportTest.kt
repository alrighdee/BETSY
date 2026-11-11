package org.betsy

import org.betsy.elm.ElmSession
import org.betsy.transport.StreamTransport
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

class StreamTransportTest {
    private fun transport(
        reply: String,
        failure: IOException? = null,
        sent: ByteArrayOutputStream = ByteArrayOutputStream(),
    ): StreamTransport =
        object : StreamTransport() {
            override val input =
                object : InputStream() {
                    private var index = 0

                    override fun read(): Int {
                        if (index < reply.length) return reply[index++].code
                        if (failure != null) throw failure
                        return -1
                    }
                }
            override val output = sent

            override fun applyTimeout(ms: Int) {}

            override fun close() {}
        }

    @Test
    fun failuresRetainOnlyBytesFromTheirOwnUnfinishedExchange() {
        val partial = "032\r0:61C78080\r"
        for (failure in listOf(null, SocketTimeoutException("late prompt"), IOException("connection reset"))) {
            val sent = ByteArrayOutputStream()
            val session = ElmSession(transport(partial, failure, sent))
            val error = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C7") } }
            assertEquals(partial, error.partialResponse)
            assertEquals(partial, session.lastRawResponse)
            assertEquals("21C7\r", sent.toString("US-ASCII"))
            assertSame(failure, error.cause)
        }
    }

    @Test
    fun anEmptyFailedExchangeDoesNotInventRawEvidence() {
        val session = ElmSession(transport("", SocketTimeoutException("no bytes")))
        val error = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C7") } }
        assertEquals("", error.partialResponse)
        assertEquals("", session.lastRawResponse)
    }

    @Test
    fun lateReplyCannotBeReadOrAssignedToTheNextCommand() {
        val sent = ByteArrayOutputStream()
        var reads = 0
        var closed = false
        val t =
            object : StreamTransport() {
                override val input =
                    object : InputStream() {
                        private val prefix = "61C78080"

                        override fun read(): Int {
                            val index = reads++
                            if (index < prefix.length) return prefix[index].code
                            if (index == prefix.length) throw SocketTimeoutException("late prompt")
                            return '>'.code // a delayed prompt must never satisfy the next command
                        }
                    }
                override val output = sent

                override fun applyTimeout(ms: Int) {}

                override fun close() {
                    closed = true
                }
            }
        val session = ElmSession(t)
        val first = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C7") } }
        assertEquals("61C78080", first.partialResponse)
        val readsAfterFailure = reads
        val next = assertThrows(TransportException::class.java) { awaitBlocking { session.rawCommand("21C8") } }
        assertTrue(next.message!!.contains("reconnect"))
        assertEquals("", next.partialResponse)
        assertEquals("", session.lastRawResponse)
        assertEquals(readsAfterFailure, reads)
        assertEquals("21C7\r", sent.toString("US-ASCII"))
        assertTrue(closed)
    }

    @Test
    fun failedWriteClosesTheStreamAndBlocksFurtherCommands() {
        var writes = 0
        var closed = false
        val failure = IOException("write interrupted")
        val t =
            object : StreamTransport() {
                override val input =
                    object : InputStream() {
                        override fun read(): Int = throw AssertionError("must not read after a failed write")
                    }
                override val output =
                    object : ByteArrayOutputStream() {
                        override fun write(
                            b: ByteArray,
                            off: Int,
                            len: Int,
                        ) {
                            writes++
                            throw failure
                        }
                    }

                override fun applyTimeout(ms: Int) {}

                override fun close() {
                    closed = true
                }
            }
        val first = assertThrows(TransportException::class.java) { awaitBlocking { t.send("21C7") } }
        assertSame(failure, first.cause)
        assertEquals("", first.partialResponse)
        assertThrows(TransportException::class.java) { awaitBlocking { t.send("21C8") } }
        assertEquals(1, writes)
        assertTrue(closed)
    }

    @Test
    fun completedExchangesCanContinueOnTheSameStream() {
        val sent = ByteArrayOutputStream()
        val t = transport("61C70000>61C80000>", sent = sent)
        assertEquals("61C70000", awaitBlocking { t.send("21C7") })
        assertEquals("61C80000", awaitBlocking { t.send("21C8") })
        assertEquals("21C7\r21C8\r", sent.toString("US-ASCII"))
    }

    @Test
    fun aPromptStillTerminatesACompleteReplyNormally() {
        val raw = "61C70000\r"
        val session = ElmSession(transport(raw + ">"))
        assertEquals(raw, awaitBlocking { session.rawCommand("21C7") })
        assertEquals(raw, session.lastRawResponse)
    }
}
