package com.example.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class EditDecision(
    val parameter: String,
    val regionMask: String,
    val frequencyBand: String,
    val magnitude: Float,
    val confidence: Float,
    val expectedBenefit: Float,
    val expectedHarm: Float,
    val dependencies: List<String> = emptyList()
)

data class EditPlan(
    val decisions: List<EditDecision>,
    // Which grading profile drove this plan and why — surfaced in the report so the user
    // sees the app committed to a scene-specific edit, not a generic filter.
    val profileName: String = "General",
    val profileReason: String = ""
)

object ContextualDecisionEngine {

    // Grading profiles: the KIND of work a senior editor does differs by scene. Profiles change
    // the shape and bravery of the plan; every decision is still defect-gated by the same
    // philosophy (detection is never permission, intent is preserved, harm is gated).
    enum class GradingProfile { GENERAL, PORTRAIT, LANDSCAPE, LOW_LIGHT }

    fun evaluate(analysis: AnalysisResult): EditPlan {
        val decisions = mutableListOf<EditDecision>()
        if (analysis.pixelCount <= 0) return EditPlan(emptyList())

        val t = analysis.tonalStructure
        val sq = analysis.signalQuality
        val ca = analysis.colorAnalysis
        val rd = analysis.regionDeficiencies

        // ---- Profile selection (evidence-based, from the analyzer's scene scores) ----
        // Portrait gate at 15: the skin score is a mask-weighted frame average, so a REAL
        // portrait (subject covering ~40-60% of frame, skin mask ~0.35-0.5 on subject) scores
        // 15-25. A 20 gate would miss realistic portraits and only catch full-frame skin
        // closeups. 15 keeps incidental warm backgrounds (scoring <10) out.
        val lowLight = sq.noiseConfidence > 12f || (analysis.isDarkImage && sq.noiseConfidence > 6f)
        val portrait = analysis.skinToneScore > 15f
        val landscape = analysis.skyScore > 20f && analysis.greenScore > 8f
        val (profile, profileReason) = when {
            lowLight -> GradingProfile.LOW_LIGHT to
                "Low-light scene (noise ${"%.0f".format(sq.noiseConfidence)}) — denoise first, protect shadows, no texture amplification"
            portrait -> GradingProfile.PORTRAIT to
                "Portrait (skin ${(analysis.skinToneScore).toInt()}%) — skin-first grading: gentle curves, skin-safe color, subject isolation"
            landscape -> GradingProfile.LANDSCAPE to
                "Landscape (sky ${(analysis.skyScore).toInt()}%, foliage ${(analysis.greenScore).toInt()}%) — depth-first grading: tonal depth, sky protection, structured detail"
            else -> GradingProfile.GENERAL to
                "General scene — defect-first corrections; no automatic cinematic styling"
        }

        // Highlight-compromised frame: any local-contrast amplification (Clarity/Texture)
        // manufactures bright halos against bright regions and pushes shoulders into
        // clipping. When the frame is materially highlight-compromised, detail work is
        // softened proportionally (avoid generating halos around blown highlights).
        val highlightCompromised = sq.highlightClipping > 0.05f
        val detailSoftening = if (highlightCompromised) (0.55f - (sq.highlightClipping - 0.05f).coerceIn(0f, 0.25f)).coerceIn(0.3f, 0.55f) else 1f

        // Do not force dark images bright / bright images dark.
        val isIntentionalDark = t.p95 < 0.6f && sq.shadowCrushing < 0.1f && analysis.isDarkImage
        val isIntentionalBright = t.p05 > 0.4f && sq.highlightClipping < 0.1f && analysis.isBrightImage

        // Only apply corrections that have measurable evidence. A scene profile is NOT
        // permission to darken shadows, desaturate backgrounds, or impose a cinematic look.
        // Such baseline styling repeatedly changed technically balanced images.

        // ---- Declared defects (region-relative where meaningful) ----
        // White-balance cast evidence: prefer measured NEUTRAL ANCHORS (smooth, near-neutral
        // pixels) when enough exist; fall back to the avgR-avgB heuristic otherwise. Anchor
        // TRUST comes from coverage (count), never from channel tightness — a cast IS
        // channel spread, so tightness would reject the very evidence we want to measure.
        val na = analysis.neutralAnchors
        val anchorsUsable = na.count >= analysis.pixelCount * 0.005f
        val anchorCastNorm = if (anchorsUsable) {
            ((na.avgR - na.avgB) / 255f).coerceIn(-0.3f, 0.3f)
        } else 0f
        val castNorm = if (anchorsUsable) anchorCastNorm
        else ((analysis.avgR - analysis.avgB) / 255f).coerceIn(-0.3f, 0.3f)
        val castBad = abs(castNorm) > 0.10f &&
            ca.avgS < 0.45f && (analysis.skinToneScore / 100f) < 0.5f &&
            rd.subjectDark < 0.3f && rd.subjectBlown < 0.3f &&
            // With good anchors we can trust the measurement more than the hue heuristic;
            // without them keep the original restraint requirements.
            (anchorsUsable || ca.hueStability < 0.85f)

        // Global exposure is off only when the WHOLE frame is off *and* the subject region agrees,
        // otherwise a region-relative (masked) edit is used below instead of a global blow-up.
        val globalDark = t.p50 < 0.30f && !isIntentionalDark && rd.subjectDark > 0.25f
        val globalBright = t.p50 > 0.72f && !isIntentionalBright && rd.subjectBlown > 0.25f

        val flatLowContrast = t.iqr < 0.16f && t.occupancy > 0.25f && sq.noiseConfidence < 18f
        val crushedReal = sq.shadowCrushing > 0.04f && rd.shadowDetailLoss > 0.3f
        val blownReal = sq.highlightClipping > 0.04f && rd.subjectBlown > 0.2f

        val skyRealDefect = rd.skyBlown > 0.5f

        // Vegetation is only drab if its own saturation is genuinely low; naturally green foliage
        // is left alone (no "detection = permission to enhance").
        val vegRealDefect = rd.vegDrab > 0.72f && rd.vegDrab > ca.avgS

        val mutedReal = ca.avgS < 0.15f && !isIntentionalDark && ca.avgS < rd.skyDull && rd.vegDrab > 0.6f

        val hazeReal = t.iqr < 0.16f &&
            t.p50 in 0.40f..0.85f &&
            sq.edgeStrength < 18f &&
            ca.avgS < 0.35f &&
            sq.shadowCrushing < 0.08f &&
            t.occupancy > 0.2f

        // Region-relative subject defect: subject is under-exposed while the global frame is
        // healthy (e.g. a backlit figure). A global exposure would blow the background, so we
        // recover the subject region with a masked shadow edit instead.
        val subjectRegionDark = !isIntentionalDark && !globalDark && rd.subjectDark > 0.4f && t.p50 < 0.6f
        val subjectRegionBlown = !isIntentionalBright && !globalBright && rd.subjectBlown > 0.4f && t.p50 > 0.4f

        // ---- True-black / true-white stretch, only when real endpoints exist to recover ----
        if (!isIntentionalDark && t.iqr < 0.20f && t.occupancy > 0.15f &&
            t.blackPoint > 0.03f && sq.shadowCrushing < 0.08f) {
            val blackMag = (t.blackPoint * 0.5f).coerceIn(0.03f, 0.12f)
            if (blackMag > 0.03f) {
                decisions.add(EditDecision("Blacks", "Global", "Low", blackMag, 0.7f, 0.6f, 0.2f))
            }
        }
        // ---- True-white stretch: HIGHLIGHT FIDELITY FIRST ----
        // Only when the frame has almost NO clipping (<10%); substantial highlight clipping must not be stretched, real white-point headroom exists, and the
        // magnitude is capped at 0.08. Bright architecture/sky/skin highlights are exactly
        // what this edit destroys when it fires on an already-bright frame.
        if (!isIntentionalBright && t.occupancy > 0.15f && t.whitePoint < 0.97f && sq.highlightClipping < 0.10f) {
            val whiteMag = ((1f - t.whitePoint) * 0.4f).coerceIn(0.03f, 0.08f)
            if (whiteMag > 0.03f) {
                decisions.add(EditDecision("Whites", "Global", "Low", whiteMag, 0.7f, 0.6f, 0.2f))
            }
        }

        // ---- Exposure: only a genuine global+subject deficiency, small and capped ----
        if (globalDark) {
            val expMag = ((0.46f - t.p50) * 0.6f).coerceIn(0.05f, 0.25f)
            decisions.add(EditDecision("Exposure", "Global", "All", expMag, 0.8f, 0.6f, 0.15f))
        } else if (globalBright) {
            val expMag = ((0.46f - t.p50) * 0.6f).coerceIn(-0.25f, -0.05f)
            decisions.add(EditDecision("Exposure", "Global", "All", expMag, 0.8f, 0.6f, 0.15f))
        }

        // ---- Region-relative subject recovery (no global blow-up) ----
        if (subjectRegionDark && sq.noiseConfidence < 15f) {
            decisions.add(EditDecision("Shadows", "Subject", "Low", 0.18f * rd.subjectDark.coerceIn(0f, 1f), 0.6f, 0.5f, 0.15f))
        }
        if (subjectRegionBlown) {
            decisions.add(EditDecision("Highlights", "Subject", "Low", 0.18f * rd.subjectBlown.coerceIn(0f, 1f), 0.6f, 0.5f, 0.15f))
        }

        // ---- Real crushed shadows / blown highlights ----
        if (crushedReal && !isIntentionalDark) {
            val noisePenalty = if (sq.noiseConfidence > 5f) 0.4f else 1f
            val shadowMag = (rd.shadowDetailLoss * 0.25f * noisePenalty).coerceIn(0.05f, 0.25f)
            decisions.add(EditDecision("Shadows", "Shadows", "Low", shadowMag, 0.7f, 0.6f, 0.2f))
        }
        if (blownReal && !isIntentionalBright) {
            val highlightMag = (rd.subjectBlown * 0.25f).coerceIn(0.05f, 0.25f)
            decisions.add(EditDecision("Highlights", "Highlights", "Low", highlightMag, 0.7f, 0.6f, 0.2f))
        }

        // ---- Contrast: only when the image is genuinely flat (not a portrait/low-key intent) ----
        if (flatLowContrast && !isIntentionalDark && !isIntentionalBright) {
            val contrastMag = ((0.16f - t.iqr) * 0.6f).coerceIn(0.03f, 0.18f)
            decisions.add(EditDecision("Contrast", "Global", "Mid", contrastMag, 0.7f, 0.6f, 0.2f))
        }

        // ---- Sky: only recover a blown sky; never saturate sky for its mere presence ----
        if (skyRealDefect) {
            decisions.add(EditDecision("Exposure", "Sky", "Low", -0.18f * rd.skyBlown.coerceIn(0f, 1f), 0.7f, 0.6f, 0.2f))
        }

        // ---- Vegetation: only lift genuinely drab foliage, gently ----
        // Rendered vibrance-like on the veg mask (muted-first, vivid-capped in OKLab), so the
        // ceiling here bounds the WORST case for muted foliage; healthy greens (which pass
        // vegDrab but are already vivid) get almost nothing downstream.
        if (vegRealDefect) {
            val vegMag = (0.12f * (rd.vegDrab - 0.72f) / 0.28f).coerceIn(0.04f, 0.12f)
            decisions.add(EditDecision("Saturation", "Vegetation", "All", vegMag, 0.6f, 0.5f, 0.2f))
        }

        // ---- Global desaturation / muted frame ----
        // Muted-first + vivid-capped in the renderer; this only fires for genuinely muted
        // frames (avgS < 0.15), so the muted-weight is near 1 for nearly everything.
        if (mutedReal) {
            decisions.add(EditDecision("Saturation", "Global", "All", 0.10f, 0.5f, 0.4f, 0.2f))
        }

        // ---- Dehaze: only a clear milky-frame signature ----
        if (hazeReal) {
            val dehazeMag = ((0.16f - t.iqr) * 1.2f).coerceIn(0.04f, 0.15f)
            decisions.add(EditDecision("Dehaze", "Global", "Low", dehazeMag, 0.6f, 0.5f, 0.2f))
        }

        // ---- White balance: correct a genuine cast (defect-gated) ----
        // P1 MIXED-LIGHTING RULE: when the anchor halves disagree materially, the frame has
        // more than one light source (tungsten window + blue sky, stage + practicals). A
        // global WB correction is the WRONG tool — it would "fix" one light by damaging the
        // other. Detection here means RESTRAINT, not permission.
        val mixedLighting = na.halfDisagreement > 0.06f
        // A single, nearly uniform chromaticity cannot tell us whether the
        // material itself is colored or an otherwise-neutral material is under
        // colored lighting. The conservative choice is to abstain from global WB.
        // Unlike named-color/scene exceptions, this is invariant to hue,
        // orientation, dimensions and exact pixel values.
        val colorAmbiguous = ca.chromaDispersion.isFinite() && ca.chromaDispersion < 0.015f
        if (castBad && !mixedLighting && !colorAmbiguous) {
            // Magnitude from the measured cast (anchor-based when available), scaled by
            // anchor trust when we have it. Negative cools, positive warms — cancels the cast.
            // Clamped to the renderer's applyWhiteBalance cap (±0.18) so the emitted decision
            // IS the applied edit — the report prose prints this magnitude verbatim.
            val trust = if (anchorsUsable) 1f else 0.75f
            val wbMag = (-castNorm * trust).coerceIn(-0.18f, 0.18f)
            decisions.add(EditDecision("WhiteBalance", "Global", "All", wbMag, 0.55f, 0.4f, 0.25f))
        }
        // P1 TINT AXIS (green-magenta): the anchors' G offset from the R/B midpoint is the
        // measured tint cast. Fire only with usable anchors (the tint is anchor-evidence-only;
        // the avgR-avgB fallback has no meaningful tint analogue), never on mixed lighting,
        // and never alongside the hue-heuristic path where anchorless frames live.
        // SIGN = correction direction (negative reduces green, positive increases it).
        // The renderer respects direction and only adjusts near-neutral pixels.
        if (anchorsUsable && !mixedLighting && !colorAmbiguous && abs(na.tintNorm) > 0.015f) {
            val tintMag = (-na.tintNorm).coerceIn(-0.18f, 0.18f)
            decisions.add(EditDecision("WhiteBalanceTint", "Global", "All", tintMag, 0.5f, 0.4f, 0.25f))
        }

        // ===================================================================
        // PROFILE-DRIVEN ENHANCEMENT BASELINE.
        // The KIND of lift is scene-specific (a portrait is not graded like a
        // landscape); magnitudes are committed, with the renderer's harm/color
        // gates as the only safety net (no double throttling here).
        // SKIP unnecessary changes to an already good exposure or palette.
        // ===================================================================

        val alreadyGood = t.iqr > 0.35f && ca.avgS > 0.2f &&
            sq.noiseConfidence < 10f && sq.edgeStrength > 15f &&
            t.p50 in 0.35f..0.65f && sq.shadowCrushing < 0.03f && sq.highlightClipping < 0.03f

        // Small near-flat contrast recovery, NOT a mandatory grading baseline. Scenes with
        // a healthy tonal spread must not have their blacks deepened to manufacture "depth".
        val structureHeadroom = (sq.edgeStrength / 50f).coerceIn(0.5f, 1f)
        if (!alreadyGood && !isIntentionalDark && !isIntentionalBright && !flatLowContrast &&
            t.iqr in 0.16f..0.20f && t.occupancy > 0.25f && sq.noiseConfidence < 18f) {
            val evidence = ((0.20f - t.iqr) / 0.04f).coerceIn(0f, 1f)
            val ceiling = when (profile) {
                GradingProfile.LANDSCAPE -> 0.10f
                GradingProfile.PORTRAIT -> 0.06f
                GradingProfile.LOW_LIGHT -> 0.035f
                else -> 0.08f
            }
            val contrastMag = ceiling * evidence * structureHeadroom
            if (contrastMag > 0.012f) {
                decisions.add(EditDecision("Contrast", "Global", "Mid", contrastMag, 0.65f, 0.55f, 0.2f))
            }
        }

        // Local structure: Clarity — WHO gets it is profile-specific.
        // Portrait gate: subject-mask detail, not frame-wide edge density. A face has small
        // but critical structure (eyes, features) that frame-averaging dilutes; the SUBJECT
        // must have structure, and then Clarity is scoped to it.
        if (profile == GradingProfile.PORTRAIT &&
            sq.noiseConfidence < 22f && analysis.subjectMask.isNotEmpty() && analysis.skinToneScore > 15f) {
            val subjDetail = analysis.subjectMask.mapIndexed { i, w -> w * analysis.detailMask[i] }.sum() /
                analysis.subjectMask.sum().coerceAtLeast(1e-6f).toFloat().coerceAtLeast(0.0001f)
            if (subjDetail > 0.03f) {
                val noiseFactor = (1f - (sq.noiseConfidence - 4f) / 28f).coerceIn(0.35f, 1f)
                val clarityMag = (0.13f * noiseFactor * structureHeadroom * detailSoftening).coerceIn(0.04f, 0.15f)
                decisions.add(EditDecision("Clarity", "Subject", "Mid", clarityMag, 0.7f, 0.6f, 0.2f))
            }
        }
        if (sq.noiseConfidence < 22f && sq.edgeStrength > 12f && profile == GradingProfile.LANDSCAPE) {
            val noiseFactor = (1f - (sq.noiseConfidence - 4f) / 28f).coerceIn(0.35f, 1f)
            // Landscape: the WHOLE frame is the subject — Detail-mask clarity (foliage,
            // rock, water texture all deserve structure), braver magnitude.
            val clarityMag = (0.24f * noiseFactor * structureHeadroom * detailSoftening).coerceIn(0.06f, 0.26f)
            decisions.add(EditDecision("Clarity", "Detail", "Mid", clarityMag, 0.7f, 0.6f, 0.2f))
        }
        if (sq.noiseConfidence < 22f && sq.edgeStrength > 12f && profile == GradingProfile.GENERAL) {
            val noiseFactor = (1f - (sq.noiseConfidence - 4f) / 28f).coerceIn(0.35f, 1f)
            val clarityMag = (0.20f * noiseFactor * structureHeadroom * detailSoftening).coerceIn(0.05f, 0.22f)
            decisions.add(EditDecision("Clarity", "Subject", "Mid", clarityMag, 0.7f, 0.6f, 0.2f))
        }

        // Micro-detail recovery: Texture — profile-aware.
        if (!alreadyGood && sq.noiseConfidence < 16f && sq.edgeStrength > 18f && sq.textureStrength > 0.03f &&
            profile != GradingProfile.LOW_LIGHT) {
            val noiseFactor = (1f - (sq.noiseConfidence - 4f) / 16f).coerceIn(0.45f, 1f)
            // Portrait: softer cap — skin texture amplification reads as aging.
            val texCap = if (profile == GradingProfile.PORTRAIT) 0.06f else 0.09f
            val textureMag = (0.09f * noiseFactor * detailSoftening).coerceIn(0.03f, texCap)
            decisions.add(EditDecision("Texture", "Texture", "High", textureMag, 0.65f, 0.6f, 0.25f))
        }

        // ---- Material-aware: broadly detected reflective metal regions ----
        if (!alreadyGood && analysis.goldBrassScore > 20f && sq.noiseConfidence < 22f && sq.edgeStrength > 12f &&
            profile != GradingProfile.LOW_LIGHT) {
            val metalMag = (0.12f * structureHeadroom * detailSoftening).coerceIn(0.04f, 0.15f)
            decisions.add(EditDecision("Clarity", "Metal", "Mid", metalMag, 0.7f, 0.6f, 0.2f))
        }

        // ---- Lens corrections: measurement invites local pixel inspection, never blind correction ----
        // Vignette: a measured corner darkening is a lens defect — correct it with a radial
        // gain (renderer), scaled by severity. Never fired on a clean lens.
        if (analysis.lensQuality.vignette > 0.15f) {
            decisions.add(EditDecision("Vignette", "Global", "All",
                (analysis.lensQuality.vignette * 0.7f).coerceIn(0.08f, 0.5f), 0.8f, 0.6f, 0.15f))
        }
        // The global score is a *suspected* lateral CA signature, not proof.
        // The renderer rejects genuine color boundaries and only corrects narrow chroma
        // outliers with consistent colors on both sides of a strong luma edge.
        if (analysis.lensQuality.chromaticAberration > 0.2f) {
            decisions.add(EditDecision("CA", "Global", "High",
                (analysis.lensQuality.chromaticAberration * 0.8f).coerceIn(0.15f, 0.7f), 0.8f, 0.6f, 0.1f))
        }

        // Color life: Vibrance — muted-first, skin-safe; braver on landscape (foliage/sky
        // color life is the point), gentler on portrait (skin tones stay honest).
        if (!alreadyGood && !isIntentionalDark && ca.avgS < 0.30f) {
            val vibMag = when (profile) {
                GradingProfile.LANDSCAPE -> (0.20f * (1f - ca.avgS)).coerceIn(0.10f, 0.20f)
                GradingProfile.PORTRAIT -> (0.10f * (1f - ca.avgS)).coerceIn(0.04f, 0.10f)
                GradingProfile.LOW_LIGHT -> (0.08f * (1f - ca.avgS)).coerceIn(0.03f, 0.08f)
                else -> (0.16f * (1f - ca.avgS)).coerceIn(0.08f, 0.16f)
            }
            decisions.add(EditDecision("Vibrance", "Global", "All", vibMag, 0.65f, 0.5f, 0.2f))
        }

        // Do not automatically reduce background saturation or add split-tone styling.
        // The subject mask is focus/saliency-based, not a verified semantic segmentation;
        // applying these decorative choices without a demonstrated defect repeatedly
        // damaged legitimate colors. The rendering passes remain available to a future
        // separately validated, confidence-gated decision policy.

        // P4 HUE-BAND REFINEMENT — measured hue repair for the sky and foliage bands.
        // "Detection is never permission": the band must be MEASURED (real weight), COHERENT
        // (a scattered hue is a mixed scene, not a band defect), and MATERIALLY OFF the
        // natural anchor (sky 220°, foliage 110°). The rotation is (anchor − mean): cyan sky
        // (205°) gets +° toward 220; yellow canopy (96°) gets +° toward 110. Capped at ±6° —
        // a nudge, never a repaint.
        // INTENT PROTECTION is the band gate itself: an orange sunset sky (hue ~30°) or an
        // autumn gold canopy (~45°) never enters the 190..250 / 70..150 hue census — warm
        // intent simply never votes. No coarse frame-warmth guard: ground warmth doesn't
        // make an overhead BLUE sky's measured drift into intent.
        fun hueDecision(band: String, stats: com.example.engine.HueBandStats, anchorDeg: Float): EditDecision? {
            if (stats.weight <= 0f || stats.coherence < 0.85f) return null
            var delta = stats.meanDeg - anchorDeg
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            if (abs(delta) <= 8f) return null
            val rotation = (-delta).coerceIn(-6f, 6f)   // toward the anchor
            return EditDecision(band, if (band == "SkyHue") "Sky" else "Vegetation", "All",
                rotation, 0.5f, 0.4f, 0.2f)
        }
        // Sky hue: only when the sky band is real.
        if (!alreadyGood && analysis.skyScore > 15f) {
            hueDecision("SkyHue", analysis.skyHueStats, 220f)?.let { decisions.add(it) }
        }
        // Foliage hue: only where vegetation is a real band.
        if (!alreadyGood && analysis.greenScore > 10f) {
            hueDecision("VegHue", analysis.vegHueStats, 110f)?.let { decisions.add(it) }
        }

        // ---- Joint optimization: don't stack a global Exposure lift with a full Shadows lift ----
        val globExp = decisions.find { it.parameter == "Exposure" && it.regionMask == "Global" }
        val globShad = decisions.find { it.parameter == "Shadows" && it.regionMask == "Shadows" }
        if (globExp != null && globExp.magnitude > 0f && globShad != null && globShad.magnitude > 0f) {
            val updatedShad = globShad.copy(magnitude = max(0f, globShad.magnitude - globExp.magnitude * 0.8f))
            decisions.remove(globShad)
            if (updatedShad.magnitude > 0.05f) decisions.add(updatedShad)
        }

        return EditPlan(decisions, profileName = profile.name, profileReason = profileReason)
    }
}
