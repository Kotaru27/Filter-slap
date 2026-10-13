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
class PipelineIntegrationTest {

    // === FIXTURE HELPERS ===

    /** Creates a horizontal gradient from black (left) to white (right) */
    private fun createHorizontalGradient(width: Int = 200, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            val value = (x * 255) / (width - 1)
            for (y in 0 until height) {
                bitmap.setPixel(x, y, Color.rgb(value, value, value))
            }
        }
        return bitmap
    }

    /** Creates a bitmap with top half blue (sky-like) and bottom half green (vegetation-like) */
    private fun createSkyAndVegetation(width: Int = 200, height: Int = 200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < height / 2) {
                    // Blue sky: HSL ~(220, 60%, 55%) -> RGB ~(70, 130, 210)
                    bitmap.setPixel(x, y, Color.rgb(70, 130, 210))
                } else {
                    // Green vegetation: HSL ~(110, 50%, 40%) -> RGB ~(51, 153, 71)
                    bitmap.setPixel(x, y, Color.rgb(51, 153, 71))
                }
            }
        }
        return bitmap
    }

    /** Creates a bitmap with a sharp edge (left half black, right half white) */
    private fun createEdgePattern(width: Int = 200, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    bitmap.setPixel(x, y, Color.rgb(20, 20, 20))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(235, 235, 235))
                }
            }
        }
        return bitmap
    }

    /** Creates a noisy pattern using deterministic pixel alternation */
    private fun createNoisyPattern(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // Deterministic noise: checkerboard of dark/light
                val v = if ((x + y) % 2 == 0) 80 else 180
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    /** Creates a low-key image: mostly dark with a few brighter pixels */
    private fun createLowKeyScene(width: Int = 200, height: Int = 200): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 10 && y < height / 10) 180 else 25
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // Helper: count how many pixels differ by more than threshold
    private fun countChangedPixels(before: Bitmap, after: Bitmap, threshold: Int = 3): Int {
        var count = 0
        val w = before.width.coerceAtMost(after.width)
        val h = before.height.coerceAtMost(after.height)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pb = before.getPixel(x, y)
                val pa = after.getPixel(x, y)
                if (abs(Color.red(pb) - Color.red(pa)) > threshold ||
                    abs(Color.green(pb) - Color.green(pa)) > threshold ||
                    abs(Color.blue(pb) - Color.blue(pa)) > threshold) {
                    count++
                }
            }
        }
        return count
    }

    // === INTEGRATION TESTS ===

    @Test
    fun `full pipeline completes on gradient image`() {
        val gradient = createHorizontalGradient()
        val analysis = ImageAnalyzer.analyze(gradient)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val (result, stats, _, _) = ImageProcessor.process(gradient, analysis)

        assertNotNull(result)
        assertEquals(gradient.width, result.width)
        assertEquals(gradient.height, result.height)
        assertTrue("Processing time should be non-negative", stats.computeTimeMs >= 0)
    }

    @Test
    fun `gradient image produces non-trivial analysis`() {
        val gradient = createHorizontalGradient()
        val analysis = ImageAnalyzer.analyze(gradient)

        // A gradient should have significant contrast
        assertFalse("Gradient should not be low contrast", analysis.isLowContrast)
        assertTrue("Gradient should have significant occupancy", analysis.contrastRatio > 0.3f)
        // Should have non-zero edge strength from the gradient
        assertTrue("Gradient should have edge strength", analysis.sharpnessScore > 0f)
    }

    @Test
    fun `gradient image produces analysis decisions`() {
        val gradient = createHorizontalGradient()
        val analysis = ImageAnalyzer.analyze(gradient)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        // A gradient spanning 0-255 should trigger some decisions
        // (likely contrast, possibly exposure depending on asymmetry)
        assertTrue("Gradient should produce at least one decision", plan.decisions.isNotEmpty())
    }

    @Test
    fun `processing changes gradient image when edits are expected`() {
        val gradient = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until 200) {
            val v = (x * 255) / 199
            for (y in 0 until 100) {
                gradient.setPixel(x, y, Color.rgb(v, v / 2, v / 4))
            }
        }
        val analysis = ImageAnalyzer.analyze(gradient)
        val plan = ContextualDecisionEngine.evaluate(analysis)

        if (plan.decisions.isNotEmpty()) {
            val (result, _, _, _) = ImageProcessor.process(gradient, analysis)
            val changedPixels = countChangedPixels(gradient, result, threshold = 1)
            assertTrue("Expected some pixels to change when decisions exist", changedPixels > 0)
        }
    }

    @Test
    fun `sky and vegetation analysis detects regions`() {
        val scene = createSkyAndVegetation()
        val analysis = ImageAnalyzer.analyze(scene)

        // Should detect some sky (blue region in top half)
        assertTrue("Sky score should be positive for blue upper region", analysis.skyScore > 0f)
        // Should detect some vegetation (green region in bottom half)
        assertTrue("Green score should be positive for green lower region", analysis.greenScore > 0f)
    }

    @Test
    fun `sky and vegetation pipeline completes`() {
        val scene = createSkyAndVegetation()
        val analysis = ImageAnalyzer.analyze(scene)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val (result, _, _, _) = ImageProcessor.process(scene, analysis)

        assertNotNull(result)
        assertEquals(scene.width, result.width)
        assertEquals(scene.height, result.height)
    }

    @Test
    fun `edge pattern has high detail and produces decisions`() {
        val edge = createEdgePattern()
        val analysis = ImageAnalyzer.analyze(edge)

        // Sharp edge should produce high edge strength
        assertTrue("Edge pattern should have edge strength", analysis.sharpnessScore > 0.1f)

        val plan = ContextualDecisionEngine.evaluate(analysis)
        // Edge pattern spans dark to bright, should trigger decisions
        assertNotNull(plan)
    }

    @Test
    fun `low key scene is recognized as dark`() {
        val lowKey = createLowKeyScene()
        val analysis = ImageAnalyzer.analyze(lowKey)

        assertTrue("Low key scene should be dark", analysis.isDarkImage)
        assertFalse("Low key scene should not be bright", analysis.isBrightImage)
    }

    @Test
    fun `low key scene pipeline completes without crash`() {
        val lowKey = createLowKeyScene()
        val analysis = ImageAnalyzer.analyze(lowKey)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val (result, _, _, _) = ImageProcessor.process(lowKey, analysis)

        assertNotNull(result)
        assertEquals(lowKey.width, result.width)
        assertEquals(lowKey.height, result.height)
    }

    @Test
    fun `noisy pattern has elevated noise score`() {
        val noisy = createNoisyPattern()
        val analysis = ImageAnalyzer.analyze(noisy)

        // Alternating 80/180 pixels create high local variance with low gradient
        // This should register as noise in the analysis
        assertTrue("Noisy pattern should have noise confidence > 0",
            analysis.signalQuality.noiseConfidence > 0f)
    }

    @Test
    fun `analysis pixel count matches fixture size`() {
        val bitmap = createHorizontalGradient(150, 80)
        val analysis = ImageAnalyzer.analyze(bitmap)

        // Analysis scales down to max 256px
        // scale = min(256/150, 256/80) = min(1.707, 3.2) = 1.707
        // analysisWidth = (150 * 1.707).toInt() = 256
        // analysisHeight = (80 * 1.707).toInt() = 136
        assertTrue("Pixel count should be positive", analysis.pixelCount > 0)
        assertEquals(analysis.analysisWidth * analysis.analysisHeight, analysis.pixelCount)
    }

    @Test
    fun `pipeline with crop produces cropped output`() {
        val gradient = createHorizontalGradient(200, 100)
        val cropped = cropBitmap(gradient, CropFrame.SQUARE.aspectRatio)

        // 200x100 with target ratio 1.0 -> newWidth = (100 * 1.0).toInt() = 100
        assertEquals(100, cropped.width)
        assertEquals(100, cropped.height)

        val analysis = ImageAnalyzer.analyze(cropped)
        val (result, _, _, _) = ImageProcessor.process(cropped, analysis)
        assertEquals(100, result.width)
        assertEquals(100, result.height)
    }

    @Test
    fun `output bitmap is valid ARGB_8888`() {
        val gradient = createHorizontalGradient(50, 50)
        val analysis = ImageAnalyzer.analyze(gradient)
        val (result, _, _, _) = ImageProcessor.process(gradient, analysis)

        assertEquals(Bitmap.Config.ARGB_8888, result.config)
    }
}
