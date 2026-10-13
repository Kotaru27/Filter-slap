package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * FIDELITY GUARDS â€” the invariants learned from real-photo QC:
 *
 * 1. OUTPUT DIMENSIONS: output.width/height ALWAYS equal input â€” no pipeline stage may
 *    rescale or crop (crop happens ONLY at the explicit cropBitmap call in the UI layer,
 *    and the UI now defaults to ORIGINAL).
 * 2. NO NEW CLIPPING: the render may never create materially more >=240-luma pixels than
 *    the input had (the temple failure: 2.3% -> 25.6%).
 * 3. VIGNETTE SAFETY: a bright-sky-center/dark-corners SCENE (asymmetric falloff) must NOT
 *    be measured as lens vignette, and even a real vignette correction may never lift
 *    bright pixels (sky) toward clipping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FidelityGuardTest {

    private fun luma(c: Int): Float =
        Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f

    private fun brightFraction(bmp: Bitmap, threshold: Float = 240f): Float {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val n = px.count { luma(it) >= threshold }
        return n.toFloat() / px.size
    }

    private fun runPipeline(bitmap: Bitmap): Bitmap {
        val analysis = ImageAnalyzer.analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        return result
    }

    // === 1. DIMENSION INVARIANT (all fixture shapes) ===

    @Test
    fun `output dimensions always match input dimensions`() {
        val shapes = listOf(
            PhotoRegressionFixtures.balancedGray(100, 100),
            PhotoRegressionFixtures.balancedGray(1152, 1536),   // portrait orientation
            PhotoRegressionFixtures.balancedGray(1536, 1152),   // landscape orientation
            PhotoRegressionFixtures.singlePixel(),
            PhotoRegressionFixtures.fullGradient()
        )
        shapes.forEach { bmp ->
            val out = runPipeline(bmp)
            assertEquals("width must be preserved", bmp.width, out.width)
            assertEquals("height must be preserved", bmp.height, out.height)
            if (bmp !== out) bmp.recycle()
            out.recycle()
        }
    }

    // === 2. NO-NEW-CLIPPING INVARIANT ===

    @Test
    fun `pipeline never creates materially more clipped pixels than input`() {
        // Tolerance: tiny per-channel rounding movements across the 240 shoulder are
        // acceptable (<1.5% absolute). The temple regression was +23 points â€” this guard
        // fails any repeat of that class of failure.
        val fixtures = listOf(
            "balancedGray" to { PhotoRegressionFixtures.balancedGray() },
            "brightGray" to { PhotoRegressionFixtures.brightGray() },
            "highKey" to { PhotoRegressionFixtures.highKey() },
            "clippedHighlights" to { PhotoRegressionFixtures.clippedHighlights() },
            "skyScene" to { PhotoRegressionFixtures.skyScene() },
            "uniformWhite" to { PhotoRegressionFixtures.uniformWhite() },
            "vegetationScene" to { PhotoRegressionFixtures.vegetationScene() }
        )
        fixtures.forEach { (name, factory) ->
            val bmp = factory()
            val out = runPipeline(bmp)
            val before = brightFraction(bmp)
            val after = brightFraction(out)
            assertTrue(
                "$name: bright(>=240) fraction must not jump (before=${"%.3f".format(before)} after=${"%.3f".format(after)})",
                after <= before + 0.015f
            )
            bmp.recycle(); out.recycle()
        }
    }

    @Test
    fun `highlight-heavy temple-like scene is protected`() {
        // Temple signature: bright clipped sky/structure upper half, dark busy street
        // lower half â€” the exact photo class that regressed 2.3% -> 25.6%.
        val w = 256; val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            if (y < h * 3 / 5) {
                // Bright sky + white temple: values near the top of the shoulder, some clipped.
                val v = if (((x / 8) + (y / 8)) % 2 == 0) 250 else 244
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            } else {
                // Dark detailed street (structure in the shadows).
                val g = if (((x / 3) + (y / 3)) % 2 == 0) 30 else 85
                bmp.setPixel(x, y, Color.rgb(g, g, g))
            }
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        val before = brightFraction(bmp)

        // The Whites stretch must not fire on a materially clipped frame.
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("Whites stretch must never fire on a highlight-compromised frame",
            plan.decisions.find { it.parameter == "Whites" })

        val (out, _, _, _) = ImageProcessor.process(bmp, analysis)
        val after = brightFraction(out)
        assertTrue(
            "temple-like frame: bright fraction must not jump (before=${"%.3f".format(before)} after=${"%.3f".format(after)})",
            after <= before + 0.015f
        )
        bmp.recycle(); out.recycle()
    }

    // === 3. VIGNETTE SAFETY ===

    @Test
    fun `asymmetric bright-center scene is NOT measured as vignette`() {
        // The false-positive: bright sky at CENTER-top, dark buildings along ONE side only.
        // Radially asymmetric falloff = scene content, not optics.
        val w = 256; val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val v = when {
                y < h / 3 -> 235                                   // bright sky band (top)
                x < w / 5 -> 60                                    // dark buildings (left edge ONLY)
                else -> 150                                        // mid-tone plaza
            }
            bmp.setPixel(x, y, Color.rgb(v, v, v))
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        assertTrue(
            "asymmetric scene falloff must not read as lens vignette (got ${analysis.lensQuality.vignette})",
            analysis.lensQuality.vignette < 0.15f
        )
    }

    @Test
    fun `vignette correction never lifts bright pixels toward clipping`() {
        // Real symmetric vignette on a BRIGHT frame (e.g. white architecture/overcast sky
        // filling the shot): the correction must recover the darkened corners while the
        // already-bright pixels never get pushed across the 240 shoulder.
        val w = 256; val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val nx = (2f * x / (w - 1)) - 1f
            val ny = (2f * y / (h - 1)) - 1f
            val d2 = nx * nx + ny * ny
            // Bright base (232) with radially symmetric falloff â€” corners land ~135.
            val v = (232f * (1f - 0.42f * d2)).coerceIn(0f, 255f).toInt()
            bmp.setPixel(x, y, Color.rgb(v, v, v))
        }
        val analysis = ImageAnalyzer.analyze(bmp)
        assertTrue("fixture must measure as genuine symmetric vignette (got ${analysis.lensQuality.vignette})",
            analysis.lensQuality.vignette > 0.3f)

        val (out, _, _, _) = ImageProcessor.process(bmp, analysis)
        val before = brightFraction(bmp)
        val after = brightFraction(out)
        assertTrue(
            "vignette correction must not create new >=240 pixels (before=${"%.3f".format(before)} after=${"%.3f".format(after)})",
            after <= before + 0.015f
        )

        // And the dark corners must actually recover.
        fun cornerAvg(b: Bitmap): Int {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            var acc = 0; var cnt = 0
            for (yy in 0 until b.height) for (xx in 0 until b.width) {
                if (xx < 24 || xx >= b.width - 24 || yy < 24 || yy >= b.height - 24) {
                    val c = px[yy * b.width + xx]
                    acc += ((Color.red(c) + Color.green(c) + Color.blue(c)) / 3); cnt++
                }
            }
            return acc / cnt
        }
        assertTrue("corners must recover after vignette correction (before=${cornerAvg(bmp)} after=${cornerAvg(out)})",
            cornerAvg(out) > cornerAvg(bmp))
        bmp.recycle(); out.recycle()
    }
}
