package org.betsy

import org.betsy.ui.connect.ConnectPhase
import org.betsy.ui.connect.ConnectProgress
import org.betsy.ui.connect.StepState
import org.betsy.ui.connect.WifiEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connecting panel exists so a failure says which phase failed. These pin the progression the
 * panel renders: one quarter per completed phase, exactly one active phase, and a failed phase that
 * stays failed instead of being overwritten by the next tick.
 */
class ConnectProgressTest {
    @Test
    fun `a fresh attempt is all pending at zero percent`() {
        val progress = ConnectProgress(wifi = false)
        val snapshot = progress.snapshot()
        assertEquals(0, snapshot.percent)
        assertEquals(4, snapshot.steps.size)
        assertTrue(snapshot.steps.all { it.state == StepState.PENDING })
        assertTrue(snapshot.steps.all { it.elapsedMs == null })
    }

    @Test
    fun `each completed phase advances the ring by a quarter`() {
        val progress = ConnectProgress(wifi = false)
        progress.begin(ConnectPhase.LINK)
        assertEquals(0, progress.percent())
        progress.complete(ConnectPhase.LINK, 120)
        assertEquals(25, progress.percent())
        progress.complete(ConnectPhase.ADAPTER, 210)
        assertEquals(50, progress.percent())
        progress.complete(ConnectPhase.HANDSHAKE, 330)
        progress.complete(ConnectPhase.VEHICLE, 440)
        assertEquals(100, progress.percent())
    }

    @Test
    fun `the active phase is the only one marked active`() {
        val progress = ConnectProgress(wifi = false)
        progress.complete(ConnectPhase.LINK, 120)
        progress.begin(ConnectPhase.ADAPTER)
        val steps = progress.snapshot().steps
        assertEquals(StepState.DONE, steps[0].state)
        assertEquals(StepState.ACTIVE, steps[1].state)
        assertEquals(StepState.PENDING, steps[2].state)
        assertEquals(StepState.PENDING, steps[3].state)
    }

    @Test
    fun `a failed phase is reported as failed and carries no timing`() {
        val progress = ConnectProgress(wifi = false)
        progress.complete(ConnectPhase.LINK, 120)
        progress.begin(ConnectPhase.ADAPTER)
        progress.fail(ConnectPhase.ADAPTER)
        val snapshot = progress.snapshot()
        assertTrue(snapshot.failed)
        assertEquals(StepState.FAILED, snapshot.steps[1].state)
        assertNull(snapshot.steps[1].elapsedMs)
        assertEquals(25, snapshot.percent)
    }

    @Test
    fun `the link step names the transport it is actually opening`() {
        assertEquals("Saying hello over Bluetooth", ConnectProgress(wifi = false).snapshot().steps[0].text)
        assertEquals("Saying hello over Wi-Fi", ConnectProgress(wifi = true).snapshot().steps[0].text)
    }

    @Test
    fun `the adapter step names the detected banner once ATZ has answered`() {
        val progress = ConnectProgress(wifi = false)
        assertEquals("Checking the adapter", progress.snapshot().steps[1].text)
        progress.adapterDetected("ELM327 v2.2")
        assertEquals("Checking the adapter \u00b7 ELM327 v2.2", progress.snapshot().steps[1].text)
    }

    @Test
    fun `a blank banner leaves the adapter step generic`() {
        val progress = ConnectProgress(wifi = false)
        progress.adapterDetected("")
        assertEquals("Checking the adapter", progress.snapshot().steps[1].text)
    }

    @Test
    fun `wifi address falls back to the section 1 defaults per part`() {
        assertEquals(WifiEndpoint("192.168.0.10", 35000), WifiEndpoint.parse(""))
        assertEquals(WifiEndpoint("10.0.0.5", 35000), WifiEndpoint.parse("10.0.0.5"))
        assertEquals(WifiEndpoint("10.0.0.5", 23), WifiEndpoint.parse("10.0.0.5:23"))
        assertEquals(WifiEndpoint("10.0.0.5", 35000), WifiEndpoint.parse("10.0.0.5:garbage"))
    }
}
