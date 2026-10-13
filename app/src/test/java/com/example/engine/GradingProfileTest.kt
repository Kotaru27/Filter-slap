package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * PROFILE-DRIVEN GRADING VERIFICATION.
 *
 * The core complaint this locks down: "it feels like a random filter slapped on" â€”
 * every photo got the same generic lift, and preset labels changed nothing.
 *
 * These tests prove:
 * 1. Different scenes now get DIFFERENT plan shapes (portrait vs landscape vs low-light).
 * 2. Measured lens defects (vignette, CA) are actually CORRECTED, not just reported.
 * 3. The engine commits to scene-appropriate magnitudes (landscape braver than portrait).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GradingProfileTest {

    // A structured scene with skin-region content: a LARGE SMOOTH warm subject mass (the
    // skin gate requires smoothness) plus realistic facial-feature structure (eye/mouth
    // bands) that gives the frame genuine edges without breaking the skin mask.
    private fun portraitLikeScene(): Bitmap {
        val w = 256; val h = 256
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val inSubject = x in 40..215 && y in 40..215
            // Facial feature bands: "eyes" at y=110..120, "mouth" at y=170..178 â€” dark
            // structured strips across the face like real features.
            val inFeature = inSubject && ((y in 110..120 && (x in 70..110 || x in 145..185)) || (y in 170..178 && x in 90..165))
            when {
                inFeature -> bitmap.setPixel(x, y, Color.rgb(90, 60, 45))
                inSubject -> bitmap.setPixel(x, y, Color.rgb(208, 160, 122))
                else -> bitmap.setPixel(x, y, Color.rgb(95, 95, 95))
            }
        }
        return bitmap
    }

    // Sky + foliage + structure: a landscape signature with REAL tonal spread (dark ground
    // vs bright sky â€” a landscape has depth), and coarse foliage texture that survives the
    // 256px analysis downsample.
    private fun landscapeLikeScene(): Bitmap {
        val w = 256; val h = 256
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            if (y < 110) {
                bitmap.setPixel(x, y, Color.rgb(70, 130, 210))            // bright sky
            } else {
                val g = if (((x / 12) + (y / 12)) % 2 == 0) 30 else 110    // dark textured ground
                bitmap.setPixel(x, y, Color.rgb(20 + g / 2, (60 + g).coerceAtMost(255), 25 + g / 4))
            }
        }
        return bitmap
    }

    // Noisy dark scene: low-light signature.
    private fun lowLightLikeScene(): Bitmap {
        val w = 200; val h = 200
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (40 + rnd.nextInt(50)).coerceIn(0, 255)
            bitmap.setPixel(x, y, Color.rgb(v, v, v))
        }
        return bitmap
    }

    @Test
    fun `portrait and landscape scenes select different profiles`() {
        val portraitPlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(portraitLikeScene()))
        val landscapePlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(landscapeLikeScene()))

        assertEquals("PORTRAIT", portraitPlan.profileName)
        assertEquals("LANDSCAPE", landscapePlan.profileName)
        assertTrue("profileReason must explain the choice", portraitPlan.profileReason.contains("skin"))
        assertTrue("profileReason must explain the choice", landscapePlan.profileReason.contains("sky"))
    }

    @Test
    fun `low light scene selects LOW_LIGHT and skips texture amplification`() {
        val plan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(lowLightLikeScene()))
        assertEquals("LOW_LIGHT", plan.profileName)
        assertNull("low-light frames must never get Texture amplification",
            plan.decisions.find { it.parameter == "Texture" })
        assertNull("low-light frames must never get Clarity amplification",
            plan.decisions.find { it.parameter == "Clarity" })
    }

    @Test
    fun `landscape clarity targets the whole frame, portrait clarity targets the subject`() {
        val portraitPlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(portraitLikeScene()))
        val landscapePlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(landscapeLikeScene()))

        val portraitClarity = portraitPlan.decisions.find { it.parameter == "Clarity" }
        val landscapeClarity = landscapePlan.decisions.find { it.parameter == "Clarity" }

        assertEquals("Subject", portraitClarity?.regionMask)
        assertEquals("Detail", landscapeClarity?.regionMask)
        assertTrue("landscape clarity must be braver than portrait (depth is the look)",
            (landscapeClarity?.magnitude ?: 0f) > (portraitClarity?.magnitude ?: 0f))
    }

    @Test
    fun `landscape contrast is committed deeper than portrait`() {
        val portraitPlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(portraitLikeScene()))
        val landscapePlan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(landscapeLikeScene()))

        val pc = portraitPlan.decisions.find { it.parameter == "Contrast" }?.magnitude ?: 0f
        val lc = landscapePlan.decisions.find { it.parameter == "Contrast" }?.magnitude ?: 0f
        assertTrue("landscape contrast ($lc) should exceed portrait contrast ($pc)", lc > pc)
    }

    @Test
    fun `measured vignette is corrected in the render`() {
        // Vignetted smooth frame: corners 40% darker than center.
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) for (x in 0 until 200) {
            val nx = (x - 100) / 100f; val ny = (y - 100) / 100f
            val d2 = nx * nx + ny * ny
            val falloff = (1f - 0.45f * d2).coerceIn(0f, 1f)
            val v = (170 * falloff).toInt()
            bitmap.setPixel(x, y, Color.rgb(v, v, v))
        }
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertTrue("fixture must measure vignette", analysis.lensQuality.vignette > 0.3f)

        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        // Corner luma must RISE after correction (radial gain lifts corners).
        fun cornerAvg(bmp: Bitmap): Int {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            var acc = 0; var cnt = 0
            for (yy in 0 until bmp.height) for (xx in 0 until bmp.width) {
                if (xx < 20 || xx >= bmp.width - 20 || yy < 20 || yy >= bmp.height - 20) {
                    val c = px[yy * bmp.width + xx]
                    acc += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3; cnt++
                }
            }
            return acc / cnt
        }
        val before = cornerAvg(bitmap)
        val after = cornerAvg(result)
        assertTrue("vignette correction must lift corners (before=$before after=$after)", after > before)
    }

    @Test
    fun `clean lens frame gets no vignette or CA decisions`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(130, 130, 130))
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("clean frame: no vignette correction", plan.decisions.find { it.parameter == "Vignette" })
        assertNull("clean frame: no CA suppression", plan.decisions.find { it.parameter == "CA" })
    }

    @Test
    fun `measured chromatic aberration is suppressed in the render`() {
        // Fringed edge: R transitions 2px earlier than G/B along a strong vertical edge.
        val w = 200; val h = 100
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val g = if (x < 100) 40 else 220
            val r = if (x < 102) 40 else 220
            bitmap.setPixel(x, y, Color.rgb(r, g, g))
        }
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertTrue("fixture must measure CA", analysis.lensQuality.chromaticAberration > 0.2f)

        val (result, _, _, _) = ImageProcessor.process(bitmap, analysis)
        // The fringe zone (x=100..101) should lose its R-vs-G imbalance after suppression.
        // The imbalance may be either sign (R leads or lags the luma edge), so the check is on
        // the MAGNITUDE of the R-G excess: it must shrink.
        fun fringeRedExcess(bmp: Bitmap): Int {
            val px = IntArray(bmp.width * bmp.height)
            bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            var acc = 0; var cnt = 0
            for (y2 in 10 until h - 10) {
                for (x2 in 100..101) {
                    val c = px[y2 * bmp.width + x2]
                    acc += Color.red(c) - Color.green(c); cnt++
                }
            }
            return acc / kotlin.math.max(1, cnt)
        }
        val before = kotlin.math.abs(fringeRedExcess(bitmap))
        val after = kotlin.math.abs(fringeRedExcess(result))
        assertTrue("CA suppression must shrink the |R-G| fringe excess (before=$before after=$after)", after < before)
    }

    @Test
    fun `plan carries profile identity for the UI`() {
        val plan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(portraitLikeScene()))
        assertTrue("profileName non-empty", plan.profileName.isNotEmpty())
        assertTrue("profileReason explains itself", plan.profileReason.isNotEmpty())
    }
}
