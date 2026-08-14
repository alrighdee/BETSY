package org.betsy

import org.betsy.capture.CaptureData
import org.betsy.decode.InfMeaning
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.dtc.DtcReader
import org.betsy.dtc.DtcSource
import org.betsy.elm.ElmSession
import org.betsy.transport.ElmTransport
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTC/INF read sequencing per PROTOCOL.md §7.1/§7.4.
 */
class DtcReaderTest {
    private class FakeTransport(
        private val responses: Map<String, String>,
        private val throwOn: Map<String, Exception> = emptyMap(),
        /**
         * Per-header overrides, `"<header>/<cmd>"` to response, consulted before [responses]. The
         * same command means different things on different ECUs: `13B0` on 7E2 is the HV ECU's
         * stored mask and on 7E0 the engine's, and a fake that cannot tell them apart cannot test
         * that both are read.
         */
        private val byHeader: Map<String, String> = emptyMap(),
    ) : ElmTransport {
        override var readTimeoutMs: Int = 2500
        val commands = mutableListOf<String>()
        private var header = ""

        override suspend fun send(cmd: String): String {
            commands += cmd
            if (cmd.startsWith("ATSH")) header = cmd.removePrefix("ATSH")
            throwOn[cmd]?.let { throw it }
            return byHeader["$header/$cmd"] ?: responses[cmd] ?: "OK"
        }

        override fun close() {}
    }

    private fun payload(vararg set: Pair<Int, Int>): String {
        val b = ByteArray(48)
        for ((k, v) in set) b[k] = v.toByte()
        return b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun gen2Base(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf(
            "ATSH7E2" to "OK",
            "ATSH7E3" to "OK",
            "ATSH7E0" to "OK",
            "0100" to "4100FFE0FFE0",
            "ATH1" to "OK",
            "ATH0" to "OK",
            "13B0" to "5300",
            "1380" to "5300",
            "03" to "7EA 02 43 00 ",
            "07" to "7EA 02 47 00 ",
            "020000" to "4200007E1F8803",
            "020001" to "7F0212",
            "21C6" to "61C6" + payload(),
            "21C7" to "61C7" + payload(),
            "21C8" to "61C8" + payload(),
            "21C9" to "61C9" + payload(),
            "21CA" to "61CA" + payload(),
        ) + extra

    private fun reader(
        transport: ElmTransport,
        model: VehicleModel,
    ): DtcReader = DtcReader(ElmSession(transport), vehicleInfo(model))

    private fun vehicleInfo(model: VehicleModel): VehicleInfo =
        VehicleInfo(
            model = model,
            supported = true,
            blockCount = 14,
            cellCount = 28,
        )

    @Test
    fun gen2SequenceIsLivenessThenKwpThenGenericThenInf() {
        val t = FakeTransport(gen2Base())
        awaitBlocking { reader(t, VehicleModel.GEN2).read() }
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
                // freeze frame: mask per frame, then the measured PID list, frame 00 only
                // because the fixture declines frame 01
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
            t.commands,
        )
    }

    @Test
    fun livenessPositiveMeansResponding() {
        val t = FakeTransport(gen2Base())
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.liveness!!.responding)
        assertEquals("Responding", result.liveness!!.detail)
        assertEquals("4100FFE0FFE0", result.rawResponses.getValue("7E2/0100").trim())
    }

