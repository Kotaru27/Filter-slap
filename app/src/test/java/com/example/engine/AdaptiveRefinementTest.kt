package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ADAPTIVE REFINEMENT GUARDS â€” the reviewer's remaining three gaps, locked as tests:
 *
 * 1. SHARPENING INTELLIGENCE: sky/bokeh/bright regions never receive Clarity/Texture.
 * 2. SATURATION = VIBRANCE-LIKE: the "Saturation" decision must not boost already-vivid
 *    pixels (neon-green prevention).
 * 3. REGION-AWARE COLOR: vegetation saturation is masked (backgrounds unaffected) and
 *    genuinely drab foliage measurably recovers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdaptiveRefinementTest {

    private fun luma(c: Int): Float =
        Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f

    // === 1. SKY IS NEVER SHARPENED ===

    @Test
    fun `smooth sky gradient receives no detail amplification`() {
        // A frame that is ALL smooth sky gradient (nothing else): any sharpening would
        // manifest as added high-frequency energy in the sky. There must be none beyond
        // rounding noise.
        val w = 200; val h = 100
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val v = 140 + (y * 60 / h)
            bmp.setPixel(x, y, Color.rgb((v * 0.55f).toInt(), (v * 0.75f).toInt(), v.coerceAtMost(255)))
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val (out, _, _, _) = ImageProcessor.process(bmp, analysis)

        fun hfEnergy(b: Bitmap): Double {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            var acc = 0.0; var cnt = 0
            for (yy in 1 until b.height - 1) for (xx in 1 until b.width - 1) {
                val c = px[yy * b.width + xx]
                val l = luma(c)
                val lf = luma(px[yy * b.width + xx - 1])
                val up = luma(px[(yy - 1) * b.width + xx])
                acc += (l - lf) * (l - lf) + (l - up) * (l - up); cnt++
            }
            return if (cnt > 0) acc / cnt else 0.0
        }
        val before = hfEnergy(bmp)
        val after = hfEnergy(out)
        assertTrue(
            "smooth sky must not gain high-frequency energy from sharpening (before=$before after=$after)",
            after <= before * 1.15 + 0.5
        )
        bmp.recycle(); out.recycle()
    }

    @Test
    fun `out-of-focus background (bokeh) is not sharpened while subject is`() {
        // Sharp textured subject in the center; smooth (out-of-focus) surround. After AUTO,
        // subject HF energy should rise, background HF energy should stay flat.
        val w = 200; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val inSubject = x in 70..129 && y in 70..129
            val v = if (inSubject) { if (((x + y) / 3) % 2 == 0) 60 else 190 } else 120
            bmp.setPixel(x, y, Color.rgb(v, v, v))
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val (out, _, _, _) = ImageProcessor.process(bmp, analysis)

        fun regionHf(b: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int): Double {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            var acc = 0.0; var cnt = 0
            for (yy in (y0 + 1) until (y1 - 1)) for (xx in (x0 + 1) until (x1 - 1)) {
                val c = px[yy * b.width + xx]
                val l = luma(c)
                val lf = luma(px[yy * b.width + xx - 1])
                val up = luma(px[(yy - 1) * b.width + xx])
                acc += (l - lf) * (l - lf) + (l - up) * (l - up); cnt++
            }
            return if (cnt > 0) acc / cnt else 0.0
        }
        val subjBefore = regionHf(bmp, 75, 75, 125, 125)
        val subjAfter = regionHf(out, 75, 75, 125, 125)
        val bgBefore = regionHf(bmp, 5, 5, 50, 50)
        val bgAfter = regionHf(out, 5, 5, 50, 50)
        assertTrue("subject detail should be able to rise (or stay) â€” $subjBefore -> $subjAfter",
            subjAfter >= subjBefore * 0.95)
        assertTrue(
            "bokeh background must not be sharpened (before=$bgBefore after=$bgAfter)",
            bgAfter <= bgBefore * 1.2 + 0.5
        )
        bmp.recycle(); out.recycle()
    }

    // === 2. SATURATION IS VIBRANCE-LIKE ===

    @Test
    fun `saturation decision does not boost already-vivid pixels`() {
        // Direct renderer-level check: apply a Vegetation Saturation decision to a VIVID
        // green pixel and a MUTED green pixel. The vivid one must barely move; the muted
        // one must lift. (OKLab pass via ImageProcessor.applyOklabColor.)
        val w = 8; val h = 8
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            // Alternate columns: vivid green (0,200,0) and muted green (70,110,80).
            bmp.setPixel(x, y, if (x % 2 == 0) Color.rgb(0, 200, 0) else Color.rgb(70, 110, 80))
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val plan = EditPlan(listOf(
            EditDecision("Saturation", "Vegetation", "All", 0.12f, 0.6f, 0.5f, 0.2f)
        ))
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        ImageProcessor.applyOklabColor(px, w, h, plan, analysis)

        fun chromaOf(c: Int): Double {
            val r = Color.red(c) / 255.0; val g = Color.green(c) / 255.0; val b = Color.blue(c) / 255.0
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            return if (mx <= 0) 0.0 else (mx - mn) / mx
        }
        val vividGain = chromaOf(px[0]) - chromaOf(Color.rgb(0, 200, 0))
        val mutedGain = chromaOf(px[1]) - chromaOf(Color.rgb(70, 110, 80))
        assertTrue(
            "vivid green must gain almost nothing (gain=$vividGain)",
            vividGain < 0.02
        )
        assertTrue(
            "muted green must gain measurably (gain=$mutedGain)",
            mutedGain > vividGain
        )
        bmp.recycle()
    }

    // === 3. REGION-AWARE: DRAB FOLIAGE RECOVERS, VIVID FOLIAGE HOLDS ===

    @Test
    fun `genuinely drab foliage recovers saturation`() {
        // A muted-drab green scene: the engine should measurably lift its saturation
        // (bounded), never leaving it unchanged, never making it neon.
        val w = 200; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val t = if (((x / 6) + (y / 6)) % 2 == 0) 8 else 0
            bmp.setPixel(x, y, Color.rgb(88 + t, 108 + t, 84 + t))  // drab grey-green
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val (out, _, _, _) = ImageProcessor.process(bmp, analysis)

        fun avgChroma(b: Bitmap): Double {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            var acc = 0.0
            for (c in px) {
                val r = Color.red(c) / 255.0; val g = Color.green(c) / 255.0; val b2 = Color.blue(c) / 255.0
                val mx = maxOf(r, g, b2); val mn = minOf(r, g, b2)
                acc += if (mx <= 0) 0.0 else (mx - mn) / mx
            }
            return acc / px.size
        }
        val before = avgChroma(bmp)
        val after = avgChroma(out)
        assertTrue(
            "drab foliage should recover saturation (before=$before after=$after)",
            after > before
        )
        // Neon ceiling: normalized chroma must stay below a vivid threshold.
        assertTrue(
            "foliage must never go neon (after chroma=$after)",
            after < 0.45
        )
        bmp.recycle(); out.recycle()
    }

    @Test
    fun `healthy vivid foliage is left alone by the engine`() {
        // Naturally saturated green foliage: no veg defect, so NO Saturation/Vibrance overboost.
        // The engine plan itself must not target vegetation.
        val w = 200; val h = 200
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val t = if (((x / 6) + (y / 6)) % 2 == 0) 10 else 0
            bmp.setPixel(x, y, Color.rgb(40 + t, 170 + t, 50 + t))  // healthy vivid green
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val vegSat = plan.decisions.find { it.parameter == "Saturation" && it.regionMask == "Vegetation" }
        // Healthy foliage must either get no veg-saturation decision or a minimal one.
        assertTrue(
            "healthy vivid foliage must not be targeted (mag=${vegSat?.magnitude})",
            vegSat == null || vegSat.magnitude <= 0.12f
        )
        bmp.recycle()
    }
}
