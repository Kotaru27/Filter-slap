package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P4: HUE-BAND REFINEMENT — measured sky/foliage hue repair, gated by evidence.
 *
 * Invariants:
 * 1. A cyan-drifted sky (hue ~200°, coherent, real band) produces a SkyHue decision
 *    rotating toward the 220° anchor, and the render moves sky pixels' hue toward blue.
 * 2. A NATURAL warm sky (sunset) produces NO SkyHue decision — golden hour is intent.
 * 3. A yellow-drifted canopy produces a VegHue decision toward 110°.
 * 4. Scattered hue bands (incoherent) and unmeasured bands produce no decisions.
 * 5. Magnitudes are capped at ±6°.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HueBandRefineTest {

    // Sky fixture with a controllable hue. Proportions mirror the proven landscape fixture
    // (sky = top 110 of 256 rows) so the skyScore gate registers a real band.
    private fun skyScene(hueRgb: Int, lower: Int = Color.rgb(51, 153, 71)): Bitmap {
        val w = 256; val h = 256
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bitmap.setPixel(x, y, if (y < 110) hueRgb else lower)
        }
        return bitmap
    }

    // HSL(205°, 60%, 58%) ≈ cyan-drifted phone sky -> RGB(70, 150, 220)
    private val cyanSky = Color.rgb(70, 150, 220)
    // HSL(220°, 60%, 55%) ≈ natural blue sky
    private val naturalSky = Color.rgb(70, 130, 210)

    @Test
    fun `cyan-drifted sky produces a SkyHue decision toward blue`() {
        val analysis = ImageAnalyzer.analyze(skyScene(cyanSky))
        assertTrue("fixture must detect sky (score=${analysis.skyScore})", analysis.skyScore > 15f)
        assertTrue("fixture must have hue evidence (weight=${analysis.skyHueStats.weight})",
            analysis.skyHueStats.weight > 0f)
        // Circular mean should read ~205° (cyan), not 220°.
        assertTrue("sky band mean should read cyan-side of 220 (mean=${analysis.skyHueStats.meanDeg})",
            analysis.skyHueStats.meanDeg < 215f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val skyHue = plan.decisions.find { it.parameter == "SkyHue" }
        assertNotNull("cyan-drifted sky must produce SkyHue (mean=${analysis.skyHueStats.meanDeg})", skyHue)
        // Cyan (<220): rotation toward the anchor is POSITIVE.
        assertTrue("SkyHue must rotate toward 220 (positive); got ${skyHue!!.magnitude}",
            skyHue.magnitude > 0f)
        assertTrue("SkyHue capped at 6°; got ${skyHue.magnitude}", skyHue.magnitude <= 6f)
    }

    @Test
    fun `natural blue sky produces no SkyHue decision`() {
        val analysis = ImageAnalyzer.analyze(skyScene(naturalSky))
        assertTrue("fixture must detect sky (score=${analysis.skyScore})", analysis.skyScore > 15f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("a sky already at ~220° must not be hue-repaired",
            plan.decisions.find { it.parameter == "SkyHue" })
    }

    @Test
    fun `natural sunset sky is never hue-repaired`() {
        // A REAL sunset: the sky itself is orange (hue ~30°). Orange pixels fail the sky
        // band's 190..250 hue census entirely — warm intent never even votes, so the band
        // is unmeasured and no repair decision can exist.
        val w = 256; val h = 256
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            bitmap.setPixel(x, y, if (y < 110) Color.rgb(240, 160, 90) else Color.rgb(80, 60, 50))
        }
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertEquals("sunset sky must not register as a blue-sky band",
            0f, analysis.skyHueStats.weight, 0.001f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("sunset (orange) skies must never get sky hue repair",
            plan.decisions.find { it.parameter == "SkyHue" })
    }

    @Test
    fun `yellow-drifted canopy produces a VegHue decision toward green`() {
        // Canopy at HSL(75°, 50%, 45%) ≈ yellow-green drift: RGB(102, 172, 57)
        val w = 160; val h = 160
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            // Vegetation-dominant frame (no sky zone content): green everywhere.
            bitmap.setPixel(x, y, Color.rgb(102, 172, 57))
        }
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertTrue("fixture must detect vegetation (score=${analysis.greenScore})", analysis.greenScore > 10f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val vegHue = plan.decisions.find { it.parameter == "VegHue" }
        assertNotNull("yellow-drifted canopy must produce VegHue (mean=${analysis.vegHueStats.meanDeg})", vegHue)
        assertTrue("VegHue must rotate toward 110 (positive); got ${vegHue!!.magnitude}",
            vegHue.magnitude > 0f)
    }

    @Test
    fun `render moves drifted sky hue toward the anchor`() {
        val scene = skyScene(cyanSky)
        val analysis = ImageAnalyzer.analyze(scene)
        val render = ImageProcessor.process(scene, analysis)
        val plan = render.appliedPlan
        val skyHue = plan.decisions.find { it.parameter == "SkyHue" }
        if (skyHue != null) {
            // Measure rendered sky hue (HSL) in a pure-sky patch and compare to input.
            fun skyHueOf(bmp: Bitmap): Float {
                val hsl = FloatArray(3)
                ColorUtilsHsl.to(bmp.getPixel(80, 20), hsl)
                return hsl[0]
            }
            val before = skyHueOf(scene)
            val after = skyHueOf(render.bitmap)
            assertTrue(
                "sky hue must move toward 220 (before=$before after=$after)",
                after > before
            )
        }
        render.bitmap.recycle()
        if (scene !== render.bitmap) scene.recycle()
    }
}

// Small local HSL helper (single-hue conversion for assertions).
private object ColorUtilsHsl {
    fun to(color: Int, out: FloatArray) {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        val max = maxOf(r, g, b); val min = minOf(r, g, b)
        val l = (max + min) / 255f / 2f
        out[2] = l
        if (max == min) { out[0] = 0f; out[1] = 0f; return }
        val d = (max - min) / 255f
        out[1] = if (l > 0.5f) d / (2f - 2f * l) else d / (2f * l)
        val rn = r / 255f; val gn = g / 255f; val bn = b / 255f
        out[0] = when (max) {
            r -> 60f * ((gn - bn) / d).let { if (it < 0) it + 6f else it }
            g -> 60f * ((bn - rn) / d + 2f)
            else -> 60f * ((rn - gn) / d + 4f)
        }
    }
}
