package org.betsy

import org.betsy.capture.CaptureData
import org.betsy.capture.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A VIN must not reach a published capture. These pin the redaction rather than the app's current
 * habit of not requesting mode 09, because that habit is one commit away from changing.
 */
class RedactTest {
    /** ASCII hex of 1HGBH41JXMN109186, the standard documentation example VIN. */
    private val vinHex = "314847424834314A584D4E313039313836"

    @Test
    fun singleFrameVinIsRemoved() {
        val out = Redact.vin("490201$vinHex")
        assertFalse(out.contains("314847"))
        assertTrue(out.contains(Redact.MARKER))
    }

    /**
     * The case that matters. An ELM327 splits long replies across `N:` indices, so a pattern
     * anchored on the tag alone strips the first frame and leaves the serial portion behind.
     */
    @Test
    fun multiFrameVinIsRemovedIncludingContinuationFrames() {
        val raw = "014 0:490201314847 1:424834314A584D 2:4E313039313836"
        val out = Redact.vin(raw)
        assertFalse("first frame survived", out.contains("314847"))
        assertFalse("continuation frame survived", out.contains("424834"))
        assertFalse("serial portion survived", out.contains("4E313039"))
    }

    @Test
    fun payloadsWithoutAVinAreUntouched() {
        // 21C7 from the fault capture. Contains 0x73 (INF 115) and must survive intact.
        val inf = "03261C7808080800000049A417E00615F5B5D70820000A0AF00000000000000010073636B4A02"
        assertEquals(inf, Redact.vin(inf))
        assertEquals("53010571", Redact.vin("53010571"))
        assertEquals("4100981A8013", Redact.vin("4100981A8013"))
    }

    /**
     * The guarantee that actually matters: no matter how the object was built, a VIN cannot reach
     * the wire. Constructed directly here, bypassing `CaptureData.from`, because that is exactly
     * the route a future contributor would take without knowing about any of this.
     */
    @Test
    fun aVinCannotReachTheWireHoweverTheCaptureWasBuilt() {
        val data =
            CaptureData(
                version = "0.0.2",
                build = "abc1234",
                car = "Gen2",
                elm = "ELM327 v1.5",
                raw =
                    mapOf(
                        "7E2/0902" to "014 0:490201314847 1:424834314A584D 2:4E313039313836",
                        "7E2/13B0" to "53010571",
                    ),
                dtcs = listOf("HV ECU (7E2): P0571"),
                notes = emptyList(),
                codes = emptyList(),
                logTail = listOf("ELM << 490201$vinHex"),
                hasStoredDtcs = true,
            )
        val json = data.toJson()
        assertFalse("VIN in raw", json.contains("314847"))
        assertFalse("VIN continuation frame in raw", json.contains("424834"))
        assertFalse("VIN serial portion in raw", json.contains("4E313039"))
        assertFalse("VIN in the session log tail", json.contains("31484742"))
        // and the actual diagnostic payload is untouched
        assertTrue(json.contains("53010571"))
    }

    @Test
    fun markerIsStableSoDownstreamCanRecogniseIt() {
        assertEquals("<VIN-REDACTED>", Redact.MARKER)
    }
}
