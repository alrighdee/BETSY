package org.betsy

import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.dtc.DtcReader
import org.betsy.elm.ElmSession
import org.betsy.model.BatteryModel
import org.betsy.poll.Poller
import org.betsy.transport.ReplayTransport
import org.betsy.transport.SimulatedCar
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end exercise of the INF path against a scripted car (PROTOCOL.md §9.4.0).
 *
 * The real test vehicle is fault-free, so every detail table reads zero and the code that
 * handles a *set* byte never runs on hardware. These tests run the whole stack, transport,
 * session, reader, decoder, against a car that does have a stored fault.
 *
 * This proves the decode path is wired correctly. It does **not** validate that byte 13 of the
 * `CA` table really means INF 611; that mapping is derived and needs a capture from a genuinely
 * faulty car.
 */
class SimulatedCarTest {
    private val gen2 = VehicleInfo(VehicleModel.GEN2, supported = true, blockCount = 14, cellCount = 28)

    private fun read(script: Map<String, String>): Pair<ReplayTransport, org.betsy.dtc.DtcReadResult> {
        val t = ReplayTransport(script)
        val result = awaitBlocking { DtcReader(ElmSession(t), gen2).read() }
        return t to result
    }

    @Test
    fun aStoredFaultIsReadAndItsTablesCapturedVerbatim() {
        val (_, result) = read(SimulatedCar.gen2WithStoredFault)

        // ReplayTransport answers the same 13B0 for HV and engine, so both groups see P0AA6.
        assertEquals(listOf("P0AA6", "P0AA6"), result.groups.flatMap { g -> g.codes.map { it.code } })
        assertTrue(result.hasStoredDtcs)

        // Set bits in the C9 and CA tables reach the capture untouched. Naming them is what
        // InfLayout deliberately does not do, so nothing decodes and the capture is flagged a
        // decoder miss: the raw bytes are the deliverable, the interpretation is not.
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertTrue(result.rawResponses.getValue("7E2/21C9").contains("61C9"))
        assertTrue(result.rawResponses.getValue("7E2/21CA").contains("61CA"))
        assertTrue(
            result.rawResponses
                .getValue("7E2/21CA")
                .trimEnd('0')
                .isNotEmpty(),
        )
    }

    @Test
    fun theWireSequenceIsFiveSeparateTableReadsUnderOneHeader() {
        val (t, _) = read(SimulatedCar.gen2WithStoredFault)
        // 7E2 refuses a batched 21C6C7C8C9CA with 7F2112, so each table is its own request -
        // but they share one ATSH7E2 (§9.4.0).
        assertEquals(
            listOf(
                "ATSH7E2",
                "0100",
                "ATSH7E2",
                "13B0",
                "ATSH7E3",
                "1380",
                "ATSH7E0",
                "13B0",
                "ATSH7E2",
                "ATH1",
                "03",
                "ATH0",
                "ATSH7E2",
                "ATH1",
                "07",
                "ATH0",
                "ATSH7E2",
                "21C6",
                "21C7",
                "21C8",
                "21C9",
                "21CA",
            ),
            t.sent,
        )
        // The five INF reads are one critical section: a header set immediately before, and none
        // in between. An interleaved ATSH here is the race withEcu exists to prevent (§1.2).
        val first = t.sent.indexOf("21C6")
        val last = t.sent.indexOf("21CA")
        assertEquals("ATSH7E2", t.sent[first - 1])
        assertTrue(t.sent.subList(first, last + 1).none { it.startsWith("ATSH") })
    }

    @Test
    fun gen2LivenessAndGenericFieldsArePopulated() {
        val (_, result) = read(SimulatedCar.gen2Healthy)
        assertTrue(result.liveness!!.responding)
        assertEquals("Responding", result.liveness!!.detail)
        assertEquals(emptyList<String>(), result.storedGenericDtcs.map { it.code })
        assertEquals(emptyList<String>(), result.pendingGenericDtcs.map { it.code })
        assertEquals(5, result.infTablesResponded)
        assertTrue(result.rawResponses.containsKey("7E2/0100"))
        assertTrue(result.rawResponses.getValue("7E2/03").contains("7EA"))
    }

