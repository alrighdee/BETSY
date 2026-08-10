package org.betsy

import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.dtc.DtcReader
import org.betsy.elm.ElmSession
import org.betsy.transport.ElmTransport
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTC/INF read sequencing per PROTOCOL.md §9.1/§9.4 against a scripted fake transport.
 * Gen3 = `0A` + `13 B0` on 7E2; Gen2 = `13 B0` on 7E2 + `13 80` on 7E3; the `7E2` Gen2 layout = both masks
 * on 7E2; INF `22 05 CA` always on 7E2. Empty groups are dropped; failures become notes.
 */
class DtcReaderTest {
    private class FakeTransport(
        private val responses: Map<String, String>,
    ) : ElmTransport {
        override var readTimeoutMs: Int = 2500
        val commands = mutableListOf<String>()

        override suspend fun send(cmd: String): String {
            commands += cmd
            return responses[cmd] ?: "OK"
        }

        override fun close() {}
    }

    private fun payload(vararg set: Pair<Int, Int>): String {
        val b = ByteArray(48) // a 2009 Gen2 returns 48 (PROTOCOL.md §9.4.0)
        for ((k, v) in set) b[k] = v.toByte()
        return b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun reader(
        transport: FakeTransport,
        model: VehicleModel,
    ): DtcReader =
        DtcReader(
            ElmSession(transport),
            VehicleInfo(
                model = model,
                supported = true,
                blockCount = 14,
                cellCount = 28,
            ),
        )

    @Test
    fun gen3ReadsPermanentThenStoredAndInf() {
        val t =
            FakeTransport(
                mapOf(
                    "ATSH7E2" to "OK",
                    "0A" to "4A010A80", // permanent: P0A80
                    "13B0" to "530230000A80", // stored: P3000, P0A80 (dupe)
                    "21C6" to "61C6" + payload(),
                    "21C7" to "61C7" + payload(),
                    "21C8" to "61C8" + payload(),
                    "21C9" to "61C9" + payload(35 to 0x01), // INF 526 active
                    "21CA" to "61CA" + payload(13 to 0x42), // INF 611 active
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN3).read() }

        assertEquals(1, result.groups.size)
        val codes = result.groups.single().codes
        assertEquals("HV ECU (7E2)", result.groups.single().label)
        assertEquals(listOf("P0A80", "P3000"), codes.map { it.code })
        // Set bytes in the C9 and CA tables are read and kept verbatim, but nothing is named:
        // InfLayout ships no field map, so a fault decodes to nothing and the capture carries
        // the bytes instead of an interpretation (PROTOCOL.md §9.4.0).
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertTrue(result.rawResponses.getValue("21C9").startsWith("61C9"))
        assertTrue(result.rawResponses.getValue("21CA").startsWith("61CA"))
        assertEquals(emptyList<String>(), result.notes)
        assertEquals(
            listOf("ATSH7E2", "0A", "13B0", "ATSH7E2", "21C6", "21C7", "21C8", "21C9", "21CA"),
            t.commands,
        )
    }

    @Test
    fun gen2ReadsBothEcuHeaders() {
        val t =
            FakeTransport(
                mapOf(
                    "ATSH7E2" to "OK",
                    "ATSH7E3" to "OK",
                    "13B0" to "53010A80",
                    "1380" to "5300", // no codes on the battery ECU
                    "21C6" to "61C6" + payload(),
                    "21C7" to "61C7" + payload(),
                    "21C8" to "61C8" + payload(),
                    "21C9" to "61C9" + payload(),
                    "21CA" to "61CA" + payload(),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(1, result.groups.size) // 7E3 returned nothing → group dropped
        val codes = result.groups.single().codes
        assertEquals("HV ECU (7E2)", result.groups.single().label)
        assertEquals(listOf("P0A80"), codes.map { it.code })
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertEquals(
            listOf("ATSH7E2", "13B0", "ATSH7E3", "1380", "ATSH7E2", "21C6", "21C7", "21C8", "21C9", "21CA"),
            t.commands,
        )
    }

    @Test
    fun failedReadBecomesNoteNotSilentEmpty() {
        val t =
            FakeTransport(
                mapOf(
                    "ATSH7E2" to "OK",
                    "0A" to "NO DATA",
                    "13B0" to "53010A80",
                    "21C6" to "NO DATA",
                    "21C7" to "NO DATA",
                    "21C8" to "NO DATA",
                    "21C9" to "NO DATA",
                    "21CA" to "NO DATA",
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN3).read() }

        val codes = result.groups.single().codes
        assertEquals(listOf("P0A80"), codes.map { it.code })
        // one note for the failed mode-0A read, then one per refused detail table
        assertEquals(6, result.notes.size)
        assertEquals(true, result.notes[0].startsWith("HV ECU (7E2) 0A"))
        assertEquals(true, result.notes[1].startsWith("INF 21C6 on 7E2"))
        assertEquals(true, result.notes[5].startsWith("INF 21CA on 7E2"))
    }

    @Test
    fun unsupportedGenerationThrows() {
        val t = FakeTransport(emptyMap())
        val thrown =
            try {
                awaitBlocking { reader(t, VehicleModel.GEN1).read() }
                null
            } catch (e: UnsupportedOperationException) {
                e
            }
        assertEquals("Gen1 DTC read is not supported in this build", thrown?.message)
    }
}
