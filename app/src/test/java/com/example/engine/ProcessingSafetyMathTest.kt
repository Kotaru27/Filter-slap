package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingSafetyMathTest {
    @Test fun `detail path never turns RGB luma into HSL lightness`() {
        // Saturated yellow: RGB luma ~0.886, HSL lightness exactly 0.5.
        assertEquals(0.5f, ProcessingSafetyMath.detailLightness(0.5f, 0.3f, 0.3f, 0.1f, 0.1f), 1e-6f)
        assertEquals(0.55f, ProcessingSafetyMath.detailLightness(0.5f, 0.3f, 0.3f, 0.1f, 0.15f), 1e-6f)
    }

    @Test fun `denoise preserves HSL reference and only transfers luma difference`() {
        assertEquals(0.5f, ProcessingSafetyMath.denoisedLightness(0.5f, 226f, 226f), 1e-6f)
        assertEquals(0.52f, ProcessingSafetyMath.denoisedLightness(0.5f, 226f, 231.1f), 1e-5f)
    }

    @Test fun `hue distance uses degrees and wraps correctly`() {
        assertEquals(2f / 180f, ProcessingSafetyMath.hueDrift(359f, 1f), 1e-6f)
        assertEquals(1f, ProcessingSafetyMath.hueDrift(0f, 180f), 1e-6f)
        assertEquals(0f, ProcessingSafetyMath.hueDrift(35f, 35f), 1e-6f)
    }

    @Test fun `throttling increases monotonically with harm`() {
        assertEquals(0f, ProcessingSafetyMath.throttle(0f), 0f)
        assertEquals(0f, ProcessingSafetyMath.throttle(0.1f), 0f)
        assertEquals(0.35f, ProcessingSafetyMath.throttle(0.35f), 0f)
        assertEquals(1f, ProcessingSafetyMath.throttle(1f), 0f)
        var last = -1f
        for (step in 0..100) {
            val now = ProcessingSafetyMath.throttle(step / 100f)
            assertTrue(now >= last)
            last = now
        }
    }
    @Test fun `noise suppression is bounded monotonic and clean images unchanged`() {
        for (local in listOf(0f, 0.25f, 0.5f, 1f)) {
            var previous = 0f
            for (step in 0..100) {
                val suppression = ProcessingSafetyMath.noiseSuppression(step.toFloat(), local)
                assertTrue(suppression >= previous)
                assertTrue(suppression in 0f..0.75f)
                previous = suppression
            }
            assertEquals(0f, ProcessingSafetyMath.noiseSuppression(0f, local), 0f)
        }
    }

    @Test fun `tint adjustment respects direction and saturated hues`() {
        assertEquals(120, ProcessingSafetyMath.correctedGreen(120, 120, 120, 0.5f))
        assertEquals(120, ProcessingSafetyMath.correctedGreen(120, 120, 120, -0.5f))
        assertEquals(180, ProcessingSafetyMath.correctedGreen(100, 180, 100, 0.5f))
        assertEquals(60, ProcessingSafetyMath.correctedGreen(100, 60, 100, -0.5f))
        assertTrue(ProcessingSafetyMath.correctedGreen(100, 60, 100, 0.5f) >= 60)
        assertTrue(ProcessingSafetyMath.correctedGreen(100, 180, 100, -0.5f) <= 180)
        // Avoid a forced neutralization of highly saturated material colors.
        assertEquals(230, ProcessingSafetyMath.correctedGreen(230, 230, 10, -0.5f))
    }

    @Test fun `safety attenuation converges monotonically without an exhausted ledger`() {
        for (initial in listOf(-0.8f, -0.1f, 0.1f, 0.8f)) {
            for (severity in listOf(0f, 0.1f, 0.2f, 0.5f, 0.8f, 1f)) {
                var magnitude = initial
                repeat(12) {
                    val next = ProcessingSafetyMath.attenuate(magnitude, severity)
                    assertTrue("Magnitude must not increase", kotlin.math.abs(next) <= kotlin.math.abs(magnitude))
                    assertTrue("Sign must not reverse", magnitude * next >= 0f)
                    magnitude = next
                }
                if (severity == 1f) assertEquals(0f, magnitude, 0f)
                if (severity <= 0.1f) assertEquals(initial, magnitude, 0f)
            }
        }
    }

}
