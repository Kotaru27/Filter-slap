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
class ContextualDecisionEngineTest {

    private fun createSolidBitmap(color: Int, width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return bitmap
    }

    private fun analyzeAndEvaluate(bitmap: Bitmap): EditPlan {
        val analysis = ImageAnalyzer.analyze(bitmap)
        return ContextualDecisionEngine.evaluate(analysis)
    }

    private fun buildAnalysis(
        pixelCount: Int = 10000,
        isDarkImage: Boolean = false,
        isBrightImage: Boolean = false,
        p01: Float = 0f, p05: Float = 0f, p25: Float = 0.25f, p50: Float = 0.5f,
        p75: Float = 0.75f, p95: Float = 0.95f, p99: Float = 1f,
        shadowDensity: Float = 0f, midtoneDensity: Float = 1f, highlightDensity: Float = 0f,
        iqr: Float = 0.5f, occupancy: Float = 1f, asymmetry: Float = 0.5f,
        noiseConfidence: Float = 0f, blurConfidence: Float = 0f, edgeStrength: Float = 50f,
        highlightClipping: Float = 0f, shadowCrushing: Float = 0f,
        avgS: Float = 0f, channelClipping: Float = 0f,
        skyScore: Float = 0f, greenScore: Float = 0f, skinToneScore: Float = 0f,
        chromaNoise: Float = 0f,
        avgR: Float = 128f, avgG: Float = 128f, avgB: Float = 128f,
        regionSkyBlown: Float = 0f, regionSkyDull: Float = 1f, regionSkyContrast: Float = 0.5f,
        regionVegDrab: Float = 1f, regionSubjectDark: Float = 0f, regionSubjectBlown: Float = 0f,
        regionShadowDetailLoss: Float = 0f
    ): AnalysisResult {
        return AnalysisResult(
            avgLuma = 128f, contrastRatio = occupancy, isLowContrast = iqr < 0.2f,
            isDarkImage = isDarkImage, isBrightImage = isBrightImage,
            isWarm = false, isCool = false,
            sharpnessScore = edgeStrength, noiseScore = noiseConfidence,
            avgR = avgR, avgG = avgG, avgB = avgB,
            minLuma = (p01 * 255).toInt(), maxLuma = (p99 * 255).toInt(),
            minR = 0, maxR = 255, minG = 0, maxG = 255, minB = 0, maxB = 255,
            analysisWidth = 100, analysisHeight = 100,
            skyMask = FloatArray(pixelCount), vegMask = FloatArray(pixelCount),
            skinMask = FloatArray(pixelCount), metalMask = FloatArray(pixelCount),
            stoneMask = FloatArray(pixelCount), detailMask = FloatArray(pixelCount),
            blurMask = FloatArray(pixelCount), noiseMask = FloatArray(pixelCount),
            brightMask = FloatArray(pixelCount), shadowMask = FloatArray(pixelCount),
            skinToneScore = skinToneScore, stoneScore = 0f, greenScore = greenScore, goldBrassScore = 0f,
            skyScore = skyScore,
            report = AnalysisReport(
                sceneCategory = "", photographyGenre = "", primarySubject = "",
                lighting = "", composition = "", dynamicRange = "",
                histogramBias = "", colorTemperature = "", suggestedEditingStyle = "",
                toneAdjustments = "", colorAdjustments = "", confidenceScore = 0
            ),
            recommendedCrops = emptyList(),
            pixelCount = pixelCount,
            tonalStructure = TonalStructure(
                p01 = p01, p05 = p05, p25 = p25, p50 = p50, p75 = p75, p95 = p95, p99 = p99,
                shadowDensity = shadowDensity, midtoneDensity = midtoneDensity,
                highlightDensity = highlightDensity, iqr = iqr, occupancy = occupancy,
                asymmetry = asymmetry, blackPoint = p05, whitePoint = p95
            ),
            signalQuality = SignalQuality(
                noiseConfidence = noiseConfidence, blurConfidence = blurConfidence,
                edgeStrength = edgeStrength, textureStrength = iqr,
                snr = if (noiseConfidence > 0) edgeStrength / noiseConfidence else 100f,
                highlightClipping = highlightClipping, shadowCrushing = shadowCrushing
            ),
            colorAnalysis = ColorAnalysis(
                avgS = avgS, p95S = 0f, hueStability = 0f,
                chromaNoise = chromaNoise,
                neutralEvidence = 0f, channelClipping = channelClipping
            ),
            regionDeficiencies = RegionDeficiency(
                skyBlown = regionSkyBlown, skyDull = regionSkyDull,
                skyLocalContrast = regionSkyContrast, vegDrab = regionVegDrab,
                subjectDark = regionSubjectDark, subjectBlown = regionSubjectBlown,
                shadowDetailLoss = regionShadowDetailLoss
            )
        )
    }