    @Test
    fun livenessNegativeResponseStillAlive() {
        val t = FakeTransport(gen2Base(mapOf("0100" to "7F0111")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.liveness!!.responding)
        assertTrue(result.liveness!!.detail.contains("service not supported"))
        assertTrue(result.liveness!!.detail.contains("7F"))
    }

    /** One probe, one round trip: 21C6 is read only by the INF sweep, never as a liveness check. */
    @Test
    fun livenessCostsExactlyOneRequest() {
        val t = FakeTransport(gen2Base())
        awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(1, t.commands.count { it == "0100" })
        assertEquals(1, t.commands.count { it == "21C6" })
    }

    @Test
    fun livenessNoDataMeansNotResponding() {
        val t = FakeTransport(gen2Base(mapOf("0100" to "NO DATA")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.liveness!!.responding)
        assertEquals("No response (NO DATA)", result.liveness!!.detail)
    }

    @Test
    fun livenessTimeoutMeansNotResponding() {
        val t =
            FakeTransport(
                gen2Base(),
                throwOn = mapOf("0100" to TransportException("socket timed out")),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.liveness!!.responding)
        assertEquals("No response (timeout)", result.liveness!!.detail)
    }

    @Test
    fun livenessAdapterErrorNotAlive() {
        val t = FakeTransport(gen2Base(mapOf("0100" to "?")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.liveness!!.responding)
        assertTrue(result.liveness!!.detail.startsWith("Adapter error:"))
    }

    @Test
    fun livenessUnexpectedHexNotAlive() {
        val t = FakeTransport(gen2Base(mapOf("0100" to "6200FFE0")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.liveness!!.responding)
        assertTrue(result.liveness!!.detail.startsWith("Unexpected response:"))
    }

    /** The real car's reply, from the an on-car read on-car probe. Guards the `41` classification. */
    @Test
    fun livenessAcceptsTheRealGen2Mode01Reply() {
        val t = FakeTransport(gen2Base(mapOf("0100" to "4100981A8013")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.liveness!!.responding)
        assertEquals("Responding", result.liveness!!.detail)
    }

    @Test
    fun generic03StoresP0aa6From7eaLine() {
        val t =
            FakeTransport(
                gen2Base(
                    mapOf(
                        "13B0" to "5300",
                        "03" to "7EA 04 43 01 0A A6 ",
                    ),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
        assertTrue(result.rawResponses.getValue("7E2/03").contains("7EA"))
        // R3: generic alone does not set hasStoredDtcs
        assertFalse(result.hasStoredDtcs)
    }

    /**
     * A multi-frame `$03` reply carries a 4-nibble first-frame PCI and continues on lines this
     * reader never reads. Decoding the first frame alone would report 2 of 6 stored DTCs as if
     * that were the whole list, so it declines and says so.
     */
    @Test
    fun generic03MultiFrameIsReportedNotTruncated() {
        val t =
            FakeTransport(
                gen2Base(
                    mapOf(
                        "03" to "7EA 10 14 43 06 01 0A A6 \r7EA 21 01 33 02 34 03 35 ",
                    ),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(emptyList<String>(), result.storedGenericDtcs.map { it.code })
        assertTrue(result.notes.any { it.contains("multi-frame") })
        // The bytes still travel, so the reply can be re-read once reassembly exists.
        assertTrue(result.rawResponses.getValue("7E2/03").contains("7EA"))
    }

    @Test
    fun generic03And07ZeroAreEmptyLists() {
        val t = FakeTransport(gen2Base())
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(emptyList<String>(), result.storedGenericDtcs.map { it.code })
        assertEquals(emptyList<String>(), result.pendingGenericDtcs.map { it.code })
    }

    @Test
    fun ath0RestoredAfterGenericEvenOnFailure() {
        val t = FakeTransport(gen2Base(mapOf("03" to "NO DATA")))
        awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        val ath0After03 =
            t.commands.indexOfFirst { it == "03" }.let { i ->
                t.commands.subList(i, t.commands.size).indexOf("ATH0")
            }
        assertTrue(ath0After03 >= 0)
        assertTrue(t.commands.count { it == "ATH0" } >= 2)
    }

    @Test
    fun infTablesRespondedIs5OnHealthy() {
        val t = FakeTransport(gen2Base())
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(5, result.infTablesResponded)
    }

    @Test
    fun infTablesRespondedDropsFailedTables() {
        val t =
            FakeTransport(
                gen2Base(
                    mapOf(
                        "21C8" to "NO DATA",
                    ),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(4, result.infTablesResponded)
    }

    @Test
    fun rawKeysIncludeLivenessAndGeneric() {
        val t = FakeTransport(gen2Base())
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.rawResponses.containsKey("7E2/0100"))
        assertTrue(result.rawResponses.containsKey("7E2/03"))
        assertTrue(result.rawResponses.containsKey("7E2/07"))
        assertTrue(result.rawResponses.containsKey("7E2/13B0"))
    }

    @Test
    fun kwpDtcDoesNotGetCollapsedByCleanGeneric() {
        val t =
            FakeTransport(
                gen2Base(
                    mapOf(
                        "13B0" to "53010A80",
                        "03" to "7EA 02 43 00 ",
                    ),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.hasStoredDtcs)
        assertEquals(listOf("P0A80"), result.groups.flatMap { g -> g.codes.map { it.code } }.distinct())
        assertEquals(emptyList<String>(), result.storedGenericDtcs.map { it.code })
    }

    /**
     * Regression: a U-class communication code stored on the ECM while 7E2 itself is clean.
     *
     * This is the shape of the HEV-fuse experiment, where silencing the HV ECU made the ECM log
     * U0293 in 0.68 s and 7E2 had nothing. An earlier build read only the HV ECU and reported "no
     * DTCs" for a car that had one, which is the worst possible answer: an empty result that looks
     * like a finding. The engine read is unconditional for exactly this reason.
     */
    @Test
    fun ecmOnlyCommunicationCodeIsReportedWhenHvEcuIsClean() {
        val t =
            FakeTransport(
                gen2Base(),
                byHeader = mapOf("7E0/13B0" to "5301C293"),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(listOf("Engine ECU (7E0)"), result.groups.map { it.label })
        val ecm = result.groups.single()
        assertEquals(listOf("U0293"), ecm.codes.map { it.code })
        assertEquals("5301C293", result.rawResponses.getValue("7E0/13B0"))
        // The HV ECU is genuinely clean, and an ECM code must not be attributed to it.
        assertEquals("5300", result.rawResponses.getValue("7E2/13B0"))
        assertTrue(result.hasStoredDtcs)
    }

    @Test
    fun standardGen2KeepsLogicalSourcesAndRetainsExactResolution() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21CA" to "61CA" + payload(29 to 0x02, 30 to 0x64))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "53010AA6",
                        "7E3/1380" to "53010A80",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(
            listOf(DtcSource.HYBRID_CONTROL, DtcSource.BATTERY_CONTROL),
            result.groups.map { it.source },
        )
        val exact = result.infResolutions.single() as InfMeaning.Resolution.Exact
        assertEquals("P0AA6", exact.dtc)
        assertEquals(612, exact.inf)
        assertEquals(listOf(612), result.infCodes.map { it.code })
    }

    @Test
    fun fallbackKeepsTwoLogicalSourcesInsideOnePhysicalSession() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21CA" to "61CA" + payload(29 to 0x02, 30 to 0x64))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "53010AA6",
                        "7E2/1380" to "53010A80",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2_7E2).read() }

        assertEquals(
            listOf(DtcSource.HYBRID_CONTROL, DtcSource.BATTERY_CONTROL),
            result.groups.map { it.source },
        )
        assertEquals(listOf("HV ECU (7E2)", "Battery ECU (7E2)"), result.groups.map { it.label })
        val firstDtc = t.commands.indexOf("13B0")
        val batteryDtc = t.commands.indexOf("1380")
        assertEquals("ATSH7E2", t.commands[firstDtc - 1])
        assertFalse(t.commands.subList(firstDtc + 1, batteryDtc).any { it.startsWith("ATSH") })

        val exact = result.infResolutions.single() as InfMeaning.Resolution.Exact
        assertEquals("P0AA6", exact.dtc)
        assertEquals(612, exact.inf)

        val capture =
            CaptureData.from(
                result = result,
                info = vehicleInfo(VehicleModel.GEN2_7E2),
                elm = "test",
                version = "test",
                build = "test",
            )
        assertEquals(listOf("HV ECU (7E2): P0AA6", "Battery ECU (7E2): P0A80"), capture.dtcs)
        assertEquals(result.rawResponses, capture.raw)
        assertEquals(listOf(612), capture.codes.map { it.code })
    }

    @Test
    fun documentedParentsOutsideHybridSourceProduceVisibleNotes() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21C7" to "61C7" + payload(29 to 0x00, 30 to 0x7B))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "5300",
                        "7E3/1380" to "53020A1F3000",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(InfMeaning.Resolution.Unresolved(123), result.infResolutions.single())
        assertTrue(result.notes.any { it.contains("P0A1F") && it.contains("battery control") })
        assertTrue(result.notes.any { it.contains("P3000") && it.contains("battery control") })
    }

    @Test
    fun readerRetainsSharedAliasResolutionAndEveryRawOccurrence() {
        val t =
            FakeTransport(
                gen2Base(
                    mapOf(
                        "21C7" to "61C7" + payload(29 to 0x02, 30 to 0x64),
                        "21CA" to "61CA" + payload(29 to 0x02, 30 to 0x64),
                    ),
                ),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "530230090AA6",
                        "7E3/1380" to "5300",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(listOf(612, 612), result.infCodes.map { it.code })
        val shared = result.infResolutions.single() as InfMeaning.Resolution.Shared
        assertEquals(linkedSetOf("P3009", "P0AA6"), shared.dtcs)
        assertEquals(612, shared.inf)
    }

    @Test
    fun readerRetainsConflictingInf123AsUnresolved() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21C7" to "61C7" + payload(29 to 0x00, 30 to 0x7B))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "53020A1F3000",
                        "7E3/1380" to "5300",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(InfMeaning.Resolution.Unresolved(123), result.infResolutions.single())
        assertEquals(listOf(123), result.infCodes.map { it.code })
        assertFalse(result.notes.any { it.contains("source mismatch") })
    }

    @Test
    fun unrelatedBatteryCodeDoesNotProduceSourceMismatchNote() {
        val t =
            FakeTransport(
                gen2Base(),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "5300",
                        "7E3/1380" to "53010A80",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertFalse(result.notes.any { it.contains("source mismatch") })
    }

    /**
     * The detector exists to make a wrong source assumption visible. Every other test asserts it
     * stays quiet, which an always-false detector would also satisfy, so one case must prove it
     * can speak.
     */
    @Test
    fun documentedParentOutsideHybridControlProducesSourceMismatchNote() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21C7" to "61C7" + payload(29 to 0x00, 30 to 0x7B))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "5300",
                        "7E3/1380" to "53010A1F",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(listOf(123), result.infCodes.map { it.code })
        assertTrue(result.notes.any { it.contains("source mismatch") && it.contains("P0A1F") })
        // The value stays unresolved: P0A1F was not read from hybrid control, so it is not a
        // candidate parent. The note is the only thing that says so out loud.
        assertEquals(InfMeaning.Resolution.Unresolved(123), result.infResolutions.single())
    }

    /**
     * With no INF value read, nothing could have been mis-attributed. The note would be a warning
     * about a relationship that never existed, shown to the owner and uploaded with the capture.
     */
    @Test
    fun sourceMismatchNoteIsSilentWhenNoInfValueWasRead() {
        val t =
            FakeTransport(
                gen2Base(),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "5300",
                        "7E3/1380" to "53010A1F",
                        "7E0/13B0" to "5300",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertTrue(result.infCodes.isEmpty())
        assertFalse(result.notes.any { it.contains("source mismatch") })
    }

    @Test
    fun gen3DoesNotRunGen2LivenessOrGeneric() {
        val t =
            FakeTransport(
                mapOf(
                    "ATSH7E2" to "OK",
                    "ATSH7E0" to "OK",
                    "0A" to "4A010A80",
                    "13B0" to "530230000A80",
                    "21C6" to "61C6" + payload(),
                    "21C7" to "61C7" + payload(),
                    "21C8" to "61C8" + payload(),
                    "21C9" to "61C9" + payload(),
                    "21CA" to "61CA" + payload(),
                ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN3).read() }
        assertEquals(null, result.liveness)
        assertEquals(emptyList<String>(), result.storedGenericDtcs.map { it.code })
        assertFalse(t.commands.contains("0100"))
        assertFalse(t.commands.contains("ATH1"))
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
