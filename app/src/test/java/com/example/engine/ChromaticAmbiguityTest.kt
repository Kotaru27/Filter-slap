package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Evidence test, not photo-specific tuning. A single chromaticity offers no way
 * to distinguish a colored material from a neutral material under colored light.
 * These tests exercise several signed cast axes and contrast patterns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ChromaticAmbiguityTest {

    private fun frame(w: Int, h: Int, px: (Int, Int) -> Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, px(x, y))
        return bmp
    }

    @Test
    fun `uniform chromaticity must not be forced toward gray`() {
        // Varied hue axes, including magenta/green and warm/cool channel offsets.
        val materials = arrayOf(
            intArrayOf(110, 165, 112), intArrayOf(165, 108, 162),
            intArrayOf(180, 130, 90), intArrayOf(88, 108, 84)
        )
        for (m in materials) {
            val b = frame(90, 70) { _, _ -> Color.rgb(m[0], m[1], m[2]) }
            val a = ImageAnalyzer.analyze(b)
            assertTrue("measured evidence must be finite", a.colorAnalysis.chromaDispersion.isFinite())
            assertTrue("uniform material has no cross-material chromatic reference", a.colorAnalysis.chromaDispersion < 0.015f)
            val plan = ContextualDecisionEngine.evaluate(a)
            assertNull("can't infer neutral WB from a uniform chromaticity",
                plan.decisions.find { it.parameter == "WhiteBalance" })
            assertNull("can't infer green/magenta cast from a uniform chromaticity",
                plan.decisions.find { it.parameter == "WhiteBalanceTint" })
            b.recycle()
        }
    }

    @Test
    fun `luminance modulation must not masquerade as chromatic diversity`() {
        val b = frame(120, 80) { x, y ->
            // Proportional brightness changes, channel ratios unchanged.
            val gain = if ((x / 7 + y / 7) % 2 == 0) 0.75f else 1f
            Color.rgb((120 * gain).toInt(), (150 * gain).toInt(), (110 * gain).toInt())
        }
        val a = ImageAnalyzer.analyze(b)
        assertTrue("brightness-only changes don't provide a neutral color reference (spread=${a.colorAnalysis.chromaDispersion})",
            a.colorAnalysis.chromaDispersion < 0.015f)
        val plan = ContextualDecisionEngine.evaluate(a)
        assertNull("material color must not be assumed to be a green cast",
            plan.decisions.find { it.parameter == "WhiteBalanceTint" })
        b.recycle()
    }

    @Test
    fun `multiple chromaticities produce measurable color evidence`() {
        val b = frame(120, 120) { x, y ->
            when ((x / 30 + y / 30) % 4) {
                0 -> Color.rgb(105, 135, 100)
                1 -> Color.rgb(165, 115, 100)
                2 -> Color.rgb(100, 120, 172)
                else -> Color.rgb(130, 130, 130)
            }
        }
        val a = ImageAnalyzer.analyze(b)
        assertTrue("distinct chromaticities should not be marked monochromatic", a.colorAnalysis.chromaDispersion > 0.015f)
        b.recycle()
    }
}
