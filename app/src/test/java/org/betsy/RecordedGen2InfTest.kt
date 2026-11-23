package org.betsy

import org.betsy.decode.DtcDecoder
import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.betsy.decode.InfMeaning
import org.betsy.elm.Normalize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorded Gen2 hybrid responses with five DTCs and five populated snapshot pages. */
class RecordedGen2InfTest {
    private data class Page(
        val lid: Int,
        val response: String,
        val inf: Int,
        val occurrenceOrder: Int,
        val documentedCandidate: String,
    )

    private val dtcResponse = "53050A1DC100C1113102C129"
    private val pages =
        listOf(
            Page(
                0xC6,
                "61C68080808000008095416F0042484A4A6E810000A0BC000000000000000200D04E6E490142474A4D944F4B4A4E95938000",
                208,
                2,
                "U0111",
            ),
            Page(
                0xC7,
                "61C78080808000004683416200494C4A4A68800000A29E0000000010000001020D520100004A4C4A52834F4D4B52A783801D",
                525,
                1,
                "P3102",
            ),
            Page(
                0xC8,
                "61C88080808000008095416F0242494A4A6E81000060BC000000000000000300D34E6D490142474A4D944F4B4A4E95938000",
                211,
                3,
                "U0100",
            ),
            Page(
                0xC9,
                "61C98080808000008095416F0042474A4A6F800000A0A1000000000000000402674E02000142474A4D944F4B4A4E95938000",
                615,
                4,
                "P0A1D",
            ),
            Page(
                0xCA,
                "61CA8080808000008095416F0042484A4A6F800000A090000000000000000500D44E02000142474A4D944F4B4A4E95938000",
                212,
                5,
                "U0100",
            ),
        )

    @Test
    fun recordedResponsesRetainAllCodesAndDetailValues() {
        val dtcs = DtcDecoder.decodeMode13(dtcResponse)
        assertEquals(listOf("P0A1D", "U0100", "U0111", "P3102", "U0129"), dtcs.map { it.code })
        val details =
            pages.map { page ->
                assertEquals(50, page.response.length / 2)
                assertEquals(page.occurrenceOrder, Normalize.u8(page.response, 4 + 28 * 2))
                InfDecoder.decodeActive(page.response, InfLayout.table(page.lid)!!).single()
            }
        assertEquals(listOf(208, 525, 211, 615, 212), details.map { it.code })
        assertEquals(pages.map { it.lid }, details.map { it.table })
    }

    @Test
    fun documentedCandidatesAreIndependentOfPageAndDtcOrder() {
        val dtcs = DtcDecoder.decodeMode13(dtcResponse).map { it.code }
        val resolved =
            pages.map { page ->
                val inf = InfDecoder.decodeActive(page.response, InfLayout.table(page.lid)!!).single().code
                assertEquals(page.inf, inf)
                val result = InfMeaning.resolve(dtcs, inf)
                assertTrue(result is InfMeaning.Resolution.Exact)
                result as InfMeaning.Resolution.Exact
                assertEquals(page.documentedCandidate, result.dtc)
                assertEquals(result, InfMeaning.resolve(dtcs.reversed(), inf))
                assertNotEquals(dtcs[page.occurrenceOrder - 1], result.dtc)
                result
            }
        assertEquals(listOf(211, 212), resolved.filter { it.dtc == "U0100" }.map { it.inf })
        assertEquals(5, resolved.size)
    }
}
