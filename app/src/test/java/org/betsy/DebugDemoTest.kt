package org.betsy

import org.betsy.capture.CaptureUploader
import org.betsy.capture.UploadResult
import org.betsy.debug.DemoFixtures
import org.betsy.debug.DemoMode
import org.betsy.debug.DemoScenario
import org.betsy.detect.VehicleDetector
import org.betsy.detect.VehicleInfo
import org.betsy.detect.VehicleModel
import org.betsy.dtc.DtcReader
import org.betsy.dtc.SweepPhase
import org.betsy.dtc.SweepProgress
import org.betsy.dtc.SweepStep
import org.betsy.elm.ElmSession
import org.betsy.transport.ReplayTransport
import org.betsy.transport.SimulatedCar
import org.betsy.transport.awaitBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debug demo path: the scripted fixtures, the sweep progress reporter, and the upload
 * short-circuit that keeps a fixture from leaving the device.
 */
class DebugDemoTest {
    private val gen2 = VehicleInfo(VehicleModel.GEN2, supported = true, blockCount = 14, cellCount = 28)

    @Test
    fun scriptsCarryAnAtzBannerSoSessionInitSucceeds() {
        assertTrue(SimulatedCar.gen2Healthy.getValue("ATZ").contains("ELM327"))
        assertTrue(SimulatedCar.gen2WithStoredFault.getValue("ATZ").contains("ELM327"))

        val session = ElmSession(ReplayTransport(SimulatedCar.gen2Healthy))
        awaitBlocking { session.initialize() }
        assertEquals("ELM327 v2.2", session.adapterBanner)
    }

    @Test
    fun captureOnlyOverlayDetectsAsCaptureOnly() {
        val session = ElmSession(ReplayTransport(SimulatedCar.gen2CaptureOnly))
        val info = awaitBlocking { VehicleDetector.detect(session) }
        assertTrue(info.captureOnly)
        assertEquals(VehicleModel.GEN2_7E2, info.model)
    }

    @Test
    fun decoderMissOverlayReportsStoredCodesButNoInf() {
        val t = ReplayTransport(SimulatedCar.gen2DecoderMiss)
        val result = awaitBlocking { DtcReader(ElmSession(t), gen2).read() }
        assertTrue(result.hasStoredDtcs)
        assertEquals(emptyList<Int>(), result.infCodes.map { it.code })
    }

    @Test
    fun progressListenerReportsEveryStepInOrderWithoutSleeping() {
        val steps = mutableListOf<SweepStep>()
        val t = ReplayTransport(SimulatedCar.gen2WithStoredFault)
        val startedAt = System.nanoTime()
        val result =
            awaitBlocking {
                DtcReader(ElmSession(t), gen2).read(progress = SweepProgress { steps += it })
            }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(
            listOf(
                SweepPhase.LIVENESS to 1,
                SweepPhase.STORED_DTCS to 3,
                SweepPhase.GENERIC_OBD to 2,
                SweepPhase.FREEZE_FRAME to 13,
                SweepPhase.INF_CODES to 5,
            ),
            steps.groupBy { it.phase }.map { it.key to it.value.size },
        )
        // A Gen2 sweep is 24 steps across 5 phases, and each step carries its position in both.
        assertEquals(24, steps.size)
        assertEquals(24, steps.last().totalSteps)
        assertEquals(24, steps.last().totalStep)
        assertEquals(SweepPhase.LIVENESS, steps.first().phase)
        assertEquals(SweepPhase.INF_CODES, steps.last().phase)
        // The freeze frame reports its mask first, then the twelve PIDs, in order.
        assertEquals("Freeze frame", steps[6].label)
        assertEquals("Freeze frame · PID 4E", steps[18].label)
        // Zero-delay transport: the read must not grow a wall-clock wait for the reporter.
        assertTrue("read took ${elapsedMs}ms, expected instant", elapsedMs < 1000)
        assertEquals(listOf(115), result.infCodes.map { it.code })
    }

    @Test
    fun demoUploadShortCircuitsWithoutNetwork() {
        DemoMode.activate(DemoScenario.GEN2_STORED_FAULT)
        assertTrue(DemoMode.active())
        try {
            assertTrue(CaptureUploader.submitJson("{}") is UploadResult.Ok)
            DemoMode.activate(DemoScenario.SHARE_FAIL)
            assertTrue(CaptureUploader.submitJson("{}") is UploadResult.Failed)
        } finally {
            DemoMode.deactivate()
        }
        assertFalse(DemoMode.active())
    }

    @Test
    fun demoCaptureIsRefusedEvenAfterTheSessionEnds() {
        DemoMode.deactivate()
        val data = DemoFixtures.capture(DemoScenario.GEN2_STORED_FAULT)
        assertTrue(data.demo)
        assertTrue(data.toJson().contains("\"demo\":true"))
        assertFalse(DemoMode.active())

        // The marker alone must refuse the payload, so a pending fixture retried on a cold start
        // cannot reach the worker.
        assertTrue(CaptureUploader.submitJson(data.toJson()) is UploadResult.Failed)
    }
}
