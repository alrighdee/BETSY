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
import org.json.JSONObject
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
        )

    private fun elmFrames(body: String): String =
        "\r\n%03X\r\n".format(body.length / 2) +
            body
                .chunked(14)
                .mapIndexed { index, chunk ->
                    "%X: %s".format(index, chunk.chunked(2).joinToString(" ").lowercase())
                }.joinToString("\r\n") + "\r\n"

    @Test
    fun successfulEnhancedAndInfRepliesRemainVerbatimInCaptures() {
        val hybrid = elmFrames("53010AA6")
        val engine = "\r\n53 01 05 71\r\n"
        val battery = "\r\n53 00\r\n"
        val inf = elmFrames("61C6" + payload(29 to 2, 30 to 0x64))
        val t =
            FakeTransport(
                gen2Base(mapOf("1380" to battery, "21C6" to inf)),
                byHeader = mapOf("7E2/13B0" to hybrid, "7E0/13B0" to engine),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.enhancedDtcReadsComplete)
        assertEquals(listOf("P0AA6", "P0571"), result.groups.flatMap { it.codes }.map { it.code })
        assertEquals(612, result.infCodes.single().code)
        assertEquals(5, result.infTablesResponded)
        val expected = mapOf("7E2/13B0" to hybrid, "7E0/13B0" to engine, "7E3/1380" to battery, "7E2/21C6" to inf)
        val capture = CaptureData.from(result, vehicleInfo(VehicleModel.GEN2), "test", "test", "test")
        val exported = JSONObject(capture.toJson()).getJSONObject("raw")
        for ((key, raw) in expected) {
            assertEquals(key, raw, result.rawResponses.getValue(key))
            assertEquals(key, raw, capture.raw.getValue(key))
            assertEquals(key, raw, exported.getString(key))
        }
    }

    @Test
    fun malformedEnhancedAndInfRepliesKeepOriginalFormattingBeforeDecodeFails() {
        val dtc = "\r\n53 02 0a a6\r\n"
        val inf = elmFrames("61C7" + payload(29 to 0, 30 to 115).dropLast(2))
        val t =
            FakeTransport(
                gen2Base(mapOf("13B0" to dtc, "1380" to dtc, "21C7" to inf, "21CA" to "61CA" + payload(29 to 0, 30 to 115))),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.enhancedDtcReadsComplete)
        assertTrue(result.groups.isEmpty())
        for (key in listOf("7E2/13B0", "7E0/13B0", "7E3/1380")) {
            assertEquals(key, dtc, result.rawResponses.getValue(key))
        }
        assertEquals(inf, result.rawResponses.getValue("7E2/21C7"))
        assertEquals(4, result.infTablesResponded)
        assertEquals(115, result.infCodes.single().code)
        assertTrue(result.notes.any { it.contains("13B0") && it.contains("truncated") })
        assertTrue(result.notes.any { it.contains("21C7") && it.contains("truncated") })
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun captureExportsEnhancedCompletionIndependentlyOfFoundDtcs() {
        for (complete in listOf(false, true)) {
            for (fault in listOf(false, true)) {
                val t =
                    FakeTransport(
                        gen2Base(),
                        byHeader =
                            mapOf(
                                "7E2/13B0" to if (fault) "53010AA6" else "5300",
                                "7E3/1380" to if (complete) "5300" else "NO DATA",
                            ),
                    )
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                val capture = CaptureData.from(result, vehicleInfo(VehicleModel.GEN2), "test", "test", "test")
                val json = JSONObject(capture.toJson())
                assertEquals(complete, json.getBoolean("enhancedDtcReadsComplete"))
                assertEquals(fault, json.getBoolean("hasStoredDtcs"))
                if (!complete) assertEquals("NO DATA", json.getJSONObject("raw").getString("7E3/1380"))
                assertEquals(result.notes, capture.notes)
            }
        }
    }

    @Test
    fun captureExportsSuccessfullyDecodedInfPageCountIndependentlyOfValues() {
        for (failed in listOf(0, 1, 5)) {
            for (withValue in listOf(false, true)) {
                val replies = mutableMapOf("13B0" to "53010AA6")
                if (withValue) replies["21C6"] = "61C6" + payload(29 to 2, 30 to 0x64)
                for (cmd in listOf("21CA", "21C9", "21C8", "21C7", "21C6").take(failed)) replies[cmd] = "NO DATA"
                val result = awaitBlocking { reader(FakeTransport(gen2Base(replies)), VehicleModel.GEN2).read() }
                val capture = CaptureData.from(result, vehicleInfo(VehicleModel.GEN2), "test", "test", "test")
                val json = JSONObject(capture.toJson())
                assertEquals(5 - failed, json.getInt("infTablesResponded"))
                assertEquals(if (withValue && failed < 5) 1 else 0, json.getJSONArray("codes").length())
                assertTrue(json.getBoolean("hasStoredDtcs"))
                assertTrue(json.getBoolean("enhancedDtcReadsComplete"))
                assertEquals(result.rawResponses, capture.raw)
                assertEquals(result.notes, capture.notes)
            }
        }
    }

    @Test
    fun enhancedDtcCompletenessRequiresEveryGen2EcuToDecode() {
        for (key in listOf("7E2/13B0", "7E3/1380", "7E0/13B0")) {
            for (raw in listOf("7F1312", "NO DATA", "53010A", "62005300")) {
                val t = FakeTransport(gen2Base(), byHeader = mapOf(key to raw))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertTrue(result.groups.isEmpty())
                assertFalse(key, result.enhancedDtcReadsComplete)
                assertTrue(result.notes.any { it.contains("Toyota enhanced DTC read incomplete") })
                assertTrue(t.commands.contains("21CA"))
            }
        }
    }

    @Test
    fun unconfirmedHeaderCannotCreateAConfirmedEmptyDtcResult() {
        for (header in listOf("7E2", "7E3", "7E0")) {
            for (raw in listOf("NOT OK", "CAN ERROR\rOK")) {
                val t = FakeTransport(gen2Base(mapOf("ATSH$header" to raw)))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertFalse("$header: $raw", result.enhancedDtcReadsComplete)
                assertTrue(result.notes.any { it.contains("DTC read on $header") && it.contains(raw.trim()) })
                assertFalse(result.rawResponses.containsKey("$header/${if (header == "7E3") "1380" else "13B0"}"))
                assertTrue(result.notes.any { it.contains("Toyota enhanced DTC read incomplete") })
            }
        }
    }

    @Test
    fun enhancedDtcCompletenessIncludesHeaderAndTransportFailures() {
        for (cmd in listOf("ATSH7E2", "ATSH7E3", "ATSH7E0", "13B0", "1380")) {
            val t = FakeTransport(gen2Base(), throwOn = mapOf(cmd to TransportException("timeout")))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertFalse(cmd, result.enhancedDtcReadsComplete)
            assertTrue(result.notes.any { it.contains("Toyota enhanced DTC read incomplete") })
        }
    }

    @Test
    fun enhancedDtcCompletenessDoesNotDependOnSupplementalFailuresOrFoundCodes() {
        for (dtc in listOf("5300", "53010AA6")) {
            val t =
                FakeTransport(
                    gen2Base(mapOf("03" to "7EA 03 7F 03 11", "020000" to "NO DATA", "21C6" to "NO DATA")),
                    byHeader = mapOf("7E2/13B0" to dtc),
                )
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertTrue(result.enhancedDtcReadsComplete)
            assertTrue(result.notes.isNotEmpty())
            assertFalse(result.notes.any { it.contains("Toyota enhanced DTC read incomplete") })
            assertEquals(dtc != "5300", result.hasStoredDtcs)
        }
    }

    @Test
    fun duplicateEcuDtcWordsRetainTheirSourcesAndOnlyOneHybridInfExplanation() {
        val t =
            FakeTransport(
                gen2Base(mapOf("13B0" to "53010AA6", "1380" to "53010AA6", "21C6" to "61C6" + payload(29 to 2, 30 to 0x64))),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.enhancedDtcReadsComplete)
        assertEquals(setOf(DtcSource.ENGINE, DtcSource.HYBRID_CONTROL, DtcSource.BATTERY_CONTROL), result.groups.map { it.source }.toSet())
        assertEquals(3, result.groups.flatMap { it.codes }.size)
        val text =
            org.betsy.ui.DtcTextFormatter
                .formatGroups(result.groups, result.infResolutions)
        assertEquals(1, text.lines().count { it == "  P0AA6-612" })
        assertEquals("61C6" + payload(29 to 2, 30 to 0x64), result.rawResponses.getValue("7E2/21C6"))
    }

    @Test
    fun incompleteDtcReadRetainsConfirmedCodesAndExportsTheWarning() {
        val t =
            FakeTransport(
                gen2Base(),
                byHeader = mapOf("7E2/13B0" to "53010AA6", "7E3/1380" to "7F1312"),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.enhancedDtcReadsComplete)
        assertTrue(result.hasStoredDtcs)
        assertEquals(listOf("P0AA6"), result.groups.flatMap { it.codes }.map { it.code })
        val capture = CaptureData.from(result, vehicleInfo(VehicleModel.GEN2), "test", "test", "test")
        assertTrue(capture.notes.any { it.contains("Toyota enhanced DTC read incomplete") })
        assertEquals(result.rawResponses, capture.raw)
    }

    @Test
    fun gen2ZeroDtcWordsDoNotCreateFaultGroupsButRawRepliesRemain() {
        val t = FakeTransport(gen2Base(mapOf("13B0" to "53010000", "1380" to "53010000")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertTrue(result.groups.isEmpty())
        assertFalse(result.hasStoredDtcs)
        for (key in listOf("7E2/13B0", "7E3/1380", "7E0/13B0")) {
            assertEquals("53010000", result.rawResponses.getValue(key))
        }
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun gen2KeepsCodesBeforeTheZeroMarkerWithoutReadingPastIt() {
        val raw = "5303000100000571"
        val t = FakeTransport(gen2Base(), byHeader = mapOf("7E2/13B0" to raw))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        val group = result.groups.single()
        assertEquals(listOf("P0001"), group.codes.map { it.code })
        assertEquals(raw, result.rawResponses.getValue("7E2/13B0"))
    }

    @Test
    fun gen2WrongOrUnframedDtcResponseIsLoggedAndDoesNotCreateCodes() {
        for (raw in listOf("620053013000", "00253013000", "00653013000")) {
            val t = FakeTransport(gen2Base(), byHeader = mapOf("7E2/13B0" to raw))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(raw, result.rawResponses.getValue("7E2/13B0").trim())
            assertTrue(result.notes.any { it.contains("13B0") })
            assertFalse(result.groups.any { it.source == DtcSource.HYBRID_CONTROL })
            assertTrue(t.commands.contains("1380"))
            assertTrue(t.commands.contains("21CA"))
        }
    }

    @Test
    fun gen2TruncatedInfPageRetainsRawReportsFailureAndContinues() {
        val body = payload(29 to 0x00, 30 to 0x73)
        for (raw in listOf("61C7" + body.take(40), "03261C7" + body.take(62), "61C7" + body.take(94))) {
            val t = FakeTransport(gen2Base(mapOf("21C7" to raw, "21CA" to "61CA" + body)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(raw, result.rawResponses.getValue("7E2/21C7").trim())
            assertEquals(4, result.infTablesResponded)
            assertTrue(result.notes.any { it.contains("21C7") && it.contains("truncated", ignoreCase = true) })
            assertEquals(listOf(0xCA), result.infCodes.map { it.table })
            assertEquals(115, result.infCodes.single().code)
        }
    }

    @Test
    fun gen2UnknownInfValueIsRetainedWithoutAnInventedMeaning() {
        val raw = "61C7" + payload(29 to 0xFF, 30 to 0xFF)
        val t = FakeTransport(gen2Base(mapOf("21C7" to raw)))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(65535, result.infCodes.single().code)
        assertEquals(raw, result.rawResponses.getValue("7E2/21C7").trim())
        assertEquals(listOf(InfMeaning.Resolution.Unresolved(65535)), result.infResolutions)
    }

    @Test
    fun gen2TruncatedDtcListRetainsRawAndReportsFailure() {
        for (raw in listOf("53", "5302", "53033000")) {
            val t = FakeTransport(gen2Base(), byHeader = mapOf("7E2/13B0" to raw))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(raw, result.rawResponses.getValue("7E2/13B0").trim())
            assertTrue(result.notes.any { it.contains("13B0") && it.contains("truncated", ignoreCase = true) })
            assertFalse(result.groups.any { it.source == DtcSource.HYBRID_CONTROL })
            assertTrue(t.commands.contains("1380"))
            assertTrue(t.commands.contains("21C6"))
        }
    }

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

    @Test
    fun livenessRejectsEveryTruncatedMode01MaskAndKeepsRaw() {
        val complete = "4100981A8013"
        for (length in complete.indices) {
            val raw = complete.take(length)
            val t = FakeTransport(gen2Base(mapOf("0100" to raw)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertFalse("truncated $raw", result.liveness!!.responding)
            assertTrue(result.liveness!!.detail.startsWith("Unexpected response:"))
            assertEquals(raw, result.rawResponses.getValue("7E2/0100"))
            assertEquals(1, t.commands.count { it == "0100" })
        }
    }

    @Test
    fun livenessRejectsWrongPidAndIncompleteOrUnrelatedRefusals() {
        for (raw in listOf("410100000000", "4120981A8013", "410C0000", "7F", "7F01", "7F011", "7F0211", "7F2211")) {
            val t = FakeTransport(gen2Base(mapOf("0100" to raw)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertFalse(raw, result.liveness!!.responding)
            assertEquals(null, result.liveness!!.negativeResponse)
            assertTrue(result.liveness!!.detail.startsWith("Unexpected response:"))
            assertEquals(raw, result.rawResponses.getValue("7E2/0100"))
        }
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
    fun freezePidStatusTextCannotSupplyAMissingValueOrHideBehindValidData() {
        for (status in listOf("STOPPED", "BUFFER FULL", "ERROR", "NO DATA", "CAN ERROR", "BUS ERROR", "UNABLE TO CONNECT", "?")) {
            for (prefix in listOf("420500", "42050079")) {
                val raw = "$prefix\r$status"
                val t = FakeTransport(gen2Base(mapOf("020500" to raw)))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertEquals(raw, result.rawResponses.getValue("7E2/020500"))
                assertTrue(raw, result.notes.any { it.contains("020500 on 7E2") && it.contains(status) })
                assertTrue(t.commands.contains("020C00"))
                assertTrue(t.commands.contains("21CA"))
            }
        }
    }

    @Test
    fun freezeMaskWithAdapterErrorCannotStartPidReads() {
        val raw = "4200007E1F88\rNO DATA"
        val t = FakeTransport(gen2Base(mapOf("020000" to raw)))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(raw, result.rawResponses.getValue("7E2/020000"))
        assertTrue(result.notes.any { it.contains("020000") && it.contains("NO DATA") })
        assertFalse(t.commands.contains("020500"))
        assertFalse(t.commands.contains("020001"))
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun genericDtcStatusTextIsNotHiddenByAPositiveFrame() {
        for ((cmd, service) in listOf("03" to "43", "07" to "47")) {
            for (status in listOf("STOPPED", "BUFFER FULL", "ERROR", "NO DATA", "?")) {
                val raw = "7EA 04 $service 01 0A A6\r$status"
                val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf(cmd to raw))), VehicleModel.GEN2).read() }
                assertTrue(result.storedGenericDtcs.isEmpty())
                assertTrue(result.pendingGenericDtcs.isEmpty())
                assertTrue(result.notes.any { it.contains("Generic DTCs ($cmd)") && it.contains(status) })
                assertEquals(raw, result.rawResponses.getValue("7E2/$cmd"))
            }
        }
    }

    @Test
    fun stoppedOrOverflowedInfPagesAreNotCountedAsSuccessful() {
        for (status in listOf("STOPPED", "BUFFER FULL", "ERROR", "stopped")) {
            val raw = "61C7" + payload(29 to 0, 30 to 115) + "\r$status"
            val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("21C7" to raw))), VehicleModel.GEN2).read() }
            assertTrue(result.infCodes.isEmpty())
            assertEquals(4, result.infTablesResponded)
            assertTrue(result.notes.any { it.contains("21C7") && it.contains(status) })
            assertEquals(raw, result.rawResponses.getValue("7E2/21C7"))
        }
    }

    @Test
    fun livenessRejectsStatusTextAfterACompleteMask() {
        for (status in listOf("BUFFER FULL", "ERROR", "buffer full")) {
            val raw = "4100981A8013\r$status"
            val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("0100" to raw))), VehicleModel.GEN2).read() }
            assertFalse(result.liveness!!.responding)
            assertTrue(result.liveness!!.detail.contains(status))
            assertEquals(raw, result.rawResponses.getValue("7E2/0100"))
        }
    }

    @Test
    fun freezePidWithDanglingNibbleKeepsRawAndReportsFailure() {
        val raw = "42050079F"
        val t = FakeTransport(gen2Base(mapOf("020500" to raw)))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(raw, result.rawResponses.getValue("7E2/020500"))
        assertTrue(result.notes.any { it.contains("020500") && it.contains("incomplete hex byte") })
        assertTrue(t.commands.contains("020C00"))
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun freezeMaskWithDanglingNibbleStopsDiscovery() {
        val raw = "4200007E1F8803F"
        val t = FakeTransport(gen2Base(mapOf("020000" to raw)))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(raw, result.rawResponses.getValue("7E2/020000"))
        assertTrue(result.notes.any { it.contains("020000") && it.contains("incomplete hex byte") })
        assertFalse(t.commands.contains("020500"))
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun genericFramesAndLivenessCannotIgnoreDanglingHexDigits() {
        for ((cmd, raw) in listOf("03" to "7EA0443010AA6F", "07" to "7EA024700F")) {
            val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf(cmd to raw))), VehicleModel.GEN2).read() }
            assertEquals(raw, result.rawResponses.getValue("7E2/$cmd"))
            assertTrue(result.storedGenericDtcs.isEmpty())
            assertTrue(result.pendingGenericDtcs.isEmpty())
            assertTrue(result.notes.any { it.contains("Generic DTCs ($cmd)") && it.contains("incomplete hex byte") })
        }
        for (raw in listOf("4100981A8013F", "7F0111F")) {
            val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("0100" to raw))), VehicleModel.GEN2).read() }
            assertFalse(result.liveness!!.responding)
            assertEquals(raw, result.rawResponses.getValue("7E2/0100"))
        }
    }

    @Test
    fun freezePidsRequireTheirCompleteValueAndRetainTruncatedRaw() {
        val widths =
            mapOf(
                "02" to 2,
                "04" to 1,
                "05" to 1,
                "0C" to 2,
                "0D" to 1,
                "0F" to 1,
                "11" to 1,
                "1F" to 2,
                "31" to 2,
                "42" to 2,
                "46" to 1,
                "4E" to 2,
            )
        for ((pid, width) in widths) {
            val cmd = "02${pid}00"
            for (bytes in 0 until width) {
                val raw = "42${pid}00" + "80".repeat(bytes)
                val t = FakeTransport(gen2Base(mapOf(cmd to raw)))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertEquals(raw, result.rawResponses.getValue("7E2/$cmd"))
                assertTrue(result.notes.any { it.contains("$cmd on 7E2") && it.contains("truncated") })
                assertTrue(t.commands.contains("21CA"))
            }
            val raw = "42${pid}00" + "80".repeat(width)
            val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf(cmd to raw))), VehicleModel.GEN2).read() }
            assertEquals(raw, result.rawResponses.getValue("7E2/$cmd"))
            assertFalse(result.notes.any { it.contains("$cmd on 7E2") })
        }
    }

    @Test
    fun freezePidRefusalsAndAdapterErrorsKeepRawAndDoNotStopOtherPids() {
        for (failed in listOf("7F0212", "NO DATA", "CAN ERROR", "?", "4205017F", "420500")) {
            val t = FakeTransport(gen2Base(mapOf("020500" to failed, "020C00" to "420C000000")))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(failed, result.rawResponses.getValue("7E2/020500"))
            assertEquals("420C000000", result.rawResponses.getValue("7E2/020C00"))
            assertTrue(result.notes.any { it.contains("020500 on 7E2") })
            if (failed == "7F0212") {
                assertTrue(result.notes.any { it.contains("020500") && it.contains("sub-function not supported") })
            }
            assertTrue(t.commands.contains("21CA"))
        }
    }

    @Test
    fun freezePidTransportErrorIsLoggedWithoutCopyingThePreviousReply() {
        val t =
            FakeTransport(
                gen2Base(mapOf("020400" to "42040080", "020C00" to "420C000000")),
                throwOn = mapOf("020500" to TransportException("socket timed out")),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.rawResponses.containsKey("7E2/020500"))
        assertEquals("42040080", result.rawResponses.getValue("7E2/020400"))
        assertEquals("420C000000", result.rawResponses.getValue("7E2/020C00"))
        assertTrue(result.notes.any { it.contains("020500 on 7E2") && it.contains("socket timed out") })
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun freezeMaskFailureIsRetainedAndStopsFrameDiscoveryButNotInfReads() {
        for (failed in listOf("7F0212", "NO DATA", "4200017E1F8803", "4200007E")) {
            val t = FakeTransport(gen2Base(mapOf("020000" to failed)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(failed, result.rawResponses.getValue("7E2/020000"))
            assertTrue(result.notes.any { it.contains("020000 on 7E2") })
            assertFalse(t.commands.contains("020500"))
            assertFalse(t.commands.contains("020001"))
            assertTrue(t.commands.contains("21CA"))
        }
    }

    @Test
    fun freezeMaskTransportErrorDoesNotCreateARawReply() {
        val t = FakeTransport(gen2Base(), throwOn = mapOf("020000" to TransportException("mask timeout")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.rawResponses.containsKey("7E2/020000"))
        assertTrue(result.notes.any { it.contains("020000 on 7E2") && it.contains("mask timeout") })
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun rejectedAth1SkipsGenericRequestsAndStillAttemptsAth0() {
        for (raw in listOf("?", "NO DATA", "NOT OK", "")) {
            val t = FakeTransport(gen2Base(mapOf("ATH1" to raw)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertFalse(t.commands.contains("03"))
            assertFalse(t.commands.contains("07"))
            assertFalse(result.rawResponses.containsKey("7E2/03"))
            assertFalse(result.rawResponses.containsKey("7E2/07"))
            assertTrue(result.notes.any { it.contains("Generic DTCs (03)") && it.contains("ATH1") })
            assertTrue(result.notes.any { it.contains("Generic DTCs (07)") && it.contains("ATH1") })
            assertEquals(2, t.commands.count { it == "ATH0" })
            assertTrue(t.commands.contains("21CA"))
        }
    }

    @Test
    fun failedHeaderRestorationSkipsFreezeAndInfButRetainsReadDtcReplies() {
        val dtc = "7EA 04 43 01 0A A6"
        val t = FakeTransport(gen2Base(mapOf("ATH0" to "?", "03" to dtc)))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
        assertEquals(dtc, result.rawResponses.getValue("7E2/03"))
        assertTrue(result.enhancedDtcReadsComplete)
        assertFalse(t.commands.any { it.startsWith("02") || it.startsWith("21C") })
        assertEquals(0, result.infTablesResponded)
        assertTrue(result.infCodes.isEmpty())
        assertFalse(result.rawResponses.keys.any { it.startsWith("7E2/02") || it.startsWith("7E2/21C") })
        assertTrue(result.notes.any { it.contains("Freeze frame") && it.contains("ATH0") })
        assertTrue(result.notes.any { it.contains("INF") && it.contains("ATH0") })
    }

    @Test
    fun laterConfirmedRestorationAllowsFreezeAndInfInTheSameSweep() {
        val base = FakeTransport(gen2Base())
        var restorations = 0
        val transport =
            object : ElmTransport {
                override var readTimeoutMs = 2500

                override suspend fun send(cmd: String): String {
                    val response = base.send(cmd)
                    return if (cmd == "ATH0" && ++restorations == 1) "?" else response
                }

                override fun close() {}
            }
        val result = awaitBlocking { reader(transport, VehicleModel.GEN2).read() }
        assertEquals(2, restorations)
        assertTrue(base.commands.contains("020000"))
        assertTrue(base.commands.contains("21CA"))
        assertEquals(5, result.infTablesResponded)
        assertTrue(result.notes.any { it.contains("restoring ATH0") })
    }

    @Test
    fun rejectedAth0IsReportedWithoutDiscardingTheDtcReply() {
        for (raw in listOf("?", "NO DATA", "NOT OK", "")) {
            val dtc = "7EA 04 43 01 0A A6"
            val t = FakeTransport(gen2Base(mapOf("ATH0" to raw, "03" to dtc)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
            assertEquals(dtc, result.rawResponses.getValue("7E2/03"))
            assertTrue(result.notes.any { it.contains("Generic DTCs (03)") && it.contains("restoring ATH0") })
            assertTrue(result.notes.any { it.contains("Generic DTCs (07)") && it.contains("restoring ATH0") })
        }
    }

    @Test
    fun athFailuresRetainBothThePrimaryAndCleanupErrors() {
        val t =
            FakeTransport(
                gen2Base(),
                throwOn =
                    mapOf(
                        "ATH1" to TransportException("header enable timeout"),
                        "ATH0" to TransportException("header restore timeout"),
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(t.commands.contains("03"))
        assertFalse(t.commands.contains("07"))
        assertEquals(2, t.commands.count { it == "ATH0" })
        assertTrue(result.notes.any { it.contains("Generic DTCs (03)") && it.contains("header enable timeout") })
        assertTrue(result.notes.any { it.contains("Generic DTCs (03)") && it.contains("header restore timeout") })
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

    @Test
    fun generic03CannotReadACodeFromCanPadding() {
        val raw = "7EA 02 43 01 0A A6 00 00 00"
        val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("03" to raw))), VehicleModel.GEN2).read() }
        assertTrue(result.storedGenericDtcs.isEmpty())
        assertTrue(result.notes.any { it.contains("Generic DTCs (03)") && it.contains("truncated") })
        assertEquals(raw, result.rawResponses.getValue("7E2/03"))
    }

    @Test
    fun generic03RejectsInvalidPciAndTruncatedFrame() {
        for (raw in listOf("7EA 00 43 00", "7EA 08 43 00", "7EA 21 43 00", "7EA 30 43 00", "7EA 04 43 00")) {
            val t = FakeTransport(gen2Base(mapOf("03" to raw)))
            val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
            assertTrue(result.storedGenericDtcs.isEmpty())
            assertTrue(result.notes.any { it.contains("Generic DTCs (03)") })
            assertEquals(raw, result.rawResponses.getValue("7E2/03"))
            assertTrue(t.commands.contains("21CA"))
            assertTrue(t.commands.count { it == "ATH0" } >= 2)
        }
    }

    @Test
    fun generic03DoesNotMistakeAnotherEcusPayloadForTheResponder() {
        val other = "7E80443017EA0"
        val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("03" to other))), VehicleModel.GEN2).read() }
        assertTrue(result.storedGenericDtcs.isEmpty())
        assertTrue(result.notes.any { it.contains("no 7EA response line") })
    }

    @Test
    fun generic03SelectsTheActual7eaLineAndIgnoresItsPadding() {
        val raw = "7E80443017EA0\r7EA0443010AA6000000"
        val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("03" to raw))), VehicleModel.GEN2).read() }
        assertEquals(listOf("P0AA6"), result.storedGenericDtcs.map { it.code })
        assertFalse(result.notes.any { it.contains("Generic DTCs (03)") })
    }

    @Test
    fun genericDtcReadsDoNotSilentlyDiscardAdditionalResponderLines() {
        for ((cmd, service) in listOf("03" to "43", "07" to "47")) {
            val first = "7EA 04 $service 01 0A A6"
            for (second in listOf("7EA 04 $service 01 30 00", first, "7EA 03 7F $cmd 11", "7EA 21 00 00")) {
                val raw = "$first\r7E8 02 43 00\r$second"
                val t = FakeTransport(gen2Base(mapOf(cmd to raw)))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertTrue(result.storedGenericDtcs.isEmpty())
                assertTrue(result.pendingGenericDtcs.isEmpty())
                assertTrue(result.notes.any { it.contains("Generic DTCs ($cmd)") && it.contains("multiple 7EA") })
                assertEquals(raw, result.rawResponses.getValue("7E2/$cmd"))
                assertTrue(t.commands.contains("21CA"))
                assertTrue(t.commands.count { it == "ATH0" } >= 2)
            }
        }
    }

    @Test
    fun generic07UsesTheSameSingleFrameBounds() {
        val raw = "7EA 02 47 01 0A A6 00 00 00"
        val result = awaitBlocking { reader(FakeTransport(gen2Base(mapOf("07" to raw))), VehicleModel.GEN2).read() }
        assertTrue(result.pendingGenericDtcs.isEmpty())
        assertTrue(result.notes.any { it.contains("Generic DTCs (07)") && it.contains("truncated") })
        assertEquals(raw, result.rawResponses.getValue("7E2/07"))
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
    fun partialRepliesAreRetainedButNeverDecodedAsSuccessfulDiagnostics() {
        val failures =
            mapOf(
                "0100" to "4100981A8013",
                "1380" to "53010A80",
                "03" to "7EA0443010AA6",
                "020500" to "42050079",
                "21C7" to "61C7" + payload(29 to 0, 30 to 115),
            )
        val t =
            FakeTransport(
                gen2Base(),
                throwOn = failures.mapValues { TransportException("timeout before prompt", it.value) },
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        for ((cmd, raw) in failures) {
            val header = if (cmd == "1380") "7E3" else "7E2"
            assertEquals(raw, result.rawResponses.getValue("$header/$cmd"))
        }
        assertFalse(result.liveness!!.responding)
        assertTrue(result.groups.isEmpty())
        assertTrue(result.storedGenericDtcs.isEmpty())
        assertTrue(result.infCodes.isEmpty())
        assertEquals(4, result.infTablesResponded)
        for (cmd in listOf("1380", "03", "020500", "21C7")) {
            assertTrue(result.notes.any { it.contains(cmd) && it.contains("timeout before prompt") })
        }
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun enhancedDtcFailuresKeepTheirOwnVerbatimRepliesForEveryGen2Ecu() {
        for (key in listOf("7E2/13B0", "7E3/1380", "7E0/13B0")) {
            for (raw in listOf("\r7F 13 12\r", "NO DATA\r", "CAN ERROR\r", "BUS ERROR\r", "UNABLE TO CONNECT\r", "?\r")) {
                val t = FakeTransport(gen2Base(), byHeader = mapOf(key to raw))
                val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
                assertEquals(key, raw, result.rawResponses.getValue(key))
                assertTrue(result.groups.isEmpty())
                assertTrue(result.notes.any { it.contains(key.substringBefore('/')) && it.contains(key.substringAfter('/')) })
                assertTrue(t.commands.contains("21CA"))
            }
        }
    }

    @Test
    fun enhancedDtcTimeoutDoesNotReuseThePreviousEcusReply() {
        val t =
            FakeTransport(
                gen2Base(mapOf("13B0" to "53010AA6")),
                throwOn = mapOf("1380" to TransportException("battery DTC timeout")),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals("53010AA6", result.rawResponses.getValue("7E2/13B0"))
        assertFalse(result.rawResponses.containsKey("7E3/1380"))
        assertTrue(result.notes.any { it.contains("1380") && it.contains("battery DTC timeout") })
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun infTimeoutsDoNotCopyHeaderOrPreviousPageReplies() {
        val valid = "61C7" + payload(29 to 0, 30 to 115)
        val t =
            FakeTransport(
                gen2Base(mapOf("21C7" to valid)),
                throwOn =
                    mapOf(
                        "21C6" to TransportException("first page timeout"),
                        "21C8" to TransportException("later page timeout"),
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertFalse(result.rawResponses.containsKey("7E2/21C6"))
        assertFalse(result.rawResponses.containsKey("7E2/21C8"))
        assertEquals(valid, result.rawResponses.getValue("7E2/21C7"))
        assertEquals(115, result.infCodes.single().code)
        assertEquals(3, result.infTablesResponded)
        assertTrue(result.notes.any { it.contains("21C6") && it.contains("first page timeout") })
        assertTrue(result.notes.any { it.contains("21C8") && it.contains("later page timeout") })
        assertTrue(t.commands.contains("21CA"))
    }

    @Test
    fun infActualRefusalsAndAdapterErrorsStillKeepTheirOwnRawReplies() {
        val t = FakeTransport(gen2Base(mapOf("21C7" to "7F2112", "21C8" to "NO DATA")))
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }
        assertEquals("7F2112", result.rawResponses.getValue("7E2/21C7"))
        assertEquals("NO DATA", result.rawResponses.getValue("7E2/21C8"))
        assertEquals(3, result.infTablesResponded)
        assertTrue(result.notes.any { it.contains("21C7") && it.contains("sub-function not supported") })
        assertTrue(result.notes.any { it.contains("21C8") && it.contains("NO DATA") })
        assertTrue(t.commands.contains("21CA"))
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
        assertTrue(capture.car.contains("14 blocks"))
        assertFalse(capture.car.contains("cells"))
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
    fun documentedParentOnBatteryControlProducesSourceMismatchNote() {
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
     * Engine and hybrid control can legitimately report the same standardized DTC number. An
     * active INF value must not turn that ordinary overlap into a source-mismatch warning.
     */
    @Test
    fun documentedParentOnEngineDoesNotProduceSourceMismatchNote() {
        val t =
            FakeTransport(
                gen2Base(mapOf("21C7" to "61C7" + payload(29 to 0x00, 30 to 0x7B))),
                byHeader =
                    mapOf(
                        "7E2/13B0" to "5300",
                        "7E3/1380" to "5300",
                        "7E0/13B0" to "53010560",
                    ),
            )
        val result = awaitBlocking { reader(t, VehicleModel.GEN2).read() }

        assertEquals(listOf(123), result.infCodes.map { it.code })
        assertTrue(
            result.groups
                .single { it.source == DtcSource.ENGINE }
                .codes
                .any { it.code == "P0560" },
        )
        assertFalse(result.notes.any { it.contains("source mismatch") })
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
