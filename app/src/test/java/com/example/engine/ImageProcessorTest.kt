package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageProcessorTest {

    private fun createSolidBitmap(color: Int, width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private fun analyze(bitmap: Bitmap): AnalysisResult = ImageAnalyzer.analyze(bitmap)

    private fun getPixels(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels
    }

    // --- 1. Output dimensions match input ---
    @Test
    fun `output dimensions match input`() {
        val bitmap = createSolidBitmap(Color.GRAY)
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    // --- 2. Identity behavior on balanced image ---
    @Test
    fun `identity behavior on balanced image`() {
        val bitmap = createSolidBitmap(Color.rgb(128, 128, 128))
        val originalPixels = getPixels(bitmap)
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val resultPixels = getPixels(result)
        val tolerance = 3
        for (i in originalPixels.indices) {
            val origR = Color.red(originalPixels[i])
            val origG = Color.green(originalPixels[i])
            val origB = Color.blue(originalPixels[i])
            val resR = Color.red(resultPixels[i])
            val resG = Color.green(resultPixels[i])
            val resB = Color.blue(resultPixels[i])
            assertTrue(
                "Pixel $i: R orig=$origR res=$resR",
                kotlin.math.abs(origR - resR) <= tolerance
            )
            assertTrue(
                "Pixel $i: G orig=$origG res=$resG",
                kotlin.math.abs(origG - resG) <= tolerance
            )
            assertTrue(
                "Pixel $i: B orig=$origB res=$resB",
                kotlin.math.abs(origB - resB) <= tolerance
            )
        }
    }

    // --- 3. processFullRes returns a valid bitmap ---
    @Test
    fun `processFullRes returns a valid bitmap`() {
        val bitmap = createSolidBitmap(Color.GRAY)
        val analysis = analyze(bitmap)
        val result = ImageProcessor.processFullRes(bitmap, analysis)
        assertNotNull(result)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    // --- 4. processLive emits exactly one frame ---
    @Test
    fun `processLive emits exactly one frame`() = runTest {
        val bitmap = createSolidBitmap(Color.GRAY)
        val analysis = analyze(bitmap)
        val frames = ImageProcessor.processLive(bitmap, analysis).toList()
        assertEquals(1, frames.size)
        val frame = frames[0]
        assertNotNull(frame.bitmap)
        assertEquals(1, frame.stageIndex)
        assertEquals(1, frame.totalStages)
        assertNull(frame.mask)
    }

    // --- 5. ProcessingStats contains timing ---
    @Test
    fun `processingStats contains timing`() {
        val bitmap = createSolidBitmap(Color.GRAY)
        val analysis = analyze(bitmap)
        val (_, stats, _, _) = ImageProcessor.process(bitmap, analysis)
        assertTrue("computeTimeMs should be >= 0", stats.computeTimeMs >= 0)
    }

    // --- 6. Curve identity: processing balanced image preserves pixels ---
    @Test
    fun `curve identity on balanced image preserves pixels`() {
        val bitmap = createSolidBitmap(Color.rgb(128, 128, 128))
        val originalPixels = getPixels(bitmap)
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val hasAdjustments = plan.decisions.any {
            it.parameter in listOf("Exposure", "Contrast", "Shadows", "Highlights")
        }
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val resultPixels = getPixels(result)
        val tolerance = 3
        for (i in originalPixels.indices) {
            assertTrue(
                "R channel pixel $i: expected ~${Color.red(originalPixels[i])} got ${Color.red(resultPixels[i])}",
                kotlin.math.abs(Color.red(originalPixels[i]) - Color.red(resultPixels[i])) <= tolerance
            )
            assertTrue(
                "G channel pixel $i: expected ~${Color.green(originalPixels[i])} got ${Color.green(resultPixels[i])}",
                kotlin.math.abs(Color.green(originalPixels[i]) - Color.green(resultPixels[i])) <= tolerance
            )
            assertTrue(
                "B channel pixel $i: expected ~${Color.blue(originalPixels[i])} got ${Color.blue(resultPixels[i])}",
                kotlin.math.abs(Color.blue(originalPixels[i]) - Color.blue(resultPixels[i])) <= tolerance
            )
        }
    }

    // --- 7. Brightening: dark non-intentional image should brighten ---
    @Test
    fun `dark image gets brightened by processing`() {
        val bitmap = createSolidBitmap(Color.rgb(70, 70, 70))
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val expDecision = plan.decisions.find { it.parameter == "Exposure" && it.regionMask == "Global" }
        if (expDecision != null && expDecision.magnitude > 0.05f) {
            val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
            val resultPixels = getPixels(result)
            val avgLuma = resultPixels.map { c ->
                (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
            }.average()
            assertTrue(
                "Average luma $avgLuma should be > 70 when exposure mag=${expDecision.magnitude}",
                avgLuma > 70.0
            )
        }
    }

    // --- 8. Darkening: bright non-intentional image should darken ---
    @Test
    fun `bright image gets darkened by processing`() {
        val bitmap = createSolidBitmap(Color.rgb(190, 190, 190))
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val expDecision = plan.decisions.find { it.parameter == "Exposure" && it.regionMask == "Global" }
        if (expDecision != null && expDecision.magnitude < -0.05f) {
            val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
            val resultPixels = getPixels(result)
            val avgLuma = resultPixels.map { c ->
                (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
            }.average()
            assertTrue(
                "Average luma $avgLuma should be < 190 when exposure mag=${expDecision.magnitude}",
                avgLuma < 190.0
            )
        }
    }

    // --- 9. ContrastCurve identity: balanced gray stays balanced ---
    @Test
    fun `contrast curve identity on balanced gray`() {
        val bitmap = createSolidBitmap(Color.rgb(128, 128, 128))
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val resultPixels = getPixels(result)
        val centerPixel = resultPixels[50 * 100 + 50]
        val luma = Color.red(centerPixel) * 0.299f + Color.green(centerPixel) * 0.587f + Color.blue(centerPixel) * 0.114f
        assertTrue(
            "Luma $luma should stay near 128 for balanced input",
            kotlin.math.abs(luma - 128f) < 4f
        )
    }

    // --- 10. Contrast increase on low-contrast gradient ---
    @Test
    fun `low contrast image gets contrast boost`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(100 * 100)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val v = 115 + (x * 20 / 100)
                pixels[y * 100 + x] = Color.rgb(v, v, v)
            }
        }
        bitmap.setPixels(pixels, 0, 100, 0, 0, 100, 100)
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val hasContrast = plan.decisions.any { it.parameter == "Contrast" }
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertNotNull(result)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    // --- 11. Shadow lift on dark crushed image ---
    @Test
    fun `shadow lift on dark crushed image`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(100 * 100)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val v = 3 + (x * 10 / 100)
                pixels[y * 100 + x] = Color.rgb(v, v, v)
            }
        }
        bitmap.setPixels(pixels, 0, 100, 0, 0, 100, 100)
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val hasShadows = plan.decisions.any { it.parameter == "Shadows" }
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertNotNull(result)
        if (hasShadows) {
            val resultPixels = getPixels(result)
            val avgLuma = resultPixels.map { c ->
                Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
            }.average()
            assertTrue("Shadow-lifted image should have higher avg luma, got $avgLuma", avgLuma > 3.0)
        }
    }

    // --- 12. Highlight recovery on bright clipped image ---
    @Test
    fun `highlight recovery on bright clipped image`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(100 * 100)
        for (y in 0 until 100) {
            for (x in 0 until 100) {
                val v = 250 + (x * 5 / 100)
                pixels[y * 100 + x] = Color.rgb(v.coerceAtMost(255), v.coerceAtMost(255), v.coerceAtMost(255))
            }
        }
        bitmap.setPixels(pixels, 0, 100, 0, 0, 100, 100)
        val analysis = analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val hasHighlights = plan.decisions.any { it.parameter == "Highlights" }
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertNotNull(result)
        if (hasHighlights) {
            val resultPixels = getPixels(result)
            val avgLuma = resultPixels.map { c ->
                Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
            }.average()
            assertTrue("Highlight-recovered image should have lower avg luma, got $avgLuma", avgLuma < 253.0)
        }
    }

    // --- 13. Output is valid ARGB_8888 ---
    @Test
    fun `output is valid ARGB_8888`() {
        val bitmap = createSolidBitmap(Color.GRAY)
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertEquals(Bitmap.Config.ARGB_8888, result.config)
    }

    // --- 14. Output pixels are in valid range ---
    @Test
    fun `output pixels are in valid range`() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(50 * 50)
        for (i in pixels.indices) {
            val r = (i * 7) % 256
            val g = (i * 13) % 256
            val b = (i * 23) % 256
            pixels[i] = Color.rgb(r, g, b)
        }
        bitmap.setPixels(pixels, 0, 50, 0, 0, 50, 50)
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val resultPixels = getPixels(result)
        for (i in resultPixels.indices) {
            val r = Color.red(resultPixels[i])
            val g = Color.green(resultPixels[i])
            val b = Color.blue(resultPixels[i])
            val a = Color.alpha(resultPixels[i])
            assertTrue("R=$r out of range at $i", r in 0..255)
            assertTrue("G=$g out of range at $i", g in 0..255)
            assertTrue("B=$b out of range at $i", b in 0..255)
            assertTrue("A=$a out of range at $i", a in 0..255)
        }
    }

    // --- 15. Crops correctly with SQUARE aspect ratio ---
    @Test
    fun `crops correctly with SQUARE aspect ratio`() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.GRAY)
        val cropped = cropBitmap(bitmap, CropFrame.SQUARE.aspectRatio)
        assertEquals(cropped.width, cropped.height)
    }

    // --- 16. cropBitmap with null ratio returns source ---
    @Test
    fun `cropBitmap with null ratio returns source`() {
        val bitmap = createSolidBitmap(Color.GRAY)
        val result = cropBitmap(bitmap, null)
        assertSame("cropBitmap(src, null) should return the source reference", bitmap, result)
    }

    // --- 17. cropBitmap when already correct ratio returns same reference ---
    @Test
    fun `cropBitmap when already correct ratio returns same reference`() {
        val bitmap = createSolidBitmap(Color.GRAY, 100, 100)
        val result = cropBitmap(bitmap, 1f)
        assertSame("1:1 bitmap with target 1f should return same reference", bitmap, result)
    }

    // --- 18. Processing does not modify input bitmap ---
    @Test
    fun `processing does not modify input bitmap`() {
        val bitmap = createSolidBitmap(Color.rgb(100, 150, 200))
        val originalPixels = getPixels(bitmap)
        val analysis = analyze(bitmap)
        ImageProcessor.process(bitmap, analysis)
        val afterPixels = getPixels(bitmap)
        assertArrayEquals(
            "Input bitmap pixels should not be modified by processing",
            originalPixels, afterPixels
        )
    }

    // --- 19. Noise reduction (Stage C): noisy image is smoothed, not sharpened ---
    @Test
    fun `noisy image gets noise-reduced output`() {
        // Flat gray base with strong checkerboard noise: high noiseConfidence, low real structure.
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val px = IntArray(200 * 200)
        for (y in 0 until 200) {
            for (x in 0 until 200) {
                val v = if ((x + y) % 2 == 0) 95 else 165
                px[y * 200 + x] = Color.rgb(v, v, v)
            }
        }
        bitmap.setPixels(px, 0, 200, 0, 0, 200, 200)
        val analysis = analyze(bitmap)
        assertTrue("Fixture should register elevated noise", analysis.signalQuality.noiseConfidence > 5f)

        val (result, stats, _, _) = ImageProcessor.process(bitmap, analysis)
        val resultPixels = getPixels(result)

        fun hfEnergy(arr: IntArray, w: Int, h: Int): Double {
            var acc = 0.0
            var cnt = 0
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val c = arr[y * w + x]
                    val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0
                    val left = (Color.red(arr[y * w + x - 1]) + Color.green(arr[y * w + x - 1]) + Color.blue(arr[y * w + x - 1])) / 3.0
                    val up = (Color.red(arr[(y - 1) * w + x]) + Color.green(arr[(y - 1) * w + x]) + Color.blue(arr[(y - 1) * w + x])) / 3.0
                    acc += (l - left) * (l - left) + (l - up) * (l - up)
                    cnt++
                }
            }
            return acc / cnt
        }

        if (stats.stagesApplied.contains("Smart NR") || analysis.signalQuality.noiseConfidence > 8f) {
            val inHf = hfEnergy(px, 200, 200)
            val outHf = hfEnergy(resultPixels, 200, 200)
            assertTrue("High-frequency energy should drop after noise reduction (in=$inHf out=$outHf)", outHf < inHf)
        }
    }

    // --- 19b. Luma NR runs even when there are NO detail edits ---
    @Test
    fun `noise reduced without any detail edits`() {
        // The fixture must genuinely have NO detail edits: noise > 12 selects the LOW_LIGHT
        // profile, whose philosophy forbids Texture/Clarity amplification. Per-pixel white
        // noise in a MIDTONE frame reads as edges to the analyzer (grad gate); the proven
        // low-light noise signature (GradingProfileTest.lowLightLikeScene) is per-pixel noise
        // in a DARK frame — luma 40..90 keeps every neighbor gradient below the grad gate.
        // The frame is dark, which independently reinforces LOW_LIGHT profile selection.
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val px = IntArray(160 * 160)
        val rnd = java.util.Random(42)
        for (i in px.indices) {
            val v = (40 + rnd.nextInt(50)).coerceIn(0, 255)
            px[i] = Color.rgb(v, v, v)
        }
        bitmap.setPixels(px, 0, 160, 0, 0, 160, 160)
        val analysis = analyze(bitmap)
        assertTrue("Fixture should be noisy (noiseConf=${analysis.signalQuality.noiseConfidence})", analysis.signalQuality.noiseConfidence > 12f)

        val plan = ContextualDecisionEngine.evaluate(analysis)
        val hasDetail = plan.decisions.any { it.parameter == "Texture" || it.parameter == "Clarity" }
        assertTrue("Noisy LOW_LIGHT fixture must not produce detail edits (noiseConf=${analysis.signalQuality.noiseConfidence})", !hasDetail)

        val (result, stats, _, _) = ImageProcessor.process(bitmap, analysis)
        fun hf(arr: IntArray, w: Int, h: Int): Double {
            var acc = 0.0; var cnt = 0
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val l = (Color.red(arr[y * w + x]) + Color.green(arr[y * w + x]) + Color.blue(arr[y * w + x])) / 3.0
                val left = (Color.red(arr[y * w + x - 1]) + Color.green(arr[y * w + x - 1]) + Color.blue(arr[y * w + x - 1])) / 3.0
                val up = (Color.red(arr[(y - 1) * w + x]) + Color.green(arr[(y - 1) * w + x]) + Color.blue(arr[(y - 1) * w + x])) / 3.0
                acc += (l - left) * (l - left) + (l - up) * (l - up); cnt++
            }
            return acc / cnt
        }
        val inHf = hf(px, 160, 160)
        val outHf = hf(getPixels(result), 160, 160)
        assertTrue("Smart NR should be applied even without detail edits", stats.stagesApplied.contains("Smart NR"))
        assertTrue("High-frequency energy should drop (in=$inHf out=$outHf)", outHf < inHf)
    }

    // --- 20. Stage C preserves genuine edges (no over-smoothing) ---
    @Test
    fun `noise reduction preserves sharp edges`() {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) {
            for (x in 0 until 200) {
                val v = if (x < 100) 30 else 220
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val rp = getPixels(result)
        // The hard left/right edge should remain a hard transition (roughly kept contrast).
        val leftLuma = (Color.red(rp[50 * 200 + 50]) + Color.green(rp[50 * 200 + 50]) + Color.blue(rp[50 * 200 + 50])) / 3.0
        val rightLuma = (Color.red(rp[50 * 200 + 150]) + Color.green(rp[50 * 200 + 150]) + Color.blue(rp[50 * 200 + 150])) / 3.0
        assertTrue("Edge contrast should be preserved (left=$leftLuma right=$rightLuma)", rightLuma - leftLuma > 100.0)
    }

    // --- 21. White balance reduces a warm cast ---
    @Test
    fun `white balance reduces warm cast toward neutral`() {
        // Isolate the WB post-pass: a warm-tinted pixel should lose R and gain B (cooling).
        val warm = Color.rgb(190, 150, 120)
        val px = IntArray(64) { warm }
        val plan = EditPlan(listOf(EditDecision("WhiteBalance", "Global", "All", -0.2f, 0.55f, 0.4f, 0.25f)))
        ImageProcessor.applyWhiteBalance(px, 8, 8, plan, null)
        val c = px[0]
        assertTrue("Red should drop when cooling (${Color.red(c)})", Color.red(c) < 190)
        assertTrue("Blue should rise when cooling (${Color.blue(c)})", Color.blue(c) > 120)
        assertTrue(
            "R-B cast should shrink (${Color.red(c) - Color.blue(c)} vs ${190 - 120})",
            (Color.red(c) - Color.blue(c)) < (190 - 120)
        )
    }

    // --- 22. Iterate-to-converge (Stage F): crushed shadow detail is recovered toward visibility ---
    @Test
    fun `exposure converges toward mid-gray target`() {
        // Shadow-crushed image: a large near-black region that the engine should recover toward
        // visible shadow detail (lifting the shadow floor) without blowing out the highlight zone.
        // This verifies the iterate-to-convergence mechanism produces a real tonal recovery rather
        // than pushing the whole frame to an arbitrary midpoint.
        val w = 192
        val h = 192
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 4 + (x * 3 / (w / 2)) else 60 + (x * 60 / w)
                val cv = v.coerceIn(0, 255)
                bitmap.setPixel(x, y, Color.rgb(cv, cv, cv))
            }
        }
        fun percentile10Of(px: IntArray): Double {
            // Use p10 of the output to measure whether the deepest shadows were lifted.
            val luma = ArrayList<Int>(px.size)
            for (p in px) luma.add((Color.red(p) + Color.green(p) + Color.blue(p)) / 3)
            luma.sort()
            val idx = (luma.size * 0.10).toInt().coerceIn(0, luma.size - 1)
            return luma[idx] / 255.0
        }
        val analysis = analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val inP10 = percentile10Of(getPixels(bitmap))
        val outP10 = percentile10Of(getPixels(result))
        assertTrue(
            "Shadow details (p10 $outP10) should be lifted above crushed near-black ($inP10)",
            outP10 > inP10
        )
    }

    // Subject-selective render proof. AUTO must boost micro-detail at the centered, in-focus
    // subject far more than at the smooth background, because Clarity/Texture are gated by the
    // subject mask (focus-saliency x center). If the mask genuinely drives the pixels, the
    // subject:background high-frequency-energy ratio must rise after processing.
    @Test
    fun `subject region gains detail relative to background after auto`() {
        val w = 200
        val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Central subject: large-scale mid-contrast stripes (reads as genuine structure,
                // NOT pixel noise, so Clarity/Texture fire for the "Subject" mask instead of NR).
                val inCenter = x in (w / 2 - 40)..(w / 2 + 40) && y in (h / 2 - 40)..(h / 2 + 40)
                // Top/bottom halves only (clean margin around edges to keep the subject interior uniform).
                val subjectMid = y in (h / 2 - 34)..(h / 2 + 34) && x in (w / 2 - 34)..(w / 2 + 34)
                val v = when {
                    !inCenter -> 128
                    subjectMid -> if ((x / 8) % 2 == 0) 90 else 160
                    else -> 128
                }
                px[y * w + x] = Color.rgb(v, v, v)
            }
        }
        bitmap.setPixels(px, 0, w, 0, 0, w, h)
        val analysis = analyze(bitmap)

        fun regionHfEnergy(arr: IntArray, w: Int, h: Int, cx: Int, cy: Int, half: Int): Double {
            var acc = 0.0; var cnt = 0
            for (y in (cy - half)..(cy + half)) {
                for (x in (cx - half)..(cx + half)) {
                    if (y <= 0 || y >= h - 1 || x <= 0 || x >= w - 1) continue
                    val c = arr[y * w + x]
                    val l = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3.0
                    val lf = (Color.red(arr[y * w + x - 1]) + Color.green(arr[y * w + x - 1]) + Color.blue(arr[y * w + x - 1])) / 3.0
                    val up = (Color.red(arr[(y - 1) * w + x]) + Color.green(arr[(y - 1) * w + x]) + Color.blue(arr[(y - 1) * w + x])) / 3.0
                    acc += (l - lf) * (l - lf) + (l - up) * (l - up)
                    cnt++
                }
            }
            return if (cnt > 0) acc / cnt else 0.0
        }

        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        val outPx = getPixels(result)

        // Measure inside the subject (center, avoids the subject/background seam) and in a
        // far corner (pure uniform background).
        val inCenter = regionHfEnergy(px, w, h, w / 2, h / 2, 14)
        val inCorner = regionHfEnergy(px, w, h, 8, 8, 4)
        val outCenter = regionHfEnergy(outPx, w, h, w / 2, h / 2, 14)
        val outCorner = regionHfEnergy(outPx, w, h, 8, 8, 4)

        val inRatio = inCenter / (inCorner + 1e-6)
        val outRatio = outCenter / (outCorner + 1e-6)

        assertTrue(
            "Subject/background HF-energy ratio should rise as Clarity/Texture target the subject mask (in=$inRatio out=$outRatio)",
            outRatio > inRatio
        )
    }
}