    @Test
    fun `empty analysis produces empty plan`() {
        val analysis = buildAnalysis(pixelCount = 0)
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertTrue("Empty analysis should produce no decisions", plan.decisions.isEmpty())
    }

    @Test
    fun `balanced gray image yields minimal edits`() {
        val bitmap = createSolidBitmap(Color.rgb(128, 128, 128))
        val plan = analyzeAndEvaluate(bitmap)
        assertTrue(
            "Balanced image should have minimal edits",
            plan.decisions.isEmpty() || plan.decisions.all { abs(it.magnitude) <= 0.16f }
        )
    }

    @Test
    fun `healthy photograph gets controlled enhancement but no corrective edits`() {
        // Moderate contrast, no cast, no crushed/blown regions, no muted color, no haze,
        // subject region healthy: AUTO gives a tasteful enhancement baseline (contrast, clarity,
        // vibrance) but NO defect-like corrections (exposure, shadows, highlights, cast fix).
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p01 = 0.05f, p05 = 0.1f, p25 = 0.35f, p50 = 0.5f,
            p75 = 0.65f, p95 = 0.9f, p99 = 0.98f,
            shadowDensity = 0.15f, midtoneDensity = 0.6f, highlightDensity = 0.25f,
            iqr = 0.3f, occupancy = 0.8f, asymmetry = 0.5f,
            noiseConfidence = 2f, blurConfidence = 5f, edgeStrength = 40f,
            highlightClipping = 0f, shadowCrushing = 0f,
            avgS = 0.4f, channelClipping = 0f,
            avgR = 128f, avgG = 128f, avgB = 128f,
            regionSkyBlown = 0f, regionSkyDull = 0.5f, regionSkyContrast = 0.5f,
            regionVegDrab = 0.5f, regionSubjectDark = 0.1f, regionSubjectBlown = 0.1f,
            regionShadowDetailLoss = 0.1f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val corrective = plan.decisions.filter {
            it.parameter in listOf("Exposure", "Shadows", "Highlights", "WhiteBalance")
        }
        assertTrue("Healthy photo should have no corrective edits", corrective.isEmpty())
        val enhancement = plan.decisions.filter { it.parameter in listOf("Contrast", "Clarity", "Vibrance") }
        assertTrue("Healthy photo should receive the AUTO enhancement baseline", enhancement.isNotEmpty())
    }

    @Test
    fun `intentional low-key image is preserved`() {
        val bitmap = createSolidBitmap(Color.rgb(30, 30, 30))
        val plan = analyzeAndEvaluate(bitmap)
        val exposure = plan.decisions.find { it.parameter == "Exposure" }
        assertNull("Intentional dark image should have no Exposure decision", exposure)
        val shadows = plan.decisions.find { it.parameter == "Shadows" }
        assertNull("Intentional dark image should have no Shadows decision", shadows)
    }

    @Test
    fun `intentional high-key image is preserved`() {
        val bitmap = createSolidBitmap(Color.rgb(230, 230, 230))
        val plan = analyzeAndEvaluate(bitmap)
        val exposure = plan.decisions.find { it.parameter == "Exposure" }
        assertNull("Intentional bright image should have no Exposure decision", exposure)
        val highlights = plan.decisions.find { it.parameter == "Highlights" }
        assertNull("Intentional bright image should have no Highlights decision", highlights)
    }

