package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageAnalyzerTest {

    private fun assertWithin(tolerance: Float, expected: Float, actual: Float, msg: String = "") {
        assertTrue(
            "$msg Expected $expected ± $tolerance, got $actual",
            abs(expected - actual) <= tolerance
        )
    }

    private fun createSolidBitmap(r: Int, g: Int, b: Int, width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(r, g, b))
        return bitmap
    }

    private fun createHorizontalGradientBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val v = (x * 255) / (width - 1)
            for (y in 0 until height) {
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // 1. All-black (10,10,10): isDarkImage true, avgLuma low, highlightDensity 0.
    //    luma = 10*0.299+10*0.587+10*0.114 = 10.0, which is >= 5 so shadowCrushing = 0.
    //    shadowMask = (1 - 10/50) = 0.8 for every pixel.
    @Test
    fun `all-black image is correctly identified as dark`() {
        val bitmap = createSolidBitmap(10, 10, 10)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("isDarkImage should be true (p75=10/255≈0.039 < 0.4)", result.isDarkImage)
        assertFalse("isBrightImage should be false", result.isBrightImage)
        assertWithin(0.05f, 10f, result.avgLuma, "avgLuma should be ~10")
        assertWithin(0.01f, 0.0f, result.tonalStructure.highlightDensity, "highlightDensity should be 0 (all luma<85)")
        assertWithin(0.01f, 1.0f, result.tonalStructure.shadowDensity, "shadowDensity should be 1.0 (all luma<85)")

        // shadowMask = (1 - luma/50).luma=10 → 0.8
        val expectedShadowMask = (1f - 10f / 50f).coerceIn(0f, 1f)
        assertWithin(0.01f, expectedShadowMask, result.shadowMask.average().toFloat(),
            "shadowMask avg should be ~0.8")

        // luma=10 is NOT < 5, so shadowCrushing should be 0
        assertWithin(0.01f, 0.0f, result.signalQuality.shadowCrushing,
            "shadowCrushing should be 0 (luma=10 is not < 5)")
    }

    // 2. All-white (255,255,255): isBrightImage true, avgLuma ~255, highlightClipping 1.0, shadowDensity 0.
    @Test
    fun `all-white image is correctly identified as bright with clipped highlights`() {
        val bitmap = createSolidBitmap(255, 255, 255)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("isBrightImage should be true (p25=1.0 > 0.6)", result.isBrightImage)
        assertFalse("isDarkImage should be false", result.isDarkImage)
        assertWithin(0.05f, 255f, result.avgLuma, "avgLuma should be ~255")
        assertWithin(0.01f, 1.0f, result.signalQuality.highlightClipping,
            "highlightClipping should be 1.0 (luma=255 > 250)")
        assertWithin(0.01f, 0.0f, result.tonalStructure.shadowDensity, "shadowDensity should be 0")
        assertWithin(0.01f, 1.0f, result.tonalStructure.highlightDensity,
            "highlightDensity should be 1.0 (all luma>=170)")
    }

    // 3. Neutral gray (128,128,128): neither dark nor bright, iqr=0, isLowContrast true,
    //    no gradients, no feature masks on uniform color.
    @Test
    fun `neutral gray image has zero contrast and no feature masks`() {
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)

        assertFalse("isDarkImage should be false (p75≈0.502 >= 0.4)", result.isDarkImage)
        assertFalse("isBrightImage should be false (p25≈0.502 <= 0.6)", result.isBrightImage)
        assertWithin(0.01f, 0.0f, result.tonalStructure.iqr, "iqr should be 0 for uniform image")
        assertTrue("isLowContrast should be true (iqr=0 < 0.2)", result.isLowContrast)
        assertWithin(0.01f, 0.0f, result.signalQuality.edgeStrength,
            "edgeStrength should be 0 for uniform image (no gradients)")
        assertWithin(0.01f, 0.0f, result.skyMask.sum(), "skyMask should be all zeros")
        assertWithin(0.01f, 0.0f, result.vegMask.sum(), "vegMask should be all zeros")
        assertWithin(0.01f, 0.0f, result.skinMask.sum(), "skinMask should be all zeros")
        assertWithin(0.01f, 0.0f, result.metalMask.sum(), "metalMask should be all zeros")
    }

    // 4. Dark image (30,30,30): isDarkImage true, shadowDensity high, shadowMask elevated.
    @Test
    fun `dark image has high shadow density and shadow mask`() {
        val bitmap = createSolidBitmap(30, 30, 30)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("isDarkImage should be true (p75≈0.118 < 0.4)", result.isDarkImage)
        assertWithin(0.01f, 1.0f, result.tonalStructure.shadowDensity,
            "shadowDensity should be 1.0 (all luma=30 < 85)")
        assertWithin(0.01f, 0.0f, result.tonalStructure.highlightDensity,
            "highlightDensity should be 0.0")

        // shadowMask = (1 - luma/50).luma~30 → 0.4
        val expectedShadowMask = (1f - 30f / 50f).coerceIn(0f, 1f)
        assertWithin(0.01f, expectedShadowMask, result.shadowMask.average().toFloat(),
            "shadowMask avg should be ~0.4")
    }

    // 5. Bright image (230,230,230): isBrightImage true, highlightDensity high, brightMask elevated.
    @Test
    fun `bright image has high highlight density and bright mask`() {
        val bitmap = createSolidBitmap(230, 230, 230)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("isBrightImage should be true (p25≈0.902 > 0.6)", result.isBrightImage)
        assertWithin(0.01f, 1.0f, result.tonalStructure.highlightDensity,
            "highlightDensity should be 1.0 (all luma=230 >= 170)")
        assertWithin(0.01f, 0.0f, result.tonalStructure.shadowDensity, "shadowDensity should be 0.0")

        // brightMask = (luma-205)/50. luma~230 → (230-205)/50=0.5
        val expectedBrightMask = (230f - 205f) / 50f
        assertWithin(0.01f, expectedBrightMask, result.brightMask.average().toFloat(),
            "brightMask avg should be ~0.5")
    }

    // 6. 1x1 image: should not crash. The analysis scale is capped at 1.0 (F12) — a 1x1
    //    stays 1x1; upsampling invented interpolated measurements that don't exist in
    //    the source.
    @Test
    fun `1x1 image does not crash and scales to analysis dimensions`() {
        val bitmap = createSolidBitmap(128, 128, 128, width = 1, height = 1)
        val result = ImageAnalyzer.analyze(bitmap)

        assertEquals("analysisWidth should stay 1 (scale capped at 1.0)", 1, result.analysisWidth)
        assertEquals("analysisHeight should stay 1 (scale capped at 1.0)", 1, result.analysisHeight)
        assertEquals("pixelCount should be 1", 1, result.pixelCount)
    }

    // 7. Luminance gradient (256x1): isLowContrast false, significant occupancy,
    //    both shadowDensity and highlightDensity > 0, iqr > 0.2.
    @Test
    fun `luminance gradient has significant contrast and tonal spread`() {
        val bitmap = createHorizontalGradientBitmap(256, 1)
        val result = ImageAnalyzer.analyze(bitmap)

        assertFalse("isLowContrast should be false for gradient (iqr significant)", result.isLowContrast)
        assertTrue("occupancy (contrastRatio) should be > 0.5", result.contrastRatio > 0.5f)
        assertTrue("shadowDensity should be > 0 for gradient", result.tonalStructure.shadowDensity > 0.0f)
        assertTrue("highlightDensity should be > 0 for gradient", result.tonalStructure.highlightDensity > 0.0f)
        assertTrue("iqr should be > 0.2 for gradient", result.tonalStructure.iqr > 0.2f)
    }

    // 8. High-contrast checkerboard: isLowContrast false, significant occupancy.
    //    2x2 with [black, black, white, white]. After scaling, half pixels luma=0, half luma=255.
    @Test
    fun `high-contrast checkerboard has significant contrast`() {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.BLACK)
        bitmap.setPixel(1, 0, Color.BLACK)
        bitmap.setPixel(0, 1, Color.WHITE)
        bitmap.setPixel(1, 1, Color.WHITE)
        val result = ImageAnalyzer.analyze(bitmap)

        assertFalse("isLowContrast should be false for checkerboard", result.isLowContrast)
        assertTrue("occupancy should be > 0.3", result.contrastRatio > 0.3f)
    }

    // 9. Histogram correctness: for uniform gray (128), lumaHistogram[lumaBin] == pixelCount.
    @Test
    fun `histogram is correct for uniform gray image`() {
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)

        val expectedBin = (128 * 0.299f + 128 * 0.587f + 128 * 0.114f).toInt().coerceIn(0, 255)
        assertEquals(
            "lumaHistogram at expected bin should equal pixelCount",
            result.pixelCount, result.lumaHistogram[expectedBin]
        )
        for (i in 0..255) {
            if (i != expectedBin) {
                assertEquals("lumaHistogram[$i] should be 0", 0, result.lumaHistogram[i])
            }
        }
    }

    // 10. Channel clipping: pure red (255,0,0) has r==255, so channelClipping = 1.0.
    @Test
    fun `pure red image has full channel clipping`() {
        val bitmap = createSolidBitmap(255, 0, 0)
        val result = ImageAnalyzer.analyze(bitmap)

        assertWithin(0.01f, 1.0f, result.colorAnalysis.channelClipping,
            "channelClipping should be 1.0 (r==255 for all pixels)")
    }

    // 11. Feature masks on uniform image: skyMask, vegMask, skinMask, metalMask all 0.
    @Test
    fun `uniform gray image has zero feature masks`() {
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)

        assertWithin(0.01f, 0.0f, result.skyMask.sum(), "skyMask should be 0")
        assertWithin(0.01f, 0.0f, result.vegMask.sum(), "vegMask should be 0")
        assertWithin(0.01f, 0.0f, result.skinMask.sum(), "skinMask should be 0")
        assertWithin(0.01f, 0.0f, result.metalMask.sum(), "metalMask should be 0")
    }

    // 12. Sky detection: top 60 rows blue (h~214, s~0.61, luma~121), smooth (variance=0).
    //     skyScore > 0. skyMask non-zero in top rows, zero in bottom rows.
    @Test
    fun `blue sky region is detected in upper portion of image`() {
        val width = 100
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val skyColor = Color.rgb(70, 130, 210)
        for (y in 0 until 60) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, skyColor)
            }
        }
        for (y in 60 until height) {
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
            }
        }
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("skyScore should be > 0 for blue sky region", result.skyScore > 0.0f)

        val aw = result.analysisWidth
        val ah = result.analysisHeight
        val skyBoundary = (60f / height * ah).toInt().coerceIn(0, ah)
        val topMaskSum = result.skyMask.take(skyBoundary * aw).sum()
        assertTrue("skyMask should have non-zero values in top region", topMaskSum > 0.0f)

        val bottomMaskSum = result.skyMask.drop(skyBoundary * aw).sum()
        assertWithin(0.01f, 0.0f, bottomMaskSum, "skyMask should be 0 in bottom gray region")
    }

    // 13. Vegetation detection: green pixels (0,200,0) with h~120, s~1.0.
    //     h in 70..150, s>0.1 → greenScore > 0.
    @Test
    fun `green pixels produce vegetation detection`() {
        val bitmap = createSolidBitmap(0, 200, 0)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("greenScore should be > 0 for green image", result.greenScore > 0.0f)
        assertTrue("vegMask should have non-zero values", result.vegMask.sum() > 0.0f)
    }

    // 14. Skin detection: warm orange (220,160,120) with h~24, s~0.6, luma~173.
    //     h in 10..40, s in 0.2..0.7, luma in 50..200 → skinToneScore > 0.
    @Test
    fun `warm orange pixels produce skin tone detection`() {
        val bitmap = createSolidBitmap(220, 160, 120)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("skinToneScore should be > 0 for skin-tone image", result.skinToneScore > 0.0f)
        assertTrue("skinMask should have non-zero values", result.skinMask.sum() > 0.0f)
    }

    // 15. Noise detection: checkerboard of 80/180. Interior pixels have grad=0 (alternating neighbors
    //     cancel in gx/gy) but high 3x3 variance → noiseMask > 0.
    @Test
    fun `alternating brightness pattern produces noise detection`() {
        val width = 100
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x + y) % 2 == 0) 80 else 180
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("noiseMask should have non-zero values for noisy pattern",
            result.noiseMask.sum() > 0.0f)
        assertTrue("noiseScore should be > 0", result.noiseScore > 0.0f)
    }

    // 16. Detail mask on edge pattern: left half black, right half white.
    //     At the vertical edge, gradMag=255 and variance is high → detailMask > 0.
    @Test
    fun `sharp edges produce non-zero detail mask`() {
        val width = 100
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 2) 0 else 255
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("detailMask should have non-zero values near edge",
            result.detailMask.sum() > 0.0f)
        assertTrue("edgeStrength should be > 0", result.signalQuality.edgeStrength > 0.0f)
    }

    // 17. Report is derived from real analysis data: confidenceScore is a REAL weighted score
    //     (mask evidence + anchor evidence + SNR + subject presence), never a placeholder.
    @Test
    fun `report has truthful derived values and no fake confidence`() {
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("confidenceScore should be a real computed score in 0..100 (got ${result.report.confidenceScore})",
            result.report.confidenceScore in 0..100)
        assertNotNull("sceneCategory should not be null", result.report.sceneCategory)
        assertNotNull("lighting should not be null", result.report.lighting)
        assertNotNull("dynamicRange should not be null", result.report.dynamicRange)
        assertNotNull("histogramBias should not be null", result.report.histogramBias)
        assertNotNull("colorTemperature should not be null", result.report.colorTemperature)
        assertTrue("composition should never be the old Unknown placeholder",
            result.report.composition != "Unknown")
        assertTrue("suggestedEditingStyle should never be the old Standard placeholder",
            result.report.suggestedEditingStyle != "Standard")
    }

    // 18. Recommended crops: only emitted when a dominant subject exists AND a candidate
    //     frame keeps >=85% of subject energy — detection is never permission to crop.
    @Test
    fun `recommended crops respect subject retention`() {
        // No dominant subject (uniform gray): no crop recommendation at all.
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)
        assertTrue("recommendedCrops should be empty for subject-less image", result.recommendedCrops.isEmpty())

        // With a recommendation, it must carry a real reason and sane confidence.
        result.recommendedCrops.forEach { rec ->
            assertTrue("confidence should be in 60..95", rec.confidence in 60..95)
            assertTrue("reason should be non-empty", rec.reason.isNotEmpty())
        }
    }

    // 19. Warm image (high R, low B) produces Warm color temperature.
    @Test
    fun `warm image has warm color temperature`() {
        val bitmap = createSolidBitmap(200, 120, 80)
        val result = ImageAnalyzer.analyze(bitmap)

        assertEquals("colorTemperature should be Warm", "Warm", result.report.colorTemperature)
        assertTrue("isWarm should be true", result.isWarm)
        assertFalse("isCool should be false", result.isCool)
    }

    // 20. Cool image (low R, high B) produces Cool color temperature.
    @Test
    fun `cool image has cool color temperature`() {
        val bitmap = createSolidBitmap(80, 120, 200)
        val result = ImageAnalyzer.analyze(bitmap)

        assertEquals("colorTemperature should be Cool", "Cool", result.report.colorTemperature)
        assertFalse("isWarm should be false", result.isWarm)
        assertTrue("isCool should be true", result.isCool)
    }

    // 21. Neutral gray image has Neutral color temperature.
    @Test
    fun `neutral gray image has neutral color temperature`() {
        val bitmap = createSolidBitmap(128, 128, 128)
        val result = ImageAnalyzer.analyze(bitmap)

        assertEquals("colorTemperature should be Neutral", "Neutral", result.report.colorTemperature)
        assertFalse("isWarm should be false", result.isWarm)
        assertFalse("isCool should be false", result.isCool)
    }

    // 22. (removed) Preset recommendation tests deleted with the dead LuxPreset/
    //     recommendedPresets pipeline — the engine never rendered presets; scene-specific
    //     grading lives in ContextualDecisionEngine's GradingProfile, covered by
    //     GradingProfileTest. Skin/sky/green detection itself is covered by tests 20/21.

    // 25. Report scene category matches dominant feature.
    @Test
    fun `report scene category reflects dominant feature`() {
        // Blue sky in top half
        val width = 100
        val height = 100
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < 60) {
                    bitmap.setPixel(x, y, Color.rgb(70, 130, 210))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
                }
            }
        }
        val result = ImageAnalyzer.analyze(bitmap)

        assertTrue("skyScore should be > 10 for blue sky", result.skyScore > 10f)
        assertEquals("sceneCategory should be Landscape", "Landscape", result.report.sceneCategory)
        assertEquals("photographyGenre should be Landscape", "Landscape", result.report.photographyGenre)
        assertEquals("primarySubject should be Sky", "Sky", result.report.primarySubject)
    }

    // 26. Report lighting reflects tonal state.
    @Test
    fun `report lighting reflects tonal state`() {
        val darkBitmap = createSolidBitmap(30, 30, 30)
        val darkResult = ImageAnalyzer.analyze(darkBitmap)
        assertEquals("Dark image lighting should be Low Light", "Low Light", darkResult.report.lighting)

        val brightBitmap = createSolidBitmap(230, 230, 230)
        val brightResult = ImageAnalyzer.analyze(brightBitmap)
        assertEquals("Bright image lighting should be Bright", "Bright", brightResult.report.lighting)
    }

    // 27. Report dynamic range reflects contrast.
    @Test
    fun `report dynamic range reflects contrast`() {
        val lowContrast = createSolidBitmap(128, 128, 128)
        val lowResult = ImageAnalyzer.analyze(lowContrast)
        assertEquals("Uniform gray dynamic range should be Narrow", "Narrow", lowResult.report.dynamicRange)

        val gradient = createHorizontalGradientBitmap(256, 1)
        val gradResult = ImageAnalyzer.analyze(gradient)
        assertEquals("Gradient dynamic range should be Wide", "Wide", gradResult.report.dynamicRange)
    }

    // 28. Subject mask genuinely isolates a centered, in-focus subject from a smooth background.
    //     Subject = focus-saliency (detail without blur) x center-prior, so the central detailed
    //     checkerboard must score much higher than the uniform gray corners. This is the channel
    //     that lets the engine grade the statue differently from the background.
    @Test
    fun `subject mask emphasizes centered detailed subject over background`() {
        val w = 200
        val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val inCenterPatch = x in (w / 2 - 34)..(w / 2 + 34) && y in (h / 2 - 34)..(h / 2 + 34)
                // Central subject: high-frequency checkerboard. Background: smooth uniform gray.
                val v = if (inCenterPatch) { if (((x + y) / 4) % 2 == 0) 255 else 40 } else 128
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val result = ImageAnalyzer.analyze(bitmap)
        val aw = result.analysisWidth
        val ah = result.analysisHeight

        var centerSum = 0f; var centerCnt = 0
        var cornerSum = 0f; var cornerCnt = 0
        for (y in 0 until ah) {
            for (x in 0 until aw) {
                val ux = x.toFloat() / aw
                val uy = y.toFloat() / ah
                val v = result.subjectMask[y * aw + x]
                if (ux in 0.30f..0.70f && uy in 0.30f..0.70f) { centerSum += v; centerCnt++ }
                if (ux < 0.15f || ux > 0.85f || uy < 0.15f || uy > 0.85f) { cornerSum += v; cornerCnt++ }
            }
        }
        val centerAvg = if (centerCnt > 0) centerSum / centerCnt else 0f
        val cornerAvg = if (cornerCnt > 0) cornerSum / cornerCnt else 0f

        assertTrue(
            "subjectMask center ($centerAvg) should clearly exceed corners ($cornerAvg); a centered subject must be isolated from the smooth background",
            centerAvg > cornerAvg + 0.15f
        )
        assertTrue("subjectMask should be non-zero on the subject (center=$centerAvg)",
            centerAvg > 0.05f)
    }

    // 29. TEXTURE channel distinguishes genuine micro-texture from a single directional outline.
    //     A 2px isotropic checkerboard (grain: gradients in every direction → low coherence) must
    //     score much higher than a single vertical hard edge (gradients all in one direction →
    //     near-perfect coherence → a SHAPE, not texture). This is "fine texture ≠ stronger outlines".
    @Test
    fun `texture mask is high on grain but low on a single edge`() {
        val w = 100
        val h = 100
        val grain = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val edge = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gv = if ((x / 2 + y / 2) % 2 == 0) 128 else 168
                grain.setPixel(x, y, Color.rgb(gv, gv, gv))
                val ev = if (x < w / 2) 128 else 168
                edge.setPixel(x, y, Color.rgb(ev, ev, ev))
            }
        }
        val grainResult = ImageAnalyzer.analyze(grain)
        val edgeResult = ImageAnalyzer.analyze(edge)

        val grainTexture = grainResult.textureMask.average().toFloat()
        val edgeTexture = edgeResult.textureMask.average().toFloat()

        assertTrue(
            "Grain texture ($grainTexture) should clearly exceed single-edge texture ($edgeTexture); a clean outline must NOT be treated as texture",
            grainTexture > edgeTexture * 3f
        )
    }
}
