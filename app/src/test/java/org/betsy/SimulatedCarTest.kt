package org.betsy

import org.betsy.decode.InfMeaning
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
 * End-to-end exercise of the INF path against a scripted car (PROTOCOL.md §7.4).
 *
 * These tests run the whole stack—transport, session, reader and decoder—against measured Gen2
 * responses carrying stored `P0571-115`. Additional scripted combinations exercise attribution
 * rules that cannot be produced by the single-fault fixture.
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

        // ReplayTransport answers the same 13B0 for HV and engine, so both groups see P0571.
        assertEquals(listOf("P0571", "P0571"), result.groups.flatMap { g -> g.codes.map { it.code } })
        assertTrue(result.hasStoredDtcs)

        // The payoff, on bytes a real car transmitted: one populated page, one sub-code, and it
        // is P0571's documented 115. Not a constant in the fixture; decoded out of the page.
        assertEquals(listOf(115), result.infCodes.map { it.code })
        assertEquals("Detail Code 2", result.infCodes.single().tableLabel)
        val resolution = result.infResolutions.single() as InfMeaning.Resolution.Exact
        assertEquals("P0571", resolution.dtc)
        assertEquals(115, resolution.inf)
        assertEquals(InfMeaning.forCode("P0571", 115), resolution.detail)

        // The bytes still travel beside the interpretation so the decoder can be checked again.
        assertTrue(result.rawResponses.getValue("7E2/21C7").contains("61C7"))
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
        // but they share one ATSH7E2 (§7.4.1).
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
                "020000",
                "020200",
                "020400",
                "020500",
                "020C00",
                "020D00",
                "020F00",
                "021100",
                "021F00",
                "023100",
                "024200",
                "024600",
                "024E00",
                "020001",
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
        // KWP path reports P0571; generic $03 is a separate observation and stays separate.
        assertTrue(result.hasStoredDtcs)
        assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
        assertEquals(5, result.infTablesResponded)
    }

    @Test
    fun healthyCarStillReadsAllFiveTablesAsABaseline() {
        val (t, result) = read(SimulatedCar.gen2Healthy)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertFalse(result.hasStoredDtcs)

        // An earlier version skipped this read when nothing was stored. It is fired now because
        // empty pages are the healthy baseline: a fault with no readable INF value must remain
        // distinguishable from a read that failed on that adapter (PROTOCOL.md §7.4).
        assertEquals(
            listOf("21C6", "21C7", "21C8", "21C9", "21CA"),
            t.sent.filter { it.startsWith("21C") && it.length == 4 },
        )
        assertTrue(result.notes.none { it.contains("No stored codes") })
    }

    @Test
    fun everyRequestKeepsItsVerbatimResponseForSharing() {
        val (_, result) = read(SimulatedCar.gen2WithStoredFault)

        // A capture has to be able to disagree with the decoder, so the bytes travel beside the
        // interpreted InfDetail values.
        assertEquals(
            listOf(
                "7E2/0100",
                "7E2/13B0",
                "7E3/1380",
                "7E0/13B0",
                "7E2/03",
                "7E2/07",
                "7E2/020000",
                "7E2/020200",
                "7E2/020500",
                "7E2/024200",
                // the refusal is kept deliberately: it is how the frame count is discovered,
                // and a capture should record what was asked as well as what answered
                "7E2/020001",
                "7E2/21C6",
                "7E2/21C7",
                "7E2/21C8",
                "7E2/21C9",
                "7E2/21CA",
            ),
            result.rawResponses.keys.toList(),
        )
        // A real multi-frame reply carries its ISO-TP length ahead of the tag, so the raw is
        // "03261CA...", not "61CA...". The fixture speaks the car's framing, not a tidied form.
        assertTrue(result.rawResponses.getValue("7E2/21CA").contains("61CA"))
        assertEquals("53010571", result.rawResponses.getValue("7E2/13B0"))
        assertTrue(result.hasStoredDtcs)
    }

    @Test
    fun aStoredDtcIsReportedEvenWhenNoSubCodeDecodes() {
        // A fault whose pages carry no sub-code. Expected for some codes, since a page is written
        // per DTC and not every DTC has one. hasStoredDtcs must stay true so this is filed as a
        // real capture rather than as noise: routing never keys on the decoder.
        val blind =
            SimulatedCar.gen2WithStoredFault +
                mapOf("21C7" to "03261C7" + "0".repeat(97))
        val (_, result) = read(blind)

        assertTrue(result.hasStoredDtcs)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertEquals(listOf("P0571", "P0571"), result.groups.flatMap { g -> g.codes.map { it.code } })
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
        // Script the DTC read but not the INF pages: the ECU goes quiet mid-sweep.
        val partial = SimulatedCar.gen2WithStoredFault.filterKeys { !it.startsWith("21C") || it == "21CED0CF" }
        val (_, result) = read(partial)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
        assertEquals(5, result.notes.count { it.startsWith("INF 21C") })
    }

    /**
     * The case the project is waiting for and cannot manufacture: a car with more than one stored
     * fault. Page-to-DTC assignment has never been observed, so the sub-code must not be attached
     * to a code on a guess. This pins that the data still reaches the capture intact, which is
     * what makes such a car worth having.
     */
    @Test
    fun aSecondFaultedCarStillYieldsUsableEvidence() {
        val twoFaults =
            SimulatedCar.gen2WithStoredFault +
                mapOf("13B0" to "53020571 0AA6".replace(" ", ""))
        val (_, result) = read(twoFaults)

        assertEquals(
            listOf("P0571", "P0AA6"),
            result.groups
                .first()
                .codes
                .map { it.code },
        )
        // One page populated, one sub-code decoded. Which DTC owns it is unknown, and the app
        // must not pretend otherwise, but the bytes are all preserved for later analysis.
        assertEquals(listOf(115), result.infCodes.map { it.code })
        assertTrue(result.rawResponses.getValue("7E2/21C7").contains("61C7"))
        assertTrue(result.rawResponses.containsKey("7E2/21C6"))
        assertTrue(result.rawResponses.containsKey("7E2/21CA"))
    }
}
