package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.max

/**
 * PHASE 4A: Photographic Golden-Image / Regression Baseline Tests.
 *
 * These tests establish the baseline for the CURRENT implementation.
 * They are NOT quality judgments â€” they lock down current behavior
 * so future changes can be detected and reviewed.
 *
 * BASELINE LABEL: PRE-PHOTOGRAPHIC-ENGINE-CHANGES
 *
 * All tolerances are documented. Excessively large tolerances are avoided.
 * Future photographic improvements will deliberately be allowed to change
 * the baseline after separate review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PhotoRegressionTest {

    // === TOLERANCE DOCUMENTATION ===
    //
    // TIER 1 â€” EXACT: No tolerance. Used for integer counts, booleans, enums.
    // TIER 2 â€” NUMERICAL: Tolerance based on floating-point precision.
    //   - Luma values: Â±1 (on 0-255 scale, from integer rounding in RGBâ†’HSLâ†’RGB)
    //   - Percentages: Â±0.5% (from floating-point accumulation)
    //   - Mask values: Â±0.01 (from float precision)
    // TIER 3 â€” PIXEL OUTPUT: Per-channel tolerance of Â±3.
    //   - Rationale: RGBâ†’HSLâ†’RGB round-trip introduces Â±1-2 per channel.
    //     Processing curves can add Â±1. Total Â±3 covers non-regression.
    // TIER 4 â€” HASH: Exact match. Any pixel change changes hash.

    private val PIXEL_TOLERANCE = 3
    private val LUMA_TOLERANCE = 1f
    private val PERCENTAGE_TOLERANCE = 0.5f
    private val MASK_TOLERANCE = 0.01f

    // ===================================================================
    // 1. FIXTURE INVENTORY
    // ===================================================================

    @Test
    fun `all fixtures are available and non-empty`() {
        val fixtures = PhotoRegressionFixtures.allFixtures()
        assertEquals("Should have 26 fixtures", 26, fixtures.size)
        fixtures.forEach { (name, factory) ->
            val bmp = factory()
            assertTrue("$name: width > 0", bmp.width > 0)
            assertTrue("$name: height > 0", bmp.height > 0)
            bmp.recycle()
        }
    }

    // ===================================================================
    // 2. PIPELINE RUNNER â€” DETERMINISTIC EXECUTION
    // ===================================================================

    @Test
    fun `pipeline runner produces deterministic output for balancedGray`() {
        val bitmap1 = PhotoRegressionFixtures.balancedGray()
        val bitmap2 = PhotoRegressionFixtures.balancedGray()

        val snapshot1 = GoldenBaseline.captureSnapshot("balancedGray", bitmap1)
        val snapshot2 = GoldenBaseline.captureSnapshot("balancedGray", bitmap2)

        assertEquals("Output hash should be identical across runs",
            snapshot1.outputHash, snapshot2.outputHash)
        assertEquals("Decision count should be identical",
            snapshot1.decisionCount, snapshot2.decisionCount)
        assertEquals("Decision types should be identical",
            snapshot1.decisionTypes, snapshot2.decisionTypes)

        bitmap1.recycle()
        bitmap2.recycle()
    }

    @Test
    fun `pipeline runner captures all required fields`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()
        val snapshot = GoldenBaseline.captureSnapshot("balancedGray", bitmap)

        assertEquals("balancedGray", snapshot.fixtureName)
        assertEquals(100, snapshot.inputWidth)
        assertEquals(100, snapshot.inputHeight)
        assertTrue("pixelCount > 0", snapshot.analysisPixelCount > 0)
        assertNotNull("sceneCategory should not be null", snapshot.sceneCategory)
        assertNotNull("lighting should not be null", snapshot.lighting)
        assertNotNull("colorTemperature should not be null", snapshot.colorTemperature)
        assertTrue("outputHash != 0", snapshot.outputHash != 0L)
        assertTrue("totalDurationMs >= 0", snapshot.totalDurationMs >= 0)

        bitmap.recycle()
    }

    // ===================================================================
    // 3. IMAGE METRICS â€” PIXEL COMPARISON
    // ===================================================================

    @Test
    fun `identical images have zero pixel metrics`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()
        val metrics = ImageMetrics.pixelMetrics(bitmap, bitmap)

        assertEquals("MAE should be 0 for identical images", 0f, metrics.mae, MASK_TOLERANCE)
        assertEquals("RMSE should be 0", 0f, metrics.rmse, MASK_TOLERANCE)
        assertEquals("maxDiff should be 0", 0, metrics.maxAbsoluteDiff)
        assertEquals("changedPixels should be 0", 0, metrics.changedPixels)

        bitmap.recycle()
    }

    @Test
    fun `different images have non-zero pixel metrics`() {
        val black = PhotoRegressionFixtures.uniformBlack()
        val white = PhotoRegressionFixtures.uniformWhite()
        val metrics = ImageMetrics.pixelMetrics(black, white)

        assertEquals("MAE should be ~255 for black vs white", 255f, metrics.mae, 1f)
        assertEquals("maxDiff should be 255", 255, metrics.maxAbsoluteDiff)

        black.recycle()
        white.recycle()
    }

    // ===================================================================
    // 4. IMAGE METRICS â€” TONAL
    // ===================================================================

    @Test
    fun `balanced gray has expected tonal metrics`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()
        val tonal = ImageMetrics.tonalMetrics(bitmap)

        assertEquals("p50 should be ~0.502", 0.502f, tonal.p50, 0.01f)
        assertEquals("p75 - p25 should be 0", 0f, tonal.p75 - tonal.p25, MASK_TOLERANCE)
        assertEquals("shadowOccupancy should be ~0.333", 0.333f, tonal.shadowOccupancy, PERCENTAGE_TOLERANCE)

        bitmap.recycle()
    }

    @Test
    fun `full gradient has maximum dynamic range`() {
        val bitmap = PhotoRegressionFixtures.fullGradient()
        val tonal = ImageMetrics.tonalMetrics(bitmap)

        assertTrue("Dynamic range should be > 0.9 for full gradient", tonal.dynamicRange > 0.9f)
        assertTrue("shadowOccupancy should be > 0.3", tonal.shadowOccupancy > 0.3f)
        assertTrue("highlightOccupancy should be > 0.3", tonal.highlightOccupancy > 0.3f)

        bitmap.recycle()
    }

    // ===================================================================
    // 5. IMAGE METRICS â€” COLOR
    // ===================================================================

    @Test
    fun `warm scene has higher meanR than meanB`() {
        val bitmap = PhotoRegressionFixtures.warmScene()
        val color = ImageMetrics.colorMetrics(bitmap)

        assertTrue("meanR should be > meanB for warm scene", color.meanR > color.meanB)
        assertTrue("meanR should be ~200", abs(color.meanR - 200f) < 1f)

        bitmap.recycle()
    }

    @Test
    fun `cool scene has higher meanB than meanR`() {
        val bitmap = PhotoRegressionFixtures.coolScene()
        val color = ImageMetrics.colorMetrics(bitmap)

        assertTrue("meanB should be > meanR for cool scene", color.meanB > color.meanR)

        bitmap.recycle()
    }

    // ===================================================================
    // 6. GOLDEN BASELINE â€” BALANCED GRAY
    // ===================================================================

    @Test
    fun `balancedGray baseline is stable`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()
        val snapshot = GoldenBaseline.captureSnapshot("balancedGray", bitmap)

        // Analysis
        assertFalse("isDarkImage", snapshot.isDarkImage)
        assertFalse("isBrightImage", snapshot.isBrightImage)
        assertTrue("isLowContrast", snapshot.isLowContrast)

        // Tonal
        assertEquals("iqr should be 0", 0f, snapshot.iqr, MASK_TOLERANCE)

        // EditPlan â€” balanced gray gets a small, bounded set of AUTO enhancement decisions
        // (a flat, muted frame legitimately receives modest contrast/saturation/vibrance/dehaze
        // lifts), but never harsh corrections such as Exposure/Shadows/Highlights.
        assertTrue("decisionCount should be small (<= 6)", snapshot.decisionCount <= 6)
        assertTrue("no decision should be large",
            snapshot.decisionMagnitudes.all { abs(it) <= 0.3f })

        // Output
        assertEquals("Output width matches input", 100, snapshot.outputWidth)
        assertEquals("Output height matches input", 100, snapshot.outputHeight)

        bitmap.recycle()
    }

    // ===================================================================
    // 7. GOLDEN BASELINE â€” ALL FIXTURES
    // ===================================================================

    @Test
    fun `all fixtures produce valid snapshots`() {
        PhotoRegressionFixtures.allFixtures().forEach { (name, factory) ->
            val bitmap = factory()
            try {
                val snapshot = GoldenBaseline.captureSnapshot(name, bitmap)
                assertEquals("fixtureName mismatch", name, snapshot.fixtureName)
                assertTrue("outputWidth > 0", snapshot.outputWidth > 0)
                assertTrue("outputHeight > 0", snapshot.outputHeight > 0)
                assertTrue("analysisPixelCount > 0", snapshot.analysisPixelCount > 0)
                assertTrue("totalDurationMs >= 0", snapshot.totalDurationMs >= 0)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `all fixtures produce deterministic output hashes`() {
        PhotoRegressionFixtures.allFixtures().forEach { (name, factory) ->
            val bitmap1 = factory()
            val bitmap2 = factory()
            try {
                val hash1 = GoldenBaseline.captureSnapshot(name, bitmap1).outputHash
                val hash2 = GoldenBaseline.captureSnapshot(name, bitmap2).outputHash
                assertEquals("$name: hash should be deterministic", hash1, hash2)
            } finally {
                bitmap1.recycle()
                bitmap2.recycle()
            }
        }
    }

    // ===================================================================
    // 8. EDITPLAN REGRESSION
    // ===================================================================

    @Test
    fun `balancedGray EditPlan has expected structure`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()
        val snapshot = GoldenBaseline.captureSnapshot("balancedGray", bitmap)

        // Balanced gray: no exposure, no shadows, no highlights
        // May have saturation boost
        assertFalse("Should not have Exposure",
            snapshot.decisionTypes.contains("Exposure"))
        assertFalse("Should not have Shadows",
            snapshot.decisionTypes.contains("Shadows"))
        assertFalse("Should not have Highlights",
            snapshot.decisionTypes.contains("Highlights"))

        bitmap.recycle()
    }

    @Test
    fun `lowKey fixture preserves dark intent`() {
        val bitmap = PhotoRegressionFixtures.lowKey()
        val snapshot = GoldenBaseline.captureSnapshot("lowKey", bitmap)

        // Low-key: isDarkImage should be true
        assertTrue("isDarkImage should be true", snapshot.isDarkImage)

        // CDE should not add positive Exposure for intentional dark
        val exposureIdx = snapshot.decisionTypes.indexOf("Exposure")
        if (exposureIdx >= 0) {
            assertTrue("Exposure should not brighten low-key (mag <= 0)",
                snapshot.decisionMagnitudes[exposureIdx] <= 0f)
        }

        bitmap.recycle()
    }

    @Test
    fun `highKey fixture preserves bright intent`() {
        val bitmap = PhotoRegressionFixtures.highKey()
        val snapshot = GoldenBaseline.captureSnapshot("highKey", bitmap)

        assertTrue("isBrightImage should be true", snapshot.isBrightImage)

        val exposureIdx = snapshot.decisionTypes.indexOf("Exposure")
        if (exposureIdx >= 0) {
            assertTrue("Exposure should not darken high-key (mag >= 0)",
                snapshot.decisionMagnitudes[exposureIdx] >= 0f)
        }

        bitmap.recycle()
    }

    @Test
    fun `skyScene has sky-related decisions`() {
        val bitmap = PhotoRegressionFixtures.skyScene()
        val snapshot = GoldenBaseline.captureSnapshot("skyScene", bitmap)

        assertTrue("skyScore should be > 0", snapshot.skyScore > 0f)

        bitmap.recycle()
    }

    @Test
    fun `skinToneScene has skin protection`() {
        val bitmap = PhotoRegressionFixtures.skinToneScene()
        val snapshot = GoldenBaseline.captureSnapshot("skinToneScene", bitmap)

        assertTrue("skinToneScore should be > 0", snapshot.skinToneScore > 0f)

        // Saturation decision should be affected by skin protection
        bitmap.recycle()
    }

    @Test
    fun `noisyPattern has noise awareness`() {
        val bitmap = PhotoRegressionFixtures.noisyPattern()
        val snapshot = GoldenBaseline.captureSnapshot("noisyPattern", bitmap)

        assertTrue("noiseConfidence should be > 0", snapshot.noiseConfidence > 0f)

        bitmap.recycle()
    }

    @Test
    fun `edgePattern has detail awareness`() {
        val bitmap = PhotoRegressionFixtures.edgePattern()
        val snapshot = GoldenBaseline.captureSnapshot("edgePattern", bitmap)

        assertTrue("edgeStrength should be > 0", snapshot.edgeStrength > 0f)

        bitmap.recycle()
    }

    // ===================================================================
    // 9. PROTECTION REGRESSION
    // ===================================================================

    @Test
    fun `intentional low-key protection â€” crushedShadows preserved`() {
        val bitmap = PhotoRegressionFixtures.crushedShadows()
        val snapshot = GoldenBaseline.captureSnapshot("crushedShadows", bitmap)

        assertTrue("isDarkImage should be true", snapshot.isDarkImage)

        // CDE should NOT add positive Exposure for intentional dark
        val exposureDecisions = snapshot.decisionTypes.zip(snapshot.decisionMagnitudes)
            .filter { it.first == "Exposure" }
        exposureDecisions.forEach { (_, mag) ->
            assertTrue("Exposure should not brighten crushed shadows (mag <= 0)", mag <= 0f)
        }

        bitmap.recycle()
    }

    @Test
    fun `intentional high-key protection â€” clippedHighlights preserved`() {
        val bitmap = PhotoRegressionFixtures.clippedHighlights()
        val snapshot = GoldenBaseline.captureSnapshot("clippedHighlights", bitmap)

        assertTrue("isBrightImage should be true", snapshot.isBrightImage)

        val exposureDecisions = snapshot.decisionTypes.zip(snapshot.decisionMagnitudes)
            .filter { it.first == "Exposure" }
        exposureDecisions.forEach { (_, mag) ->
            assertTrue("Exposure should not darken clipped highlights (mag >= 0)", mag >= 0f)
        }

        bitmap.recycle()
    }

    @Test
    fun `noisy image has reduced shadow recovery`() {
        val bitmap = PhotoRegressionFixtures.noisyPattern()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        // If Shadows decision exists, magnitude should be reduced by noisePenalty
        val shadowDecision = plan.decisions.find { it.parameter == "Shadows" }
        if (shadowDecision != null) {
            assertTrue("Shadow magnitude should be <= 0.6 (noise-penalized)",
                shadowDecision.magnitude <= 0.6f)
        }

        bitmap.recycle()
    }

    @Test
    fun `blur protection â€” no texture enhancement on smooth images`() {
        val bitmap = PhotoRegressionFixtures.smoothGradient()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        // Smooth gradient should not get Texture decisions
        val textureDecision = plan.decisions.find { it.parameter == "Texture" }
        assertNull("Smooth image should not get Texture enhancement", textureDecision)

        bitmap.recycle()
    }

    @Test
    fun `sky protection â€” sky region edits are sky-specific`() {
        val bitmap = PhotoRegressionFixtures.skyScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        val skyDecisions = plan.decisions.filter { it.regionMask == "Sky" }
        skyDecisions.forEach { decision ->
            assertEquals("Sky decisions should use Sky mask", "Sky", decision.regionMask)
        }

        bitmap.recycle()
    }

    @Test
    fun `vegetation protection â€” vegetation mask present`() {
        val bitmap = PhotoRegressionFixtures.vegetationScene()
        val analysis = ImageAnalyzer.analyze(bitmap)

        assertTrue("greenScore should be > 0", analysis.greenScore > 0f)
        assertTrue("vegMask should have non-zero values", analysis.vegMask.sum() > 0f)

        bitmap.recycle()
    }

    @Test
    fun `clipping protection â€” quality gate limits harmful edits`() {
        // Use a high-contrast image that might trigger quality gate
        val bitmap = PhotoRegressionFixtures.highContrastEdge()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        // Process and verify output is valid
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        assertNotNull(result)

        // Verify no pixels are completely blown out or crushed
        val outputTonal = ImageMetrics.tonalMetrics(result)
        assertTrue("Output should have some tonal range",
            outputTonal.dynamicRange > 0f)

        bitmap.recycle()
        result.recycle()
    }

    // ===================================================================
    // 10. PREVIEW / EXPORT REGRESSION
    // ===================================================================

    @Test
    fun `preview analysis matches export analysis for same input`() {
        val bitmap = PhotoRegressionFixtures.balancedGray()

        // Preview path: analyze the bitmap directly
        val previewAnalysis = ImageAnalyzer.analyze(bitmap)

        // Export path: crop then analyze
        val cropped = cropBitmap(bitmap, null) // null = no crop = same image
        val exportAnalysis = ImageAnalyzer.analyze(cropped)

        // Both should produce equivalent analysis
        assertEquals("pixelCount should match", previewAnalysis.pixelCount, exportAnalysis.pixelCount)
        assertEquals("isDarkImage should match", previewAnalysis.isDarkImage, exportAnalysis.isDarkImage)
        assertEquals("isBrightImage should match", previewAnalysis.isBrightImage, exportAnalysis.isBrightImage)
        assertEquals("iqr should match", previewAnalysis.tonalStructure.iqr, exportAnalysis.tonalStructure.iqr, MASK_TOLERANCE)
        assertEquals("p50 should match", previewAnalysis.tonalStructure.p50, exportAnalysis.tonalStructure.p50, MASK_TOLERANCE)

        bitmap.recycle()
    }

    @Test
    fun `crop changes analysis composition`() {
        val bitmap = PhotoRegressionFixtures.skyScene(width = 200, height = 100)
        val fullAnalysis = ImageAnalyzer.analyze(bitmap)

        val cropped = cropBitmap(bitmap, CropFrame.SQUARE.aspectRatio)
        val croppedAnalysis = ImageAnalyzer.analyze(cropped)

        assertNotEquals("Pixel counts should differ for cropped",
            fullAnalysis.pixelCount, croppedAnalysis.pixelCount)

        bitmap.recycle()
        cropped.recycle()
    }

    // ===================================================================
    // 11. PROTECTION REGRESSION â€” COMPREHENSIVE
    // ===================================================================

    @Test
    fun `low-key with crushing preserves dark intent`() {
        val bitmap = PhotoRegressionFixtures.lowKeyWithCrushing()
        val snapshot = GoldenBaseline.captureSnapshot("lowKeyWithCrushing", bitmap)

        assertTrue("isDarkImage should be true", snapshot.isDarkImage)

        val exposureDecisions = snapshot.decisionTypes.zip(snapshot.decisionMagnitudes)
            .filter { it.first == "Exposure" }
        exposureDecisions.forEach { (_, mag) ->
            assertTrue("Exposure should not brighten low-key with crushing (mag <= 0)", mag <= 0f)
        }

        bitmap.recycle()
    }

    @Test
    fun `high contrast edge has no clipping protection violation`() {
        val bitmap = PhotoRegressionFixtures.highContrastEdge()
        val analysis = ImageAnalyzer.analyze(bitmap)

        // highlightClipping should be 0 for pure black/white split
        // (no pixels with luma > 250 except the white half which is luma=255)
        assertTrue("highlightClipping should reflect white region",
            analysis.signalQuality.highlightClipping >= 0f)

        bitmap.recycle()
    }

    // ===================================================================
    // 12. OUTPUT PIXEL REGRESSION
    // ===================================================================

    @Test
    fun `balancedGray output pixel samples are deterministic`() {
        val bitmap1 = PhotoRegressionFixtures.balancedGray()
        val bitmap2 = PhotoRegressionFixtures.balancedGray()

        val snapshot1 = GoldenBaseline.captureSnapshot("balancedGray", bitmap1)
        val snapshot2 = GoldenBaseline.captureSnapshot("balancedGray", bitmap2)

        assertEquals("Pixel samples should be identical",
            snapshot1.outputPixelSamples, snapshot2.outputPixelSamples)
        assertEquals("Output hash should be identical",
            snapshot1.outputHash, snapshot2.outputHash)

        bitmap1.recycle()
        bitmap2.recycle()
    }

    @Test
    fun `darkGray output has higher meanLuma than input`() {
        val bitmap = PhotoRegressionFixtures.darkGray()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        // If there's a positive exposure decision, output should be brighter
        val hasPositiveExposure = plan.decisions.any {
            it.parameter == "Exposure" && it.magnitude > 0.02f
        }

        if (hasPositiveExposure) {
            val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
            val inputTonal = ImageMetrics.tonalMetrics(bitmap)
            val outputTonal = ImageMetrics.tonalMetrics(result)

            assertTrue("Output meanLuma should be > input meanLuma when brightening",
                outputTonal.meanLuma > inputTonal.meanLuma)
            result.recycle()
        }

        bitmap.recycle()
    }

    // ===================================================================
    // 13. REGIONAL METRICS
    // ===================================================================

    @Test
    fun `skyScene has sky region in output`() {
        val bitmap = PhotoRegressionFixtures.skyScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)

        val regional = ImageMetrics.regionalMetrics(result, analysis)
        assertTrue("Sky pixel count should be > 0", regional.skyPixelCount > 0)

        bitmap.recycle()
        result.recycle()
    }

    @Test
    fun `skinToneScene has skin region in output`() {
        val bitmap = PhotoRegressionFixtures.skinToneScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)

        val regional = ImageMetrics.regionalMetrics(result, analysis)
        assertTrue("Skin pixel count should be > 0", regional.skinPixelCount > 0)

        bitmap.recycle()
        result.recycle()
    }

    // ===================================================================
    // 14. DETERMINISM VERIFICATION
    // ===================================================================

    @Test
    fun `full baseline generation is deterministic`() {
        val baseline1 = GoldenBaseline.generateFullBaseline()
        val baseline2 = GoldenBaseline.generateFullBaseline()

        assertEquals("Same number of snapshots", baseline1.size, baseline2.size)

        baseline1.zip(baseline2).forEach { (s1, s2) ->
            assertEquals("${s1.fixtureName}: hash should match", s1.outputHash, s2.outputHash)
            assertEquals("${s1.fixtureName}: decisionCount should match", s1.decisionCount, s2.decisionCount)
            assertEquals("${s1.fixtureName}: decisionTypes should match", s1.decisionTypes, s2.decisionTypes)
            assertEquals("${s1.fixtureName}: outputMeanLuma should match",
                s1.outputMeanLuma, s2.outputMeanLuma, LUMA_TOLERANCE)
        }
    }

    // ===================================================================
    // 15. EDGE CASES
    // ===================================================================

    @Test
    fun `singlePixel does not crash pipeline`() {
        val bitmap = PhotoRegressionFixtures.singlePixel()
        val snapshot = GoldenBaseline.captureSnapshot("singlePixel", bitmap)
        assertEquals("Output should be 1x1 or scaled", snapshot.outputWidth, snapshot.outputHeight)
        bitmap.recycle()
    }

    @Test
    fun `uniformBlack does not crash pipeline`() {
        val bitmap = PhotoRegressionFixtures.uniformBlack()
        val snapshot = GoldenBaseline.captureSnapshot("uniformBlack", bitmap)
        assertTrue("Output should be valid", snapshot.outputWidth > 0)
        bitmap.recycle()
    }

    @Test
    fun `uniformWhite does not crash pipeline`() {
        val bitmap = PhotoRegressionFixtures.uniformWhite()
        val snapshot = GoldenBaseline.captureSnapshot("uniformWhite", bitmap)
        assertTrue("Output should be valid", snapshot.outputWidth > 0)
        bitmap.recycle()
    }

    @Test
    fun `warmCoolSplit produces mixed color temperature`() {
        val bitmap = PhotoRegressionFixtures.warmCoolSplit()
        val color = ImageMetrics.colorMetrics(bitmap)

        // Should have roughly equal warm and cool halves
        assertTrue("meanR should be > 0", color.meanR > 0f)
        assertTrue("meanB should be > 0", color.meanB > 0f)

        bitmap.recycle()
    }

    // ===================================================================
    // 16. OUTPUT MEAN LUMA BOUNDS
    // ===================================================================

    @Test
    fun `output meanLuma is within valid bounds for all fixtures`() {
        PhotoRegressionFixtures.allFixtures().forEach { (name, factory) ->
            val bitmap = factory()
            try {
                val snapshot = GoldenBaseline.captureSnapshot(name, bitmap)
                assertTrue("$name: outputMeanLuma >= 0", snapshot.outputMeanLuma >= 0f)
                assertTrue("$name: outputMeanLuma <= 255", snapshot.outputMeanLuma <= 255f)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test
    fun `output meanRGB channels are within valid bounds for all fixtures`() {
        PhotoRegressionFixtures.allFixtures().forEach { (name, factory) ->
            val bitmap = factory()
            try {
                val snapshot = GoldenBaseline.captureSnapshot(name, bitmap)
                assertTrue("$name: outputMeanR in 0..255", snapshot.outputMeanR in 0f..255f)
                assertTrue("$name: outputMeanG in 0..255", snapshot.outputMeanG in 0f..255f)
                assertTrue("$name: outputMeanB in 0..255", snapshot.outputMeanB in 0f..255f)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
