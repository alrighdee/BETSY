package org.betsy.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfMeaningTest {
    @Test
    fun `the retained real fault pair has an explanation`() {
        val detail = InfMeaning.forCode("P0571", 115)
        assertNotNull(detail)
        assertTrue(detail!!.narrows.contains("both brake-switch signals", ignoreCase = true))
    }

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

    @Test
    fun `one exact reported pair resolves`() {
        val result = InfMeaning.resolve(listOf("P0AA6", "P0A80"), 612)
        assertEquals(
            InfMeaning.Resolution.Exact(
                "P0AA6",
                612,
                InfMeaning.forCode("P0AA6", 612)!!,
            ),
            result,
        )
    }

    @Test
    fun `resolution normalizes case and deduplicates reported codes`() {
        val expected = InfMeaning.resolve(listOf("P0AA6"), 612)
        assertEquals(expected, InfMeaning.resolve(listOf("p0aa6", "P0AA6"), 612))
    }

    @Test
    fun `unknown reported pair remains unresolved`() {
        assertEquals(InfMeaning.Resolution.Unresolved(612), InfMeaning.resolve(listOf("P0A80"), 612))
    }

    @Test
    fun `sub-code 123 resolves with one parent and not with both`() {
        assertTrue(InfMeaning.resolve(listOf("P0A1F"), 123) is InfMeaning.Resolution.Exact)
        assertEquals(
            InfMeaning.Resolution.Unresolved(123),
            InfMeaning.resolve(listOf("P0A1F", "P3000"), 123),
        )
    }

    @Test
    fun `insulation aliases resolve once as shared`() {
        val result = InfMeaning.resolve(listOf("P3009", "P0AA6"), 612)
        assertTrue(result is InfMeaning.Resolution.Shared)
        result as InfMeaning.Resolution.Shared
        assertEquals(linkedSetOf("P3009", "P0AA6"), result.dtcs)
        assertEquals(InfMeaning.forCode("P0AA6", 612), result.detail)
    }

    @Test
    fun `documented parent membership is read only`() {
        assertTrue(InfMeaning.isDocumentedParent("p0a1f"))
        assertTrue(InfMeaning.isDocumentedParent("P3000"))
        assertFalse(InfMeaning.isDocumentedParent("P0A80"))
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

    /**
     * The four phase-current entries per machine differ only in main-versus-backup and in how the
     * sensor failed, and the two machines differ only in being motor or generator. Twenty-four
     * near-identical sentences written to one pattern is exactly where a copied line survives
     * unnoticed, and a collapsed pair would send someone to the wrong sensor with full confidence.
     */
    @Test
    fun `phase current sensor entries stay distinct across phase and machine`() {
        val entries =
            mapOf(
                "P0A60" to listOf(288, 289, 290, 292, 294, 501),
                "P0A63" to listOf(296, 297, 298, 300, 302, 502),
                "P0A72" to listOf(326, 327, 328, 330, 333, 515),
                "P0A75" to listOf(334, 335, 336, 338, 341, 516),
            ).flatMap { (dtc, infs) -> infs.map { InfMeaning.forCode(dtc, it)!!.narrows } }

        assertEquals(24, entries.size)
        assertEquals(entries.size, entries.toSet().size)
        // The motor and generator halves must not read as each other.
        assertTrue(entries.count { it.contains("motor's") } == 12)
        assertTrue(entries.count { it.contains("generator's") } == 12)
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

    /**
     * `142` sits among eighteen sub-codes whose fault-table text is identical, and it shipped
     * inside that generated family for one release. The master chart gives it its own row, and it
     * is not an internal fault: the area is the wiring and the power source control unit. Read as
     * the family sentence it says "replace the hybrid controller", which is the wrong part.
     */
    @Test
    fun `the controller sub-code that is not an internal fault points away from the controller`() {
        val odd = InfMeaning.forCode("P0A1D", 142)!!
        val family = InfMeaning.forCode("P0A1D", 155)!!
        assertNotEquals(family.narrows, odd.narrows)
        assertNotEquals(family.area, odd.area)
        assertTrue(!odd.area.contains("Hybrid controller"))
        assertTrue(odd.area.contains("power source", ignoreCase = true))
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
