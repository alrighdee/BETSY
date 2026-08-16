package org.betsy

import org.betsy.update.UpdateChecker
import org.betsy.update.UpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Latest-release comparison and response mapping. No socket: [UpdateChecker.interpret] is the
 * seam, the same idea as the demo uploader stub.
 */
class UpdateNoticeTest {
    @Test
    fun newerTagIsAvailable() {
        val status = UpdateChecker.evaluate("v0.0.4", URL, "0.0.3")
        val available = status as UpdateStatus.Available
        assertEquals("0.0.4", available.version)
        assertEquals(URL, available.url)
    }

    @Test
    fun sameTagIsCurrent() {
        val status = UpdateChecker.evaluate("v0.0.3", URL, "0.0.3")
        assertEquals(UpdateStatus.Current("0.0.3"), status)
    }

    @Test
    fun olderTagIsCurrent() {
        val status = UpdateChecker.evaluate("v0.0.2", URL, "0.0.3")
        assertEquals(UpdateStatus.Current("0.0.3"), status)
    }

    @Test
    fun leadingVDoesNotChangeEquality() {
        assertEquals(0, UpdateChecker.compare("v0.0.4", "0.0.4"))
        assertEquals("0.0.4", UpdateChecker.normalize("V0.0.4"))
    }

    @Test
    fun junkTagCannotOutrankARealOne() {
        assertTrue(UpdateChecker.compare("0.0.3", "not-a-version") > 0)
        val status = UpdateChecker.evaluate("later", URL, "0.0.3")
        assertEquals(UpdateStatus.Current("0.0.3"), status)
    }

    @Test
    fun missingSegmentEqualsZero() {
        assertEquals(0, UpdateChecker.compare("0.0", "0.0.0"))
    }

    @Test
    fun preReleaseOutranksPreviousRelease() {
        assertTrue(UpdateChecker.compare("0.0.5-pre-release", "0.0.4") > 0)
        assertEquals(
            UpdateStatus.Current("0.0.5-pre-release"),
            UpdateChecker.evaluate("v0.0.4", URL, "0.0.5-pre-release"),
        )
    }

    @Test
    fun preReleaseLosesToTheSameNumbersReleased() {
        assertTrue(UpdateChecker.compare("0.0.5", "0.0.5-pre-release") > 0)
        val status = UpdateChecker.evaluate("v0.0.5", URL5, "0.0.5-pre-release")
        val available = status as UpdateStatus.Available
        assertEquals("0.0.5", available.version)
        assertEquals(URL5, available.url)
    }

    @Test
    fun dismissedVersionStaysQuietUntilANewerTag() {
        val four = UpdateStatus.Available("0.0.4", URL)
        assertNull(UpdateChecker.visibleBanner(four, "0.0.4"))
        assertEquals(four, UpdateChecker.visibleBanner(four, "0.0.3"))
        val five = UpdateStatus.Available("0.0.5", URL)
        assertEquals(five, UpdateChecker.visibleBanner(five, "0.0.4"))
        assertNull(UpdateChecker.visibleBanner(UpdateStatus.Current("0.0.3"), null))
    }

    @Test
    fun interpretMapsJsonAndFailuresWithoutASocket() {
        val newer =
            UpdateChecker.interpret(
                200,
                """{"tag_name":"v0.0.4","html_url":"$URL"}""",
                "0.0.3",
            )
        assertEquals(UpdateStatus.Available("0.0.4", URL), newer)

        val same =
            UpdateChecker.interpret(
                200,
                """{"tag_name":"v0.0.3","html_url":"$URL"}""",
                "0.0.3",
            )
        assertEquals(UpdateStatus.Current("0.0.3"), same)

        assertTrue(UpdateChecker.interpret(404, "{}", "0.0.3") is UpdateStatus.Unknown)
        assertTrue(UpdateChecker.interpret(200, "not-json", "0.0.3") is UpdateStatus.Unknown)
        assertTrue(UpdateChecker.interpret(200, "{}", "0.0.3") is UpdateStatus.Unknown)
    }

    private companion object {
        const val URL = "https://github.com/alrighdee/BETSY/releases/tag/v0.0.4"
        const val URL5 = "https://github.com/alrighdee/BETSY/releases/tag/v0.0.5"
    }
}
