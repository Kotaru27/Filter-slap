package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * PHASE 1-3 verification: real color forensics, neutral anchors, lens quality,
 * composition, horizon tilt, and crop recommendations.
 *
 * Every test uses deterministic synthetic fixtures and asserts the MEASUREMENT
 * semantics documented in the analyzer — no placeholders survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImageForensicsTest {

    private fun createSolid(r: Int, g: Int, b: Int, w: Int = 100, h: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(r, g, b))
        return bitmap
    }

    // === p95S: 95th percentile of saturation ===

    @Test
    fun `p95S is zero for a neutral gray frame`() {
        val result = ImageAnalyzer.analyze(createSolid(128, 128, 128))
        assertTrue("gray frame has no vivid pixels; p95S=${result.colorAnalysis.p95S}",
            result.colorAnalysis.p95S < 0.02f)
    }

    @Test
    fun `p95S is high for a fully saturated frame`() {
        val result = ImageAnalyzer.analyze(createSolid(255, 0, 0))
        assertTrue("pure red is fully saturated; p95S=${result.colorAnalysis.p95S}",
            result.colorAnalysis.p95S > 0.9f)
    }

    // === hueStability: circular hue coherence ===

    @Test
    fun `hueStability is 1 for a single-hue frame`() {
        val result = ImageAnalyzer.analyze(createSolid(255, 0, 0))
        assertTrue("single hue = perfectly coherent; got ${result.colorAnalysis.hueStability}",
            result.colorAnalysis.hueStability > 0.95f)
    }

    @Test
    fun `hueStability is low for opposing-hue halves`() {
        // Red (hue 0°) and cyan (hue 180°) are TRUE circular opposites → mean ~0.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            bitmap.setPixel(x, y, if (x < 50) Color.rgb(255, 0, 0) else Color.rgb(0, 255, 255))
        }
        val result = ImageAnalyzer.analyze(bitmap)
        assertTrue("opposing hues must read as unstable; got ${result.colorAnalysis.hueStability}",
            result.colorAnalysis.hueStability < 0.15f)
    }

    // === chromaNoise: local saturation scatter in flat regions ===

    @Test
    fun `chromaNoise is near zero for uniform color`() {
        val result = ImageAnalyzer.analyze(createSolid(100, 130, 160))
        assertTrue("uniform frame has no saturation scatter; got ${result.colorAnalysis.chromaNoise}",
            result.colorAnalysis.chromaNoise < 0.01f)
    }

    @Test
    fun `chromaNoise is measurable for saturation speckle`() {
        // Flat luma with alternating SATURATION (not hue): real chroma-noise signature.
        // (90,110,130)≈s=0.18 and (110,110,110)≈s=0 — equal luma, different saturation.
        // Built at 256px so the analyzer's downsample is 1:1 — no resampling smoothing.
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        for (y in 0 until 256) for (x in 0 until 256) {
            bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.rgb(90, 110, 130) else Color.rgb(110, 110, 110))
        }
        val result = ImageAnalyzer.analyze(bitmap)
        assertTrue("saturation speckle in flat regions must measure chromaNoise; got ${result.colorAnalysis.chromaNoise}",
            result.colorAnalysis.chromaNoise > 0.05f)
    }

    // === neutralEvidence / NeutralAnchors ===

    @Test
    fun `neutral anchors are found in gray regions`() {
        // Sky top + gray pavement bottom: the pavement is a gray-world anchor.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            bitmap.setPixel(x, y, if (y < 50) Color.rgb(70, 130, 210) else Color.rgb(128, 128, 128))
        }
        val result = ImageAnalyzer.analyze(bitmap)
        val na = result.neutralAnchors
        assertTrue("gray half must yield anchors; count=${na.count}", na.count > 1000)
        assertTrue("coverage should be ~0.5; got ${na.coverage}", abs(na.coverage - 0.5f) < 0.1f)
        assertTrue("anchors should be tight; spread=${na.spread}", na.spread < 2f)
        assertTrue("neutralEvidence must mirror coverage", abs(result.colorAnalysis.neutralEvidence - na.coverage) < 0.01f)
    }

    @Test
    fun `warm cast shifts anchor channels`() {
        // A gray card photographed under warm light: anchors carry R > B evidence.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(168, 128, 98))
        val result = ImageAnalyzer.analyze(bitmap)
        val na = result.neutralAnchors
        assertTrue("warm gray card must have anchors; count=${na.count}", na.count > 1000)
        assertTrue("anchor avgR must exceed avgB for warm cast; R=${na.avgR} B=${na.avgB}",
            na.avgR - na.avgB > 40f)
    }

    // === Lens quality ===

    @Test
    fun `vignette is measurable on corner-darkened frame and zero on uniform`() {
        val uniform = ImageAnalyzer.analyze(createSolid(128, 128, 128))
        assertTrue("uniform frame has no vignette", uniform.lensQuality.vignette < 0.05f)

        // Vignetted frame: corners 40% darker than center, smooth falloff.
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) for (x in 0 until 200) {
            val nx = (x - 100) / 100f; val ny = (y - 100) / 100f
            val d2 = nx * nx + ny * ny
            val falloff = (1f - 0.45f * d2).coerceIn(0f, 1f)
            val v = (170 * falloff).toInt()
            bitmap.setPixel(x, y, Color.rgb(v, v, v))
        }
        val vignetted = ImageAnalyzer.analyze(bitmap)
        assertTrue("vignetted fixture must measure severity > 0.3; got ${vignetted.lensQuality.vignette}",
            vignetted.lensQuality.vignette > 0.3f)
    }

    @Test
    fun `chromatic aberration is near zero on neutral edges and measurable on fringed edges`() {
        // Clean luma edge, R and B transition at the same x: no fringing.
        val clean = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) {
            val v = if (x < 100) 40 else 220
            clean.setPixel(x, y, Color.rgb(v, v, v))
        }
        val cleanResult = ImageAnalyzer.analyze(clean)
        assertTrue("clean edge should measure CA ~0; got ${cleanResult.lensQuality.chromaticAberration}",
            cleanResult.lensQuality.chromaticAberration < 0.1f)

        // Fringed edge: R transitions 2px earlier than G/B — lateral CA within the ±2 window.
        val fringed = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 200) {
            val g = if (x < 100) 40 else 220
            val r = if (x < 102) 40 else 220
            val b = if (x < 100) 40 else 220
            fringed.setPixel(x, y, Color.rgb(r, g, b))
        }
        val fringedResult = ImageAnalyzer.analyze(fringed)
        assertTrue("fringed edge must measure CA above clean; fringed=${fringedResult.lensQuality.chromaticAberration} clean=${cleanResult.lensQuality.chromaticAberration}",
            fringedResult.lensQuality.chromaticAberration > cleanResult.lensQuality.chromaticAberration)
    }

    // === Channel endpoints ===

    @Test
    fun `channel endpoints are measured not hardcoded`() {
        // Gradient from 60..200 in R: endpoints must NOT be 0/255.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            val v = 60 + (x * 140 / 99)
            bitmap.setPixel(x, y, Color.rgb(v, v, v))
        }
        val result = ImageAnalyzer.analyze(bitmap)
        assertTrue("minR should be ~60, got ${result.minR}", abs(result.minR - 60) < 15)
        assertTrue("maxR should be ~200, got ${result.maxR}", abs(result.maxR - 200) < 15)
        assertTrue("minG should be ~60, got ${result.minG}", abs(result.minG - 60) < 15)
        assertTrue("maxB should be ~200, got ${result.maxB}", abs(result.maxB - 200) < 15)
    }

    // === textureStrength is the real mask mean now ===

    @Test
    fun `textureStrength is the real mask mean not the IQR surrogate`() {
        // Uniform frame: iqr surrogate was 0 too, but a real mask mean must stay tiny.
        val flat = ImageAnalyzer.analyze(createSolid(128, 128, 128))
        assertTrue("flat frame textureStrength ~0; got ${flat.signalQuality.textureStrength}",
            flat.signalQuality.textureStrength < 0.02f)

        // Genuine grain (2px isotropic checkerboard): real texture must register.
        val grain = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            val v = if ((x / 2 + y / 2) % 2 == 0) 90 else 170
            grain.setPixel(x, y, Color.rgb(v, v, v))
        }
        val grainResult = ImageAnalyzer.analyze(grain)
        assertTrue("grain textureStrength should exceed flat; grain=${grainResult.signalQuality.textureStrength} flat=${flat.signalQuality.textureStrength}",
            grainResult.signalQuality.textureStrength > flat.signalQuality.textureStrength)
        assertTrue("grain textureStrength should clear the CDE 0.03 gate; got ${grainResult.signalQuality.textureStrength}",
            grainResult.signalQuality.textureStrength > 0.03f)
    }

    // === Composition ===

    @Test
    fun `composition is never Unknown and reflects subject placement`() {
        val flat = ImageAnalyzer.analyze(createSolid(128, 128, 128))
        assertTrue("subject-less frame should describe scene-wide interest; got '${flat.report.composition}'",
            flat.report.composition == "Scene-wide interest (no dominant subject)")

        // Centered detailed subject must read as centered.
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (y in 0 until 200) for (x in 0 until 200) {
            val inCenter = x in 66..133 && y in 66..133
            val v = if (inCenter) { if (((x + y) / 4) % 2 == 0) 255 else 40 } else 128
            bitmap.setPixel(x, y, Color.rgb(v, v, v))
        }
        val result = ImageAnalyzer.analyze(bitmap)
        assertTrue("centered subject; got '${result.report.composition}'",
            result.report.composition == "Centered subject")
    }

    // === Horizon tilt ===

    @Test
    fun `tilted horizon is reported and level horizon is not`() {
        fun tiltedFrame(angleDeg: Double): Bitmap {
            val w = 240; val h = 120
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val tan = kotlin.math.tan(Math.toRadians(angleDeg))
            for (y in 0 until h) for (x in 0 until w) {
                val boundary = h / 2.0 + tan * (x - w / 2.0)
                val v = if (y < boundary) 200 else 60
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
            return bitmap
        }
        val level = ImageAnalyzer.analyze(tiltedFrame(0.0))
        assertTrue("level horizon must not be flagged; got '${level.report.perspectiveCorrection}'",
            level.report.perspectiveCorrection == null)

        val tilted = ImageAnalyzer.analyze(tiltedFrame(6.0))
        assertNotNull("6° tilt must be reported; got '${tilted.report.perspectiveCorrection}'",
            tilted.report.perspectiveCorrection)
    }

    // === White balance: anchor-driven correction ===

    @Test
    fun `warm gray-card cast is corrected via neutral anchors`() {
        // Warm-cast gray card: strong anchor evidence, low saturation → cast must be corrected.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(180, 130, 90))
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val wb = plan.decisions.find { it.parameter == "WhiteBalance" }
        assertNotNull("warm gray-card cast must produce a WhiteBalance decision (anchors=${analysis.neutralAnchors.count}, castNorm R-B=${analysis.neutralAnchors.avgR - analysis.neutralAnchors.avgB})", wb)
        assertTrue("warm cast correction must cool (negative); got ${wb!!.magnitude}", wb.magnitude < 0f)
    }

    @Test
    fun `neutral frame gets no white balance decision`() {
        val bitmap = createSolid(128, 128, 128)
        val analysis = ImageAnalyzer.analyze(bitmap)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("neutral frame must not be cast-corrected",
            plan.decisions.find { it.parameter == "WhiteBalance" })
    }

    // === P1: WB tint axis (green-magenta) ===

    @Test
    fun `green cast is measured and neutralized via the tint decision`() {
        // Green-cast gray card: G sits far above the R/B midpoint. Anchors carry the evidence.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(110, 165, 112))
        val analysis = ImageAnalyzer.analyze(bitmap)
        val na = analysis.neutralAnchors
        assertTrue("green gray card must have anchors; count=${na.count}", na.count > 1000)
        assertTrue("tintNorm must be positive (G above midpoint); got ${na.tintNorm}",
            na.tintNorm > 0.05f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val tint = plan.decisions.find { it.parameter == "WhiteBalanceTint" }
        assertNotNull("green cast must produce a WhiteBalanceTint decision (tintNorm=${na.tintNorm})", tint)
        assertTrue("green cast correction must pull G down (negative); got ${tint!!.magnitude}",
            tint.magnitude < 0f)
        // Render-level proof: apply the plan's WB pass; the G/R/B gap must shrink.
        val px = IntArray(100 * 100) { Color.rgb(110, 165, 112) }
        ImageProcessor.applyWhiteBalance(px, 100, 100, plan, analysis)
        val c = px[0]
        val gapBefore = 165f - (110f + 112f) / 2f
        val gapAfter = Color.green(c) - (Color.red(c) + Color.blue(c)) / 2f
        assertTrue("tint render must shrink the G gap (before=$gapBefore after=$gapAfter)",
            abs(gapAfter) < abs(gapBefore))
    }

    @Test
    fun `magenta cast produces a positive tint decision`() {
        // Magenta-cast gray card: G sits far below the R/B midpoint.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(165, 108, 162))
        val analysis = ImageAnalyzer.analyze(bitmap)
        val na = analysis.neutralAnchors
        assertTrue("magenta gray card must have anchors; count=${na.count}", na.count > 1000)
        assertTrue("tintNorm must be negative (G below midpoint); got ${na.tintNorm}",
            na.tintNorm < -0.05f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val tint = plan.decisions.find { it.parameter == "WhiteBalanceTint" }
        assertNotNull("magenta cast must produce a WhiteBalanceTint decision (tintNorm=${na.tintNorm})", tint)
        assertTrue("magenta cast correction must lift G (positive); got ${tint!!.magnitude}",
            tint.magnitude > 0f)
    }

    @Test
    fun `neutral frame gets no tint decision`() {
        val analysis = ImageAnalyzer.analyze(createSolid(128, 128, 128))
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("neutral frame must not be tint-corrected",
            plan.decisions.find { it.parameter == "WhiteBalanceTint" })
    }

    // === P1: mixed-lighting restraint ===

    @Test
    fun `mixed lighting suppresses global white balance`() {
        // Two neutral halves under DIFFERENT lights: left half warm gray (168,128,98),
        // right half cool gray (98,128,168). Both halves are full of anchors, but they
        // disagree — a global WB would damage one light. The engine must stay its hand.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 100) for (x in 0 until 100) {
            bitmap.setPixel(x, y, if (x < 50) Color.rgb(168, 128, 98) else Color.rgb(98, 128, 168))
        }
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertTrue("halves must measurably disagree; got ${analysis.neutralAnchors.halfDisagreement}",
            analysis.neutralAnchors.halfDisagreement > 0.06f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("mixed lighting must suppress global warmth WB",
            plan.decisions.find { it.parameter == "WhiteBalance" })
        assertNull("mixed lighting must suppress tint WB",
            plan.decisions.find { it.parameter == "WhiteBalanceTint" })
    }

    @Test
    fun `single-light frame keeps white balance available`() {
        // Uniform warm gray card: halves agree → the mixed-light gate must NOT fire.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(180, 130, 90))
        val analysis = ImageAnalyzer.analyze(bitmap)
        assertTrue("uniform frame halves must agree; got ${analysis.neutralAnchors.halfDisagreement}",
            analysis.neutralAnchors.halfDisagreement < 0.06f)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNotNull("uniform warm cast must still be corrected",
            plan.decisions.find { it.parameter == "WhiteBalance" })
    }
}
