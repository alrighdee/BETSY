package org.betsy.ui

import org.betsy.decode.InfMeaning
import org.betsy.dtc.DtcGroup
import org.betsy.dtc.DtcSource
import org.betsy.model.Dtc
import org.betsy.model.InfDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcTextFormatterTest {
    @Test
    fun twoFaultsStillShowTheOneExactExplanation() {
        val groups =
            listOf(
                group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6"),
                group(DtcSource.BATTERY_CONTROL, "Battery ECU (7E3)", 0x0A80 to "P0A80"),
            )
        val text = DtcTextFormatter.formatGroups(groups, listOf(exact("P0AA6", 612)))

        assertTrue(text.contains("P0AA6-612"))
        assertTrue(text.contains(InfMeaning.forCode("P0AA6", 612)!!.narrows))
        assertTrue(text.contains("P0A80"))
        assertFalse(text.contains("P0A80-612"))
    }

    @Test
    fun severalInfValuesCanResolveAcrossParents() {
        val groups =
            listOf(
                group(
                    DtcSource.HYBRID_CONTROL,
                    "HV ECU (7E2)",
                    0x0AA6 to "P0AA6",
                    0x0705 to "P0705",
                ),
            )
        val text =
            DtcTextFormatter.formatGroups(
                groups,
                listOf(exact("P0AA6", 612), exact("P0705", 571)),
            )

        assertTrue(text.contains("P0AA6-612"))
        assertTrue(text.contains("P0705-571"))
    }

    @Test
    fun severalInfValuesUnderOneParentDoNotRepeatTheParent() {
        val groups = listOf(group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6"))
        val text =
            DtcTextFormatter.formatGroups(
                groups,
                listOf(exact("P0AA6", 526), exact("P0AA6", 612)),
            )

        assertEquals(1, text.lines().count { it == "  P0AA6" })
        assertTrue(text.contains("INF 526:"))
        assertTrue(text.contains("INF 612:"))
        assertTrue(text.contains(InfMeaning.forCode("P0AA6", 526)!!.narrows))
        assertTrue(text.contains(InfMeaning.forCode("P0AA6", 612)!!.narrows))
    }

    @Test
    fun conflictingInf123DisplaysNoSelectedExplanation() {
        val groups =
            listOf(
                group(
                    DtcSource.HYBRID_CONTROL,
                    "HV ECU (7E2)",
                    0x0A1F to "P0A1F",
                    0x3000 to "P3000",
                ),
            )
        val text =
            DtcTextFormatter.formatGroups(
                groups,
                listOf(InfMeaning.Resolution.Unresolved(123)),
            )

        assertFalse(text.contains("P0A1F-123"))
        assertFalse(text.contains("P3000-123"))
        assertFalse(text.contains(InfMeaning.forCode("P0A1F", 123)!!.narrows))
        assertFalse(text.contains(InfMeaning.forCode("P3000", 123)!!.narrows))
    }

    @Test
    fun aliasesRenderOneSharedExplanation() {
        val groups =
            listOf(
                group(
                    DtcSource.HYBRID_CONTROL,
                    "HV ECU (7E2)",
                    0x3009 to "P3009",
                    0x0AA6 to "P0AA6",
                ),
            )
        val shared = InfMeaning.resolve(listOf("P3009", "P0AA6"), 612)
        val text = DtcTextFormatter.formatGroups(groups, listOf(shared))
        val sentence = InfMeaning.forCode("P0AA6", 612)!!.narrows

        assertTrue(text.contains("Shared INF 612 (P3009 / P0AA6):"))
        assertEquals(1, occurrences(text, sentence))
        assertFalse(text.contains("P3009-612"))
        assertFalse(text.contains("P0AA6-612"))
    }

    @Test
    fun repeatedRawInfIsExplainedOnceAndPreservedTwice() {
        val groups = listOf(group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6"))
        val resolutions = listOf(exact("P0AA6", 612))
        val raw = listOf(InfDetail(0xC7, 612), InfDetail(0xCA, 612))
        val text =
            DtcTextFormatter.formatGroups(groups, resolutions) +
                DtcTextFormatter.formatInfEvidence(raw, 5)
        val sentence = InfMeaning.forCode("P0AA6", 612)!!.narrows

        assertEquals(1, occurrences(text, sentence))
        assertTrue(text.contains("Detail Code 2: 612"))
        assertTrue(text.contains("Detail Code 5: 612"))
    }

    @Test
    fun singleExactOutputKeepsCombinedCodeExplanationAndArea() {
        val groups = listOf(group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6"))
        val text = DtcTextFormatter.formatGroups(groups, listOf(exact("P0AA6", 612)))
        val detail = InfMeaning.forCode("P0AA6", 612)!!

        assertTrue(text.contains("P0AA6-612"))
        assertTrue(text.contains(detail.narrows))
        assertTrue(text.contains("Look at: ${detail.area}"))
    }

    @Test
    fun generalAndLocalizedInsulationValuesBothRemainVisible() {
        val groups = listOf(group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6"))
        for (localized in 611..614) {
            val text =
                DtcTextFormatter.formatGroups(
                    groups,
                    listOf(exact("P0AA6", 526), exact("P0AA6", localized)),
                )
            assertTrue(text.contains("INF 526:"))
            assertTrue(text.contains("INF $localized:"))
        }
    }

    @Test
    fun displayLabelsDoNotControlAttribution() {
        val resolution = listOf(exact("P0AA6", 612))
        val standard =
            DtcTextFormatter.formatGroups(
                listOf(group(DtcSource.HYBRID_CONTROL, "HV ECU (7E2)", 0x0AA6 to "P0AA6")),
                resolution,
            )
        val renamed =
            DtcTextFormatter.formatGroups(
                listOf(group(DtcSource.HYBRID_CONTROL, "Presentation changed", 0x0AA6 to "P0AA6")),
                resolution,
            )

        assertTrue(standard.contains("P0AA6-612"))
        assertTrue(renamed.contains("P0AA6-612"))
    }

    private fun group(
        source: DtcSource,
        label: String,
        vararg codes: Pair<Int, String>,
    ): DtcGroup = DtcGroup(source, label, codes.map { (raw, code) -> Dtc(raw, code) })

    private fun exact(
        dtc: String,
        inf: Int,
    ): InfMeaning.Resolution.Exact = InfMeaning.Resolution.Exact(dtc, inf, InfMeaning.forCode(dtc, inf)!!)

    private fun occurrences(
        text: String,
        needle: String,
    ): Int = text.windowed(needle.length).count { it == needle }
}
