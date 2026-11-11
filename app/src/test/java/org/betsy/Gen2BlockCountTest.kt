package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.detect.VehicleDetector
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.transport.ReplayTransport
import org.betsy.transport.SimulatedCar
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class Gen2BlockCountTest {
    private val recordedD0 by lazy { SimulatedCar.gen2WithStoredFault.getValue("21D0") }

    @Test
    fun recordedCountDoesNotDependOnResistanceMagnitudes() {
        assertEquals(14, Gen2Decoder.readBlockCount(recordedD0))
        val changedIr = recordedD0.substring(0, 34) + "00FF".repeat(7)
        assertEquals(14, Gen2Decoder.readBlockCount(changedIr))
        // Synthetic boundary of the implemented 20-block field layout.
        val fullLayout = "61D014" + "00".repeat(14) + "19".repeat(20)
        assertEquals(20, Gen2Decoder.readBlockCount(fullLayout))
    }

    @Test
    fun truncatedD0CannotEstablishABlockCount() {
        for (response in listOf("61D00E", recordedD0.dropLast(2))) {
            assertThrows(NoDataException::class.java) { Gen2Decoder.readBlockCount(response) }
        }
    }

    @Test
    fun invalidOrUnsupportedCountsAreNotInferredFromTheRemainingBytes() {
        for (raw in listOf("00", "15", "FF")) {
            val response = recordedD0.replaceRange(4, 6, raw)
            assertThrows(NoDataException::class.java) { Gen2Decoder.readBlockCount(response) }
        }
    }

    @Test
    fun detectionRejectsAnInvalidCounterRatherThanScanningResistanceBytes() {
        val script = SimulatedCar.gen2Healthy + ("21D0" to recordedD0.replaceRange(4, 6, "00"))
        val session = ElmSession(ReplayTransport(script))
        assertThrows(NoDataException::class.java) { awaitBlocking { VehicleDetector.detect(session) } }
    }

    @Test
    fun detectionRetainsTheReportedCountInsteadOfInventingTenBlocks() {
        // Synthetic three-block payload checks counter handling, not support for a new car model.
        val d0 = "61D003" + "00".repeat(14) + "19".repeat(3)
        val ce = "61CE788000" + "85A0".repeat(3)
        val script = SimulatedCar.gen2Healthy + mapOf("21D0" to d0, "21CE" to ce)
        val info = awaitBlocking { VehicleDetector.detect(ElmSession(ReplayTransport(script))) }
        assertEquals(3, info.blockCount)
        assertFalse(info.badAdapter)
    }

    @Test
    fun standaloneCountMismatchPreservesPreviousReadings() {
        val model = BatteryModel(blockCount = 13, internalResistance = listOf(23))
        assertThrows(NoDataException::class.java) { Gen2Decoder.decodeD0(recordedD0, model) }
        assertEquals(listOf(23), model.internalResistance)
    }

    @Test
    fun combinedCountMismatchPreservesPreviousReadings() {
        val recorded = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")
        val response = recorded.replaceRange(68, 70, "0D")
        val model = BatteryModel(blockCount = 14, soc = 17f, internalResistance = listOf(23))
        assertThrows(NoDataException::class.java) { Gen2Decoder.decodeCombined(response, model, false) }
        assertEquals(17f, model.soc, 0f)
        assertEquals(listOf(23), model.internalResistance)
    }
}
