package org.betsy

import org.betsy.decode.SpeedRpmDecoder
import org.betsy.model.BatteryModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PROTOCOL.md §5.6. The regression fixture is the real response captured from a Gen2 car on
 * an on-car read: `410C00000D00`, twelve hex chars. Reading speed as a u16 needs chars 10..14 and threw
 * `StringIndexOutOfBoundsException: begin 10, end 14, length 12`, which killed the whole poll loop
 * before a single cycle completed.
 */
class SpeedRpmDecoderTest {
    @Test
    fun `a minimal stationary response decodes instead of running off the end`() {
        val m = BatteryModel()
        SpeedRpmDecoder.decode("410C00000D00", m)
        assertEquals(0, m.rpm)
        assertEquals(0f, m.speedKmh, 0.001f)
    }

    @Test
    fun `rpm is a u16 at quarter-rpm per bit`() {
        val m = BatteryModel()
        // 0x0FA0 = 4000 quarter-rpm = 1000 rpm.
        SpeedRpmDecoder.decode("410C0FA00D00", m)
        assertEquals(1000, m.rpm)
    }

    @Test
    fun `speed is a single byte stored in km per hour`() {
        val m = BatteryModel()
        // 0x64 = 100 km/h. Imperial conversion belongs to the display.
        SpeedRpmDecoder.decode("410C00000D64", m)
        assertEquals(100f, m.speedKmh, 0.001f)
    }

    @Test
    fun `a full-scale speed byte stays a byte`() {
        val m = BatteryModel()
        SpeedRpmDecoder.decode("410C00000DFF", m)
        assertEquals(255f, m.speedKmh, 0.001f)
    }

    @Test
    fun `trailing bytes after the speed byte are ignored`() {
        val m = BatteryModel()
        SpeedRpmDecoder.decode("410C0FA00D6400000000", m)
        assertEquals(1000, m.rpm)
        assertEquals(100f, m.speedKmh, 0.001f)
    }

    @Test
    fun `rpm vectors preserve unsigned width and truncate fractional revolutions`() {
        val vectors = listOf(0 to 0, 1 to 0, 2 to 0, 32767 to 8191, 32768 to 8192, 65534 to 16383, 65535 to 16383)
        val m = BatteryModel()
        for ((raw, expectedRpm) in vectors) {
            SpeedRpmDecoder.decode("410C%04X0D00".format(raw), m)
            assertEquals("raw rpm $raw", expectedRpm, m.rpm)
        }
    }
}
