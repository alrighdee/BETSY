package org.betsy

import org.betsy.elm.NegativeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ISO 14229 refusals. The live fixture is the real reply captured from a Gen2 HV ECU on 2026-08-08:
 * `7F2211` to the §9.4 INF read, mode 22 is newer than the car. Unrecognized it reached the decoder
 * and was reported as a missing `6205CA` tag, which reads like a decode bug rather than a refusal.
 */
class NegativeResponseTest {
    @Test
    fun `the captured Gen2 refusal is recognized and named`() {
        val refusal = NegativeResponse.parse("7F2211")
        assertEquals(NegativeResponse("22", 0x11), refusal)
        assertEquals("service not supported", refusal?.meaning)
    }

    @Test
    fun `other response codes get their ISO names`() {
        assertEquals("request out of range", NegativeResponse.parse("7F2231")?.meaning)
        assertEquals("conditions not correct", NegativeResponse.parse("7F1922")?.meaning)
        assertEquals("sub-function not supported", NegativeResponse.parse("7F2212")?.meaning)
    }

    @Test
    fun `an unlisted response code still reports its raw value`() {
        assertEquals("refused with NRC 0x9A", NegativeResponse.parse("7F229A")?.meaning)
    }

    @Test
    fun `positive responses are not refusals`() {
        assertNull(NegativeResponse.parse("61CE668004"))
        assertNull(NegativeResponse.parse("5300"))
        assertNull(NegativeResponse.parse("410C00000D00"))
    }

    @Test
    fun `7F occurring as payload data does not count as a refusal`() {
        // Only a response that begins with 7F is a refusal; otherwise good frames would be discarded.
        assertNull(NegativeResponse.parse("61D07F0000"))
        assertNull(NegativeResponse.parse("6205CA7F22"))
    }

    @Test
    fun `a truncated refusal is not guessed at`() {
        assertNull(NegativeResponse.parse("7F22"))
        assertNull(NegativeResponse.parse("7F"))
    }
}