    @Test
    fun storedFaultAlsoExposesGenericStoredCodeSeparately() {
        val (_, result) = read(SimulatedCar.gen2WithStoredFault)
        // KWP path still reports P0AA6; generic $03 also has P0AA6; they stay separate fields.
        assertTrue(result.hasStoredDtcs)
        assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
        assertEquals(5, result.infTablesResponded)
    }

    @Test
    fun healthyCarStillReadsAllFiveTablesAsABaseline() {
        val (t, result) = read(SimulatedCar.gen2Healthy)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertFalse(result.hasStoredDtcs)

        // An earlier version skipped this read when nothing was stored. It is fired now, because
        // the all-zero answer is itself the evidence: when a genuinely faulty car eventually reads
        // back as zero, only a body of healthy captures distinguishes "the mapping is wrong" from
        // "the read failed on that adapter" (§9.4.0).
        assertEquals(
            listOf("21C6", "21C7", "21C8", "21C9", "21CA"),
            t.sent.filter { it.startsWith("21C") && it.length == 4 },
        )
        assertTrue(result.notes.none { it.contains("No stored codes") })
    }

    @Test
    fun everyRequestKeepsItsVerbatimResponseForSharing() {
        val (_, result) = read(SimulatedCar.gen2WithStoredFault)

        // The decoded InfDetail values are a hypothesis about these bytes. A capture has to be
        // able to disagree with the decoder, so the bytes travel beside the interpretation.
        assertEquals(
            listOf(
                "7E2/0100",
                "7E2/13B0",
                "7E3/1380",
                "7E0/13B0",
                "7E2/03",
                "7E2/07",
                "7E2/21C6",
                "7E2/21C7",
                "7E2/21C8",
                "7E2/21C9",
                "7E2/21CA",
            ),
            result.rawResponses.keys.toList(),
        )
        assertTrue(result.rawResponses.getValue("7E2/21CA").startsWith("61CA"))
        assertEquals("53010AA6", result.rawResponses.getValue("7E2/13B0"))
        assertTrue(result.hasStoredDtcs)
    }

    @Test
    fun aStoredDtcIsReportedEvenWhenNoSubCodeDecodes() {
        // The capture worth building the pipeline for: the ECU has a fault, the mapping yields
        // nothing. hasStoredDtcs must stay true so this is filed as real rather than as noise.
        val blind = SimulatedCar.gen2WithStoredFault + mapOf("21C9" to "61C9", "21CA" to "61CA")
        val (_, result) = read(blind)

        assertTrue(result.hasStoredDtcs)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertEquals(listOf("P0AA6", "P0AA6"), result.groups.flatMap { g -> g.codes.map { it.code } })
    }

    /**
     * A car answering 21CE on 7E2 is recognised but never decoded: no such car has been observed,
     * so its battery layout has never been checked and must not produce numbers. It is still
     * readable for codes and raw tables, which is the capture that would make it supportable.
     */
    @Test
    fun anUnverifiedLayoutIsRecognisedForCaptureButNotForDecoding() {
        val info =
            VehicleInfo(
                model = VehicleModel.GEN2_7E2,
                supported = false,
                captureOnly = true,
                blockCount = 0,
                cellCount = 0,
            )
        assertFalse(info.supported)
        assertTrue(info.captureOnly)

        // Live polling must refuse it outright rather than decode with unchecked offsets.
        val threw =
            try {
                awaitBlocking { Poller(ElmSession(ReplayTransport(SimulatedCar.gen2Healthy)), info).poll(BatteryModel()) }
                false
            } catch (_: UnsupportedOperationException) {
                true
            }
        assertTrue("live polling must refuse an unverified layout", threw)
    }

    @Test
    fun aSilentEcuBecomesANoteRatherThanASilentEmptyResult() {
        // Script the DTC read but not the detail tables: the ECU goes quiet mid-sweep.
        val partial = SimulatedCar.gen2WithStoredFault.filterKeys { !it.startsWith("21C") || it == "21CED0CF" }
        val (_, result) = read(partial)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertEquals(5, result.notes.count { it.startsWith("INF 21C") })
    }
}
