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
 * P3: SPLIT-TONE — profile-driven zone color grading (cool shadows / warm highlights).
 *
 * Invariants:
 * 1. LANDSCAPE gets both zone decisions; GENERAL gets highlights only; PORTRAIT and
 *    LOW_LIGHT get none (skin truth / noise restraint).
 * 2. applySplitTone cools the shadow zone, warms the highlight zone, leaves midtones
 *    untouched, and never grades skin pixels. (Tested directly on the pass — the pipeline
 *    A/B is confounded by tonal stages that also move chroma proxies.)
 * 3. Magnitudes are bounded (≤ 0.04) — the look is felt, not seen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SplitToneTest {

    // Proven profile fixtures (same patterns as GradingProfileTest).
    private fun landscapeLikeScene(): Bitmap {
        val w = 256; val h = 256
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            if (y < 110) {
                bitmap.setPixel(x, y, Color.rgb(70, 130, 210))
            } else {
                val g = if (((x / 12) + (y / 12)) % 2 == 0) 30 else 110
                bitmap.setPixel(x, y, Color.rgb(20 + g / 2, (60 + g).coerceAtMost(255), 25 + g / 4))
            }
        }
        return bitmap
    }

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
    fun `landscape profile emits both split-tone decisions, bounded`() {
        val analysis = ImageAnalyzer.analyze(landscapeLikeScene())
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertEquals("LANDSCAPE", plan.profileName)

        val sh = plan.decisions.find { it.parameter == "SplitToneShadows" }
        assertNotNull("LANDSCAPE must grade shadows", sh)
        assertTrue("shadow grade bounded (<=0.04); got ${sh!!.magnitude}", sh.magnitude in 0f..0.04f)

        val hi = plan.decisions.find { it.parameter == "SplitToneHighlights" }
        assertNotNull("LANDSCAPE must grade highlights", hi)
        assertTrue("highlight grade bounded (<=0.04); got ${hi!!.magnitude}", hi.magnitude in 0f..0.04f)
    }

    @Test
    fun `low-light profile skips split-tone`() {
        val plan = ContextualDecisionEngine.evaluate(ImageAnalyzer.analyze(lowLightLikeScene()))
        assertEquals("LOW_LIGHT", plan.profileName)
        assertNull("LOW_LIGHT must not split-tone",
            plan.decisions.find { it.parameter.startsWith("SplitTone") })
    }

    // A synthetic analysis carrier: real masks from a rendered scene shape, overridden for
    // the three zones. Only the mask arrays this pass reads are populated.
    private fun zoneAnalysis(w: Int, h: Int, build: (x: Int, y: Int) -> Triple<Float, Float, Float>): AnalysisResult {
        val n = w * h
        val shadow = FloatArray(n); val bright = FloatArray(n); val skin = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val (sv, bv, kv) = build(x, y)
            shadow[y * w + x] = sv; bright[y * w + x] = bv; skin[y * w + x] = kv
        }
        return AnalysisResult(
            avgLuma = 128f, contrastRatio = 0.5f, isLowContrast = false,
            isDarkImage = false, isBrightImage = false, isWarm = false, isCool = false,
            sharpnessScore = 10f, noiseScore = 0f,
            avgR = 128f, avgG = 128f, avgB = 128f,
            minLuma = 0, maxLuma = 255, minR = 0, maxR = 255, minG = 0, maxG = 255, minB = 0, maxB = 255,
            analysisWidth = w, analysisHeight = h,
            skyMask = FloatArray(n), vegMask = FloatArray(n), skinMask = skin, metalMask = FloatArray(n),
            stoneMask = FloatArray(n), detailMask = FloatArray(n), blurMask = FloatArray(n), noiseMask = FloatArray(n),
            brightMask = bright, shadowMask = shadow,
            subjectMask = FloatArray(n), bgMask = FloatArray(n),
            skinToneScore = 0f, stoneScore = 0f, greenScore = 0f, goldBrassScore = 0f, skyScore = 0f,
            report = AnalysisReport("", "", "", "", "", "", "", "", "", "", "", 0),
            recommendedCrops = emptyList(),
            pixelCount = n,
            tonalStructure = TonalStructure(0f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1f, 0.4f, 0.8f, 0.5f, 0.4f, 0.8f, 0.5f, 0.1f, 0.9f),
            signalQuality = SignalQuality(0f, 0f, 10f, 0f, 100f, 0f, 0f),
            colorAnalysis = ColorAnalysis(0f, 0f, 0f, 0f, 0f, 0f),
            regionDeficiencies = RegionDeficiency(0f, 0f, 0f, 0f, 0f, 0f, 0f),
            neutralAnchors = NeutralAnchors(0, 0f, 0f, 0f, 0f, 0f)
        )
    }

    @Test
    fun `pass cools shadows, warms highlights, midtones and skin untouched`() {
        val w = 64; val h = 64
        // Left third: shadow zone (weight 1). Right third: highlight zone (weight 1).
        // Middle third: neither. A skin patch sits inside the shadow zone.
        val analysis = zoneAnalysis(w, h) { x, _ ->
            when {
                x < 21 -> Triple(1f, 0f, 0f)
                x in 21..42 && (x + 0) in 26..32 -> Triple(1f, 0f, 1f)  // skin inside shadows
                x < 43 -> Triple(0f, 0f, 0f)
                else -> Triple(0f, 1f, 0f)
            }
        }
        val plan = EditPlan(listOf(
            EditDecision("SplitToneShadows", "Shadows", "All", 0.04f, 0.5f, 0.4f, 0.2f),
            EditDecision("SplitToneHighlights", "Highlights", "All", 0.04f, 0.5f, 0.4f, 0.2f)
        ))
        val px = IntArray(w * h) { Color.rgb(128, 128, 128) }
        ImageProcessor.applySplitTone(px, w, h, plan, analysis)

        // Blue-yellow proxy: (R+G)/2 - B. Cool (toward blue) = smaller value.
        fun proxy(x: Int, y: Int): Double {
            val c = px[y * w + x]
            return ((Color.red(c) + Color.green(c)) / 2.0) - Color.blue(c)
        }

        val shadowProxy = proxy(5, 32)
        val midProxy = proxy(32, 32)   // middle-third, no zone — but x=32 is the skin column!
        val midProxy2 = proxy(40, 32)  // another middle-third column
        val highlightProxy = proxy(58, 32)
        val skinProxy = proxy(28, 32)

        // Baseline: an ungraded midtone is 0 (128,128,128).
        assertTrue("shadow zone must cool (proxy=$shadowProxy)", shadowProxy < -0.5)
        assertTrue("highlight zone must warm (proxy=$highlightProxy)", highlightProxy > 0.5)
        assertTrue("midtones must stay neutral (proxy=$midProxy2)", kotlin.math.abs(midProxy2) < 0.5)
        assertTrue("skin must never be graded (proxy=$skinProxy)", kotlin.math.abs(skinProxy) < 0.5)
        assertTrue("unzoned midtone must be neutral (proxy=$midProxy)", kotlin.math.abs(midProxy) < 0.5)
    }
}
