package org.betsy

import org.betsy.decode.Gen2Decoder
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.elm.ElmSession
import org.betsy.elm.NoDataException
import org.betsy.model.BatteryModel
import org.betsy.poll.Poller
import org.betsy.transport.ElmTransport
import org.betsy.transport.SimulatedCar
import org.betsy.transport.TransportException
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PollerTest {
    private val battery = SimulatedCar.gen2WithStoredFault.getValue("21CED0CF")

    private class PollTransport(
        val battery: String,
        val speed: String,
        val failure: TransportException? = null,
    ) : ElmTransport {
        override var readTimeoutMs = 2500
        val sent = mutableListOf<String>()

        override suspend fun send(cmd: String): String {
            sent += cmd
            return when (cmd) {
                "ATSH7E3" -> "OK"
                "21CED0CF" -> battery
                "010C0D" -> {
                    if (failure != null) throw failure
                    speed
                }
                else -> throw AssertionError("unexpected request $cmd")
            }
        }

        override fun close() {}
    }

    private fun poller(t: ElmTransport): Poller = Poller(ElmSession(t), VehicleInfo(VehicleModel.GEN2, supported = true, blockCount = 14))

    @Test
    fun optionalRefusalsAndMalformedSpeedDoNotDiscardBatteryCycles() {
        for (reply in listOf("NO DATA", "7F0111", "7F0112", "7F0122", "410C0FA0", "410C0FA00F0D64")) {
            val t = PollTransport(battery, reply)
            val p = poller(t)
            val m = BatteryModel(rpm = 777, speedMph = 12f)
            val expected = BatteryModel(blockCount = 14, rpm = 777, speedMph = 12f)
            Gen2Decoder.decodeCombined(battery, expected, fifteenBlockVariant = false)
            repeat(4) { awaitBlocking { p.poll(m) } }
            assertEquals(expected, m)
            assertEquals(4, t.sent.count { it == "21CED0CF" })
            assertEquals(4, t.sent.count { it == "010C0D" })
        }
    }

    @Test
    fun validOptionalSpeedStillUpdatesBothValues() {
        val m = BatteryModel()
        awaitBlocking { poller(PollTransport(battery, "410C0FA00D64")).poll(m) }
        assertEquals(1000, m.rpm)
        assertEquals(62.5f, m.speedMph, 0.001f)
        assertEquals(14, m.blockVolts.size)
    }

    @Test
    fun optionalTransportFailureIsNotSuppressed() {
        val t = PollTransport(battery, "", TransportException("link failed"))
        assertThrows(TransportException::class.java) { awaitBlocking { poller(t).poll(BatteryModel()) } }
    }

    @Test
    fun malformedBatteryDataStillFailsBeforeTheOptionalRead() {
        val t = PollTransport("61CE", "410C0FA00D64")
        assertThrows(NoDataException::class.java) { awaitBlocking { poller(t).poll(BatteryModel()) } }
        assertFalse(t.sent.contains("010C0D"))
    }
}
