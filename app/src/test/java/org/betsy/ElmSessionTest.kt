package org.betsy

import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.transport.ElmTransport
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ElmSessionTest {
    private class Replies(
        private val reply: (String, Int) -> String,
    ) : ElmTransport {
        override var readTimeoutMs: Int = 2500
        val commands = mutableListOf<String>()

        override suspend fun send(cmd: String): String {
            commands += cmd
            return reply(cmd, commands.count { it == cmd })
        }

        override fun close() {}
    }

    @Test
    fun unconfirmedAth0PreventsHeaderlessRequestsFromReachingTheTransport() {
        for (reply in listOf("?", "NO DATA", "NOT OK", "", "OK\rCAN ERROR")) {
            val transport =
                Replies { cmd, _ ->
                    if (cmd == "ATH0") {
                        reply
                    } else if (cmd.startsWith("AT")) {
                        "OK"
                    } else {
                        "5300"
                    }
                }
            val session = ElmSession(transport)
            awaitBlocking { session.rawCommand("ATH1") }
            awaitBlocking { session.rawCommand("ATH0") }
            val error = assertThrows(ElmException::class.java) { awaitBlocking { session.command("13B0") } }
            assertTrue(error.message!!.contains("ATH0"))
            assertFalse(transport.commands.contains("13B0"))
            assertEquals("", session.lastRawResponse)
        }
    }

    @Test
    fun rejectedOrInterruptedHeaderEnableStillRequiresConfirmedHeaderlessFormat() {
        for (failWithException in listOf(false, true)) {
            val transport =
                Replies { cmd, _ ->
                    if (cmd == "ATH1") {
                        if (failWithException) throw org.betsy.transport.TransportException("enable timeout")
                        "?"
                    } else {
                        "5300"
                    }
                }
            val session = ElmSession(transport)
            try {
                awaitBlocking { session.rawCommand("ATH1") }
            } catch (_: org.betsy.transport.TransportException) {
                // A failed exchange leaves the adapter state unconfirmed.
            }
            assertThrows(ElmException::class.java) { awaitBlocking { session.command("21C6") } }
            assertFalse(transport.commands.contains("21C6"))
        }
    }

    @Test
    fun aConfirmedAth0AllowsHeaderlessReadsAgain() {
        val transport =
            Replies { cmd, _ ->
                if (cmd == "ATH0") {
                    "ath0\r ok\r"
                } else if (cmd.startsWith("AT")) {
                    "OK"
                } else {
                    "5300"
                }
            }
        val session = ElmSession(transport)
        awaitBlocking { session.rawCommand("ATH1") }
        assertThrows(ElmException::class.java) { awaitBlocking { session.command("13B0") } }
        awaitBlocking { session.rawCommand("ATH0") }
        assertEquals("5300", awaitBlocking { session.command("13B0") })
        assertEquals(1, transport.commands.count { it == "13B0" })
    }

    @Test
    fun headerRequiresAnUnambiguousOkBeforeRunningTheEcuBlock() {
        for (header in listOf("7E0", "7E2", "7E3")) {
            for (raw in listOf("NOT OK", "OKAY", "OK\rCAN ERROR", "STOPPED\rOK", "?\rOK", "OK\r4100FFFFFFFF")) {
                val transport = Replies { _, _ -> raw }
                val session = ElmSession(transport)
                var ran = false
                val error =
                    assertThrows(ElmException::class.java) {
                        awaitBlocking {
                            session.withEcu(header) {
                                ran = true
                                session.command("13B0")
                            }
                        }
                    }
                assertFalse("$header: $raw", ran)
                assertEquals(listOf("ATSH$header", "ATSH$header"), transport.commands)
                assertEquals(raw, session.lastRawResponse)
                assertTrue(error.message!!.contains(raw.trim()))
            }
        }
    }

    @Test
    fun configurationDoesNotProceedPastAnUnconfirmedCommand() {
        val commands = listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATSTFF")
        for ((index, target) in commands.withIndex()) {
            val transport = Replies { cmd, _ -> if (cmd == target) "NOT OK" else "OK" }
            val error =
                assertThrows(ElmException::class.java) {
                    awaitBlocking { ElmSession(transport).configure() }
                }
            assertEquals(commands.take(index) + listOf(target, target), transport.commands)
            assertTrue(error.message!!.contains(target))
        }
    }

    @Test
    fun exactOkAcceptsWhitespaceCaseAndTheCurrentCommandEcho() {
        for (raw in listOf("OK", "\r\n ok \r\n", "ATSH7E3\r\nOK\r\n", "atsh7e3\r ok")) {
            val transport = Replies { _, _ -> raw }
            var ran = false
            awaitBlocking { ElmSession(transport).withEcu("7E3") { ran = true } }
            assertTrue(raw, ran)
            assertEquals(listOf("ATSH7E3"), transport.commands)
        }
        val transport = Replies { cmd, _ -> "$cmd\rOK" }
        awaitBlocking { ElmSession(transport).configure() }
        assertEquals(6, transport.commands.size)
    }

    @Test
    fun oneRetryCanConfirmTheAddress() {
        val transport = Replies { _, attempt -> if (attempt == 1) "NOT OK" else "OK" }
        var ran = false
        awaitBlocking { ElmSession(transport).withEcu("7E2") { ran = true } }
        assertTrue(ran)
        assertEquals(listOf("ATSH7E2", "ATSH7E2"), transport.commands)
    }
}
