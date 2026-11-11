package org.betsy

import org.betsy.detect.VehicleDetector
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmException
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.transport.ElmTransport
import org.betsy.transport.SimulatedCar
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDetectorTest {
    private class ProbeTransport(
        val ce: String,
        val failCe: Boolean = false,
    ) : ElmTransport {
        override var readTimeoutMs = 2500
        private var header = ""
        val sent = mutableListOf<String>()
        private val combined = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")

        override suspend fun send(cmd: String): String {
            sent += "$header/$cmd"
            if (cmd.startsWith("ATSH")) {
                header = cmd.removePrefix("ATSH")
                return "OK"
            }
            if (header == "7E3" && cmd == "21CE") {
                if (failCe) throw TransportException("probe timeout")
                return ce
            }
            if (header == "7E3" && cmd == "21D0") return "61" + combined.substring(66, 126)
            return "7F${cmd.take(2)}12"
        }

        override fun close() {}
    }

    private val ce = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF").take(66)

    @Test
    fun completeGen2ProbeUsesTheExistingRequestsAndReportedCount() {
        for (raw in listOf(ce, "021" + ce + "000000")) {
            val t = ProbeTransport(raw)
            val info = awaitBlocking { VehicleDetector.detect(ElmSession(t)) }
            assertEquals(VehicleModel.GEN2, info.model)
            assertEquals(14, info.blockCount)
            assertTrue(info.supported)
            assertEquals(1, t.sent.count { it == "7E3/21CE" })
            assertEquals(1, t.sent.count { it == "7E3/21D0" })
            assertEquals(5, t.sent.size)
        }
    }

    @Test
    fun anEmbeddedTagDoesNotIdentifyGen2() {
        val t = ProbeTransport("6200" + ce)
        assertThrows(ElmException::class.java) { awaitBlocking { VehicleDetector.detect(ElmSession(t)) } }
        assertFalse(t.sent.contains("7E3/21D0"))
    }

    @Test
    fun truncatedGen2ProbeCannotEnableLiveDataOrFallThroughToAnotherLayout() {
        for (raw in listOf("61CE", ce.dropLast(2), "022" + ce, "002" + ce)) {
            val t = ProbeTransport(raw)
            val errorType = if (raw.startsWith("022")) ElmException::class.java else NoDataException::class.java
            assertThrows(errorType) { awaitBlocking { VehicleDetector.detect(ElmSession(t)) } }
            assertFalse(t.sent.contains("7E2/21CE"))
        }
    }

    @Test
    fun transportFailureIsNotEvidenceForAnotherVehicleLayout() {
        val t = ProbeTransport(ce, failCe = true)
        assertThrows(TransportException::class.java) { awaitBlocking { VehicleDetector.detect(ElmSession(t)) } }
        assertFalse(t.sent.contains("7E2/21CE"))
    }
}
