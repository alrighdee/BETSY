package org.betsy

import org.betsy.elm.ElmSession
import org.betsy.ui.connect.FirmwareGrade
import org.betsy.ui.connect.FirmwareTone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connect screen grades an adapter from the ATZ banner it answered with on a previous session
 * (PROTOCOL.md §1.1). Clones below v1.5 cannot read Toyota battery blocks, so a v1.x banner must never
 * grade as good, and an adapter we have never connected to must stay unknown rather than be guessed.
 */
class FirmwareGradeTest {
    @Test
    fun `genuine v2 firmware grades good`() {
        assertEquals(FirmwareTone.GOOD, FirmwareGrade.tone("ELM327 v2.2"))
        assertEquals(FirmwareTone.GOOD, FirmwareGrade.tone("ELM327 v2.0"))
    }

    @Test
    fun `v1 firmware grades weak`() {
        assertEquals(FirmwareTone.WEAK, FirmwareGrade.tone("ELM327 v1.5"))
        assertEquals(FirmwareTone.WEAK, FirmwareGrade.tone("ELM327 v1.4"))
    }

    @Test
    fun `missing or unparseable banner stays unknown`() {
        assertEquals(FirmwareTone.UNKNOWN, FirmwareGrade.tone(null))
        assertEquals(FirmwareTone.UNKNOWN, FirmwareGrade.tone(""))
        assertEquals(FirmwareTone.UNKNOWN, FirmwareGrade.tone("OBDII adapter"))
    }

    @Test
    fun `label falls back when no banner is cached`() {
        assertEquals("Firmware unknown", FirmwareGrade.label(null))
        assertEquals("Firmware unknown", FirmwareGrade.label("  "))
        assertEquals("ELM327 v2.2", FirmwareGrade.label("ELM327 v2.2"))
    }

    @Test
    fun `banner is picked out of the ATZ echo and blank lines`() {
        assertEquals("ELM327 v2.2", ElmSession.parseBanner("ATZ\r\r\rELM327 v2.2\r\r"))
        assertEquals("ELM327 v1.5", ElmSession.parseBanner("\r\rELM327 v1.5\r"))
    }

    @Test
    fun `a response without a banner yields empty rather than junk`() {
        assertEquals("", ElmSession.parseBanner("ATZ\r\rOK\r"))
    }
}
