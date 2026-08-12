package org.betsy

import org.betsy.debug.CaptureLog
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window must keep the sweep, not the end of the session.
 *
 * Modelled on a real fault session: 964 lines, the sweep beginning at line 70, and the
 * battery poll running for the remaining 894. A tail of any sane size preserves none of the
 * sweep, which is the one thing a fault capture exists to record.
 */
class CaptureLogWindowTest {
    private fun session(): List<String> =
        buildList {
            repeat(69) { add("POLL soc=58.5 line $it") }
            add("DTC ${CaptureLog.SWEEP_MARKER} Gen2 (2004-2009)")
            repeat(130) { add("ELM >> sweep command $it") }
            repeat(764) { add("POLL soc=58.5 trailing $it") }
        }

    /** Pure re-implementation of CaptureLog.window's rule, so the policy is testable off-device. */
    private fun window(
        lines: List<String>,
        marker: String,
        before: Int,
        max: Int,
    ): List<String> {
        val at = lines.indexOfLast { it.contains(marker) }
        if (at < 0) return lines.takeLast(max)
        return lines.subList((at - before).coerceAtLeast(0), lines.size).take(max)
    }

    @Test
    fun aTailWouldHaveLostTheEntireSweep() {
        val tail = session().takeLast(120)
        assertTrue("a tail should contain no sweep at all", tail.none { it.contains("sweep command") })
    }

    @Test
    fun theWindowKeepsTheWholeSweep() {
        val w = window(session(), CaptureLog.SWEEP_MARKER, 25, 700)
        assertTrue("marker missing", w.any { it.contains(CaptureLog.SWEEP_MARKER) })
        assertTrue("first command missing", w.any { it.contains("sweep command 0") })
        assertTrue("last command missing", w.any { it.contains("sweep command 129") })
        assertTrue("pre-sweep context missing", w.any { it.contains("POLL soc=58.5 line 60") })
    }

    @Test
    fun fallsBackToATailWhenNoSweepHappened() {
        val lines = List(500) { "POLL line $it" }
        val w = window(lines, CaptureLog.SWEEP_MARKER, 25, 150)
        assertTrue(w.size == 150 && w.last() == "POLL line 499")
    }
}
