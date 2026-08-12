package org.betsy

import org.betsy.decode.DtcMeaning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These check the *shape* of the explanations, not their prose. The thing worth pinning is that an
 * unknown code stays unknown, because a confident wrong explanation could send someone to replace a
 * battery that is fine.
 */
class DtcMeaningTest {
    /** Keyed on the value the car transmits, so a read maps straight through with no formatting. */
    @Test
    fun theCodesFromTheTestCarAreExplained() {
        assertNotNull("P0571", DtcMeaning.forWire(0x0571))
        assertNotNull("U0293", DtcMeaning.forWire(0xC293))
        assertNotNull("P0AA6", DtcMeaning.forWire(0x0AA6))
        assertNotNull("P0A80", DtcMeaning.forWire(0x0A80))
    }

    @Test
    fun anUnknownCodeGetsNoInventedExplanation() {
        assertNull(DtcMeaning.forWire(0x1234))
        assertNull(DtcMeaning.forWire(0x0000))
    }

    /** The two that can hurt someone are the two that must say so. */
    @Test
    fun theDangerousOnesAreMarkedUrgent() {
        assertEquals(DtcMeaning.Severity.URGENT, DtcMeaning.forWire(0x0AA6)!!.severity)
        assertEquals(DtcMeaning.Severity.URGENT, DtcMeaning.forWire(0x0A94)!!.severity)
    }

    /**
     * The isolation fault is the one where a wrong move has consequences, so its text has to
     * mention the orange cabling rather than leaving the reader to find out.
     */
    @Test
    fun theIsolationFaultWarnsAboutTouchingHighVoltage() {
        val m = DtcMeaning.forWire(0x0AA6)!!
        assertTrue(m.usually.contains("orange"))
    }

    /**
     * The expensive misdiagnosis this app exists to prevent: "replace hybrid battery pack" read as
     * "buy a whole battery", when it is often one module.
     */
    @Test
    fun theBatteryCodeSaysItMayBeOneModule() {
        val m = DtcMeaning.forWire(0x0A80)!!
        assertTrue(m.usually.contains("module"))
    }

    /** The closing line has to stand alone, since it is read last and acted on. */
    @Test
    fun everySeverityCarriesUsableAdvice() {
        for (s in DtcMeaning.Severity.entries) {
            assertTrue(s.name, s.advice.length > 10 && s.advice.endsWith("."))
        }
    }

    /**
     * The family this app exists for. A weak block is the repairable case, so the text must not
     * read like a dead battery, and it must point at the specific module.
     */
    @Test
    fun everyBatteryBlockOnAGen2IsExplainedAndNamesItsBlock() {
        for (block in 1..14) {
            val m = DtcMeaning.forWire(0x3010 + block)
            assertNotNull("block $block", m)
            assertTrue("block $block names itself", m!!.what.contains("block $block"))
            assertTrue("block $block avoids implying a dead pack", m.usually.contains("module"))
        }
    }

    /** A blocked cooling fan actively destroys a battery, so it must not read as trivial. */
    @Test
    fun theCoolingFanFaultIsNotMarkedMinor() {
        val m = DtcMeaning.forWire(0x0A82)!!
        assertTrue(m.severity != DtcMeaning.Severity.MINOR)
    }

    @Test
    fun everyExplanationIsActuallyWritten() {
        assertTrue(DtcMeaning.explainedCount >= 30)
    }
}
