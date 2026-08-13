package org.betsy.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfMeaningTest {
    /**
     * The reason the table is keyed by pair rather than by sub-code alone.
     *
     * Sub-code 123 belongs to two different trouble codes and means something different under
     * each. A number-only lookup would answer both with whichever entry was written last, and
     * would look completely correct doing it.
     */
    @Test
    fun `sub-code 123 means different things under different codes`() {
        val underBatteryEcu = InfMeaning.forCode("P0A1F", 123)
        val underBatterySystem = InfMeaning.forCode("P3000", 123)
        assertNotNull(underBatteryEcu)
        assertNotNull(underBatterySystem)
        assertNotEquals(underBatteryEcu!!.narrows, underBatterySystem!!.narrows)
    }

    /** An undocumented pair must not borrow a neighbour's meaning. */
    @Test
    fun `unknown pairs return null rather than a guess`() {
        assertNull(InfMeaning.forCode("P0705", 999))
        assertNull(InfMeaning.forCode("P9999", 571))
        // A real sub-code under the wrong parent code is the dangerous case: the number exists,
        // so a number-only lookup would happily answer.
        assertNull(InfMeaning.forCode("P0A90", 571))
    }

    /**
     * The pack-failure codes are the ones BETSY exists for and they are not documented yet.
     * Pinned so that filling them in is a deliberate act with a test to update, rather than
     * something that quietly half-happens.
     */
    @Test
    fun `the pack failure code is still undocumented`() {
        assertNull(InfMeaning.forCode("P0A80", 596))
    }

    /**
     * The insulation fault is documented under two trouble codes with the same sub-codes. Both
     * must answer, and both must answer identically: the areas do not differ between them, and
     * two wordings would imply they do.
     */
    @Test
    fun `the insulation fault answers under both of its trouble codes`() {
        for (inf in listOf(526, 611, 612, 613, 614)) {
            val old = InfMeaning.forCode("P3009", inf)
            val new = InfMeaning.forCode("P0AA6", inf)
            assertNotNull(old)
            assertEquals(old, new)
        }
        assertTrue(InfMeaning.forCode("P0AA6", 612)!!.narrows.contains("battery", ignoreCase = true))
        assertTrue(InfMeaning.forCode("P0AA6", 611)!!.narrows.contains("air conditioning", ignoreCase = true))
    }

    @Test
    fun `lookup is case insensitive`() {
        assertEquals(InfMeaning.forCode("P0705", 571), InfMeaning.forCode("p0705", 571))
    }

    /**
     * The four shift/select circuits differ only in which sensor and which short, and that
     * difference is the entire point of showing a sub-code. If two of them ever read the same,
     * the table has stopped narrowing anything.
     */
    @Test
    fun `sub-codes under one trouble code are distinguishable`() {
        val shift =
            listOf(571, 572, 573, 574, 575, 576, 577, 578)
                .map { InfMeaning.forCode("P0705", it)!!.narrows }
        assertEquals(shift.size, shift.toSet().size)
    }

    /** A locked generator and a locked gearset are different repairs. */
    @Test
    fun `mechanical lockups are named specifically`() {
        assertTrue(InfMeaning.forCode("P0A90", 240)!!.narrows.contains("generator", ignoreCase = true))
        assertTrue(InfMeaning.forCode("P0A90", 242)!!.narrows.contains("planetary", ignoreCase = true))
    }

    /**
     * Motor temperature entries describe a SENSOR fault. Writing them as overheating would name a
     * different fault with a different repair, and it is a mistake this project has made before.
     */
    @Test
    fun `temperature sensor faults are not described as overheating`() {
        for (inf in listOf(248, 250)) {
            val t = InfMeaning.forCode("P0A2B", inf)!!.narrows.lowercase()
            assertTrue(t.contains("sensor"))
            assertTrue(!t.contains("too hot") && !t.contains("overheat"))
        }
    }

    @Test
    fun `every entry has a non-empty sentence that reads as one`() {
        for (inf in listOf(571, 578)) {
            val d = InfMeaning.forCode("P0705", inf)!!
            assertTrue(d.narrows.isNotBlank())
            assertTrue(d.narrows.endsWith("."))
            assertTrue(d.narrows.first().isUpperCase())
        }
        assertTrue(InfMeaning.size > 50)
    }

    /**
     * `P0A1D` has eighteen sub-codes the source describes identically and one, 390, that means
     * something else. Generating the family is the only sane way to carry it, and the risk of
     * generating is that it swallows the exception.
     */
    @Test
    fun `the generated controller family does not swallow the code that differs`() {
        val generic = InfMeaning.forCode("P0A1D", 135)!!.narrows
        val odd = InfMeaning.forCode("P0A1D", 390)!!.narrows
        assertNotEquals(generic, odd)
        assertTrue(odd.contains("charge", ignoreCase = true))
    }

    /** Where the per-sub-code detail was recovered, it must beat the family sentence. */
    @Test
    fun `known controller sub-codes are more specific than the family`() {
        val generic = InfMeaning.forCode("P0A1D", 135)!!.narrows
        for (inf in listOf(139, 140, 141, 143, 187, 615)) {
            assertNotEquals(generic, InfMeaning.forCode("P0A1D", inf)!!.narrows)
        }
        assertTrue(InfMeaning.forCode("P0A1D", 141)!!.narrows.contains("program", ignoreCase = true))
    }

    /**
     * Over-current on an inverter presents identically whichever component caused it. The
     * sub-code is the only thing that names the cause, so the three must not collapse.
     */
    @Test
    fun `over-current sub-codes name distinct causes`() {
        val causes = listOf(287, 505, 506).map { InfMeaning.forCode("P0A78", it)!!.narrows }
        assertEquals(causes.size, causes.toSet().size)
        // 284 is a genuine overheat on the same code, and must not read as an over-current.
        assertTrue(InfMeaning.forCode("P0A78", 284)!!.narrows.contains("overheat", ignoreCase = true))
    }

    /** These share one signal line, but distinguish an earth short, +B/open, and open alone. */
    @Test
    fun `boost shutdown circuit sub-codes keep their distinct conditions`() {
        val descriptions = (558..560).map { InfMeaning.forCode("P0A94", it)!!.narrows }
        assertEquals(descriptions.size, descriptions.toSet().size)
        assertTrue(descriptions[0].contains("earth", ignoreCase = true))
        assertTrue(descriptions[1].contains("battery positive", ignoreCase = true))
        assertTrue(descriptions[2].contains("broken", ignoreCase = true))
    }

    @Test
    fun `reviewed signal and network pairs are documented`() {
        val pairs =
            listOf(
                "P0A78" to 278,
                "P0A78" to 283,
                "P0A7A" to 321,
                "P0A7A" to 323,
                "P0A94" to 545,
                "P0A94" to 546,
                "P0A94" to 549,
                "P0A94" to 551,
                "P0A94" to 552,
                "P3212" to 275,
                "P3222" to 313,
                "P3223" to 312,
                "U0100" to 530,
                "U0111" to 531,
                "U0131" to 434,
            )

        for ((dtc, inf) in pairs) {
            assertNotNull(InfMeaning.forCode(dtc, inf))
        }
    }

    private fun assertNotNull(v: Any?) = assertTrue(v != null)
}
