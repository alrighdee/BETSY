package org.betsy.debug

import org.betsy.BuildConfig
import org.betsy.capture.CaptureData
import org.betsy.detect.VehicleDetector
import org.betsy.dtc.DtcReader
import org.betsy.elm.ElmSession
import org.betsy.transport.ReplayTransport
import org.betsy.transport.awaitBlocking

/**
 * Builds a shareable [CaptureData] from a script without opening a real transport. Detection and
 * the DTC sweep run against a zero-delay [ReplayTransport], so the capture is the real decoder's
 * reading of the fixture bytes, not a hand-written placeholder that could drift from it.
 */
object DemoFixtures {
    fun capture(scenario: DemoScenario): CaptureData {
        val transport = ReplayTransport(scenario.script)
        val session = ElmSession(transport)
        val info = awaitBlocking { VehicleDetector.detect(session) }
        val result = awaitBlocking { DtcReader(session, info).read() }
        return CaptureData
            .from(
                result = result,
                info = info,
                elm = "ELM327 v2.2",
                version = BuildConfig.VERSION_NAME,
                build = BuildConfig.GIT_HASH,
            ).copy(demo = true)
    }
}