    @Test
    fun `low-key with crushing still preserves intent`() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        for (y in 0 until 95) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(30, 30, 30))
        for (y in 95 until 100) for (x in 0 until 100) bitmap.setPixel(x, y, Color.rgb(2, 2, 2))
        val plan = analyzeAndEvaluate(bitmap)
        val exposure = plan.decisions.find { it.parameter == "Exposure" }
        assertNull("Low-key with mild crushing should preserve dark intent", exposure)
        val shadows = plan.decisions.find { it.parameter == "Shadows" }
        assertNull("Low-key with mild crushing should not attempt shadow recovery", shadows)
    }

    @Test
    fun `shadow recovery triggered when conditions met`() {
        // real crushed shadows: shadowCrushing>0.04 and shadowDetailLoss>0.3, not intentional dark.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false,
            p05 = 0.1f, p25 = 0.51f, p50 = 0.4f, p75 = 0.6f, p95 = 0.706f,
            shadowDensity = 0.3f, shadowCrushing = 0.05f,
            noiseConfidence = 0f,
            regionShadowDetailLoss = 0.5f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val shadows = plan.decisions.find { it.parameter == "Shadows" && it.regionMask == "Shadows" }
        assertNotNull("Shadow recovery should be triggered", shadows)
        assertTrue("Shadow magnitude should be positive", shadows!!.magnitude > 0f)
        assertEquals("Shadow region mask should be Shadows", "Shadows", shadows.regionMask)
    }

    @Test
    fun `contrast decision when image is genuinely flat`() {
        val analysis = buildAnalysis(
            pixelCount = 10000,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.55f, p95 = 0.9f,
            iqr = 0.12f, occupancy = 0.8f, asymmetry = 0.5f,
            noiseConfidence = 0f, blurConfidence = 0f, edgeStrength = 50f,
            avgS = 0.3f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val contrast = plan.decisions.find { it.parameter == "Contrast" }
        assertNotNull("Contrast decision should be generated", contrast)
        assertTrue("Contrast magnitude should be positive", contrast!!.magnitude > 0f)
        assertEquals("Contrast region mask should be Global", "Global", contrast.regionMask)
    }

    @Test
    fun `saturation boost for desaturated non-dark image`() {
        val bitmap = createSolidBitmap(Color.rgb(128, 128, 128))
        val plan = analyzeAndEvaluate(bitmap)
        val saturation = plan.decisions.find {
            it.parameter == "Saturation" && it.regionMask == "Global"
        }
        assertNotNull("Desaturated image should get saturation boost", saturation)
        assertTrue("Saturation magnitude should be positive", saturation!!.magnitude > 0f)
    }

    @Test
    fun `high noise suppresses clarity detail-enhancement`() {
        // Noise must suppress aggressive detail processing: a noisy frame is never sharpened
        // into visible grain, so Clarity must NOT be emitted at high noiseConfidence.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            noiseConfidence = 30f, blurConfidence = 10f, edgeStrength = 40f,
            avgS = 0.4f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val clarity = plan.decisions.find { it.parameter == "Clarity" }
        assertNull("High-noise image should not be given Clarity", clarity)
    }

    @Test
    fun `low-noise detailed image gets controlled clarity`() {
        // Enhanced AUTO adds tasteful local structure when detail is real and noise is low.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            noiseConfidence = 2f, blurConfidence = 5f, edgeStrength = 40f,
            avgS = 0.4f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val clarity = plan.decisions.find { it.parameter == "Clarity" }
        assertNotNull("Low-noise detailed image should get controlled Clarity", clarity)
        assertTrue("Clarity magnitude should be modest", clarity!!.magnitude in 0.05f..0.25f)
    }

    @Test
    fun `well-exposed image has few or small decisions`() {
        // Moderate contrast, no clipping, no crushing, low noise
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p01 = 0.05f, p05 = 0.1f, p25 = 0.35f, p50 = 0.5f,
            p75 = 0.65f, p95 = 0.9f, p99 = 0.98f,
            shadowDensity = 0.15f, midtoneDensity = 0.6f, highlightDensity = 0.25f,
            iqr = 0.3f, occupancy = 0.8f, asymmetry = 0.5f,
            noiseConfidence = 2f, blurConfidence = 5f, edgeStrength = 40f,
            highlightClipping = 0f, shadowCrushing = 0f,
            avgS = 0.3f, channelClipping = 0f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val largeDecisions = plan.decisions.filter { abs(it.magnitude) > 0.2f }
        assertTrue(
            "Well-exposed image should have no large-magnitude decisions",
            largeDecisions.isEmpty()
        )
    }

    @Test
    fun `joint optimization reduces shadow magnitude when exposure is applied`() {
        // globalDark: p50<0.30, not intentional dark, subjectDark>0.25 -> Exposure fires.
        // crushedReal: shadowCrushing>0.04, shadowDetailLoss>0.3 -> Shadows fires.
        // Joint: Shadows magnitude is dragged down by the Exposure lift.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.25f, p75 = 0.6f, p95 = 0.85f,
            shadowDensity = 0.3f, shadowCrushing = 0.05f,
            noiseConfidence = 0f, iqr = 0.3f, occupancy = 0.75f,
            avgS = 0.3f, asymmetry = 0.2f,
            regionSubjectDark = 0.4f, regionShadowDetailLoss = 1.0f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)

        val exposure = plan.decisions.find {
            it.parameter == "Exposure" && it.regionMask == "Global"
        }
        val shadows = plan.decisions.find { it.parameter == "Shadows" && it.regionMask == "Shadows" }

        assertNotNull("Global Exposure should be generated", exposure)
        assertTrue("Exposure should be positive", exposure!!.magnitude > 0f)
        assertNotNull("Shadows should be generated", shadows)
        assertTrue(
            "Shadow magnitude should be reduced by joint optimization below its raw value",
            shadows!!.magnitude < 0.25f && shadows.magnitude > 0f
        )
    }

    @Test
    fun `region masks are correct for each decision type`() {
        // A blown sky (regionSkyBlown > 0.5) yields a Sky-region edit; real crushed shadows
        // yield a Shadows-region edit. Sky presence alone is never enough.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.8f, p95 = 0.97f,
            shadowDensity = 0.3f, shadowCrushing = 0.05f,
            noiseConfidence = 0f, edgeStrength = 50f,
            iqr = 0.5f, occupancy = 0.87f,
            skyScore = 30f, regionSkyBlown = 0.7f,
            regionShadowDetailLoss = 0.5f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)

        for (decision in plan.decisions) {
            when (decision.parameter) {
                "Shadows" -> assertTrue(
                    "Shadows should use a regional mask",
                    decision.regionMask in listOf("Shadows", "Subject")
                )
                "Exposure" -> assertTrue(
                    "Exposure mask should match its target",
                    decision.regionMask in listOf("Global", "Sky")
                )
                "Contrast" -> assertEquals(
                    "Contrast should use Global mask",
                    "Global", decision.regionMask
                )
            }
        }

        val skyDecisions = plan.decisions.filter { it.regionMask == "Sky" }
        assertTrue("Blown sky should produce sky-region decisions", skyDecisions.isNotEmpty())
        skyDecisions.forEach { decision ->
            assertEquals("Sky decisions should apply to Sky region", "Sky", decision.regionMask)
        }
    }

    @Test
    fun `dehaze decision emitted for milky low contrast image`() {
        // Haze: iqr low, p50 mid, weak edges, desaturated, low chroma noise, no crushing.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.35f, p25 = 0.45f, p50 = 0.6f, p75 = 0.7f, p95 = 0.85f,
            iqr = 0.15f, occupancy = 0.5f,
            noiseConfidence = 0f, blurConfidence = 0f, edgeStrength = 10f,
            avgS = 0.2f, chromaNoise = 0.05f,
            shadowCrushing = 0f, highlightClipping = 0f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val dehaze = plan.decisions.find { it.parameter == "Dehaze" }
        assertNotNull("Hazy image should get a Dehaze decision", dehaze)
        assertTrue("Dehaze magnitude should be positive", dehaze!!.magnitude > 0f)
        assertEquals("Dehaze region should be Global", "Global", dehaze.regionMask)
    }

    @Test
    fun `genuinely muted image gets a global saturation lift`() {
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.1f, regionSkyDull = 0.3f, regionVegDrab = 0.7f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val sat = plan.decisions.find { it.parameter == "Saturation" && it.regionMask == "Global" }
        assertNotNull("Genuinely muted image should get a global saturation lift", sat)
        assertTrue("Saturation magnitude should be positive", sat!!.magnitude > 0f)
    }

    @Test
    fun `moderately saturated image gets no global saturation boost`() {
        // Global saturation is only lifted for genuinely muted frames (avgS<0.15). A healthy
        // moderate-vividness frame is left alone (maximum restraint).
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.3f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val sat = plan.decisions.find { it.parameter == "Saturation" && it.regionMask == "Global" }
        assertNull("Moderately saturated frame should not get a global saturation boost", sat)
    }

    @Test
    fun `regional subject shadow recovery emitted when subject is dark but frame is healthy`() {
        // A backlit figure: subject region is dark (subjectDark>0.4) while the global frame is
        // healthy, so a Skin-masked shadow recovery edit is applied instead of a global blow-up.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false,
            p05 = 0.1f, p25 = 0.4f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            shadowDensity = 0.3f, shadowCrushing = 0.05f,
            noiseConfidence = 0f, skinToneScore = 60f,
            regionSubjectDark = 0.6f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val subject = plan.decisions.find { it.parameter == "Shadows" && it.regionMask == "Subject" }
        assertNotNull("Dark subject should get a regional Subject shadow decision", subject)
        assertTrue("Subject shadow magnitude should be positive", subject!!.magnitude > 0f)
    }

    @Test
    fun `regional vegetation saturation emitted when foliage present`() {
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.3f, greenScore = 50f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val veg = plan.decisions.find { it.parameter == "Saturation" && it.regionMask == "Vegetation" }
        assertNotNull("Vegetation region should get a saturation decision", veg)
        assertTrue("Vegetation saturation magnitude should be positive", veg!!.magnitude > 0f)
    }

    @Test
    fun `sky detection alone produces no sky enhancement`() {
        // A detected, healthy sky (skyDull low, skyBlown == 0) is evidence, not permission to edit.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.4f, skyScore = 60f,
            regionSkyBlown = 0f, regionSkyDull = 0.2f, regionVegDrab = 0.4f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val skyEdit = plan.decisions.find { it.regionMask == "Sky" }
        assertNull("Detected healthy sky should receive no edit", skyEdit)
    }

    @Test
    fun `healthy vegetation alone produces no saturation edit`() {
        // Detected green foliage with native saturation (vegDrab low) must NOT be boosted.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.4f, greenScore = 60f,
            regionVegDrab = 0.4f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val veg = plan.decisions.find { it.parameter == "Saturation" && it.regionMask == "Vegetation" }
        assertNull("Healthy vegetation should get no saturation boost", veg)
    }

    @Test
    fun `bright sky does not globally brighten a healthy subject`() {
        // A well-exposed subject against a bright sky: recover the blown sky region specifically,
        // never push a global exposure that would drag the background/subject relationship.
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.45f, p50 = 0.5f, p75 = 0.7f, p95 = 0.97f,
            occupancy = 0.8f, iqr = 0.3f,
            avgS = 0.4f, skyScore = 50f,
            regionSkyBlown = 0.8f, regionSubjectDark = 0.15f, regionVegDrab = 0.4f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val globalExp = plan.decisions.find { it.parameter == "Exposure" && it.regionMask == "Global" }
        assertNull("Healthy subject with bright sky must not get a global exposure", globalExp)
        val skyExp = plan.decisions.find { it.parameter == "Exposure" && it.regionMask == "Sky" }
        assertNotNull("Blown sky should get a localized sky recovery", skyExp)
    }

    @Test
    fun `white balance corrects an obvious warm cast`() {
        // Warm cast: boosted R, reduced B, but low saturation (a cast, not a scene of red objects).
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.2f, avgR = 190f, avgG = 128f, avgB = 70f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val wb = plan.decisions.find { it.parameter == "WhiteBalance" }
        assertNotNull("Warm cast should produce a WhiteBalance decision", wb)
        // Negative magnitude cools (reduces R, adds B) to cancel the warm cast.
        assertTrue("Warm cast should be corrected toward cool (negative)", wb!!.magnitude < 0f)
    }

    @Test
    fun `white balance is skipped for saturated or skin-heavy frames`() {
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.1f, p25 = 0.3f, p50 = 0.5f, p75 = 0.7f, p95 = 0.9f,
            occupancy = 0.8f, iqr = 0.4f,
            avgS = 0.6f, avgR = 190f, avgG = 128f, avgB = 70f, skinToneScore = 80f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val wb = plan.decisions.find { it.parameter == "WhiteBalance" }
        assertNull("Saturated, skin-heavy frame should keep its natural white balance", wb)
    }

    // F13: the old thresholds left a dead zone at iqr [0.16, 0.18) where NEITHER the
    // corrective contrast (fires < 0.16) nor the enhancement baseline (blocked < 0.18)
    // produced a Contrast decision. A genuinely flat frame at 0.165 must get exactly one.
    @Test
    fun `flat image in the former contrast dead zone still gets a contrast decision`() {
        val analysis = buildAnalysis(
            pixelCount = 10000,
            isDarkImage = false, isBrightImage = false,
            p05 = 0.42f, p25 = 0.42f, p50 = 0.5f, p75 = 0.585f, p95 = 0.585f,
            iqr = 0.165f, occupancy = 0.165f, asymmetry = 0.5f,
            noiseConfidence = 0f, blurConfidence = 0f, edgeStrength = 50f,
            avgS = 0.3f
        )
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val contrastDecisions = plan.decisions.filter { it.parameter == "Contrast" }
        assertEquals("Exactly one Contrast decision for the dead-zone frame", 1, contrastDecisions.size)
        assertTrue("Contrast magnitude should be positive", contrastDecisions[0].magnitude > 0f)
    }
}
