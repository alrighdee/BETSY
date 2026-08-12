package org.betsy

import org.betsy.decode.InfDecoder
import org.betsy.decode.InfLayout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * End to end against the capture BETSY itself uploaded, verbatim from
 * `a published capture in captures/real/`. The app recorded these bytes faithfully and then
 * reported "no INF decoded" for a day, because the decoder implemented the wrong model.
 */
class RealCaptureTest {
    private val pages =
        mapOf(
            0xC6 to "03261C6" + "0".repeat(97),
            0xC7 to "03261C7808080800000049A417E00615F5B5D70820000A0AF00000000000000010073636B4A02615F5C639E6C665D659E9A801C0000000000",
            0xC8 to "03261C8" + "0".repeat(97),
            0xC9 to "03261C9" + "0".repeat(97),
            0xCA to "03261CA" + "0".repeat(97),
        )

    @Test
    fun betsysOwnCaptureNowYieldsP0571SubCode115() {
        val found =
            pages.flatMap { (lid, raw) -> InfDecoder.decodeActive(raw, InfLayout.table(lid)!!) }
        assertEquals("exactly one sub-code from the whole sweep", 1, found.size)
        assertEquals(115, found[0].code)
        assertEquals("Detail Code 2", found[0].tableLabel)
    }
}
