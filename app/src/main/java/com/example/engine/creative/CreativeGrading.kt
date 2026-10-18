package com.example.engine.creative

import com.example.engine.v7.EnhancementMath
import com.example.engine.v8.CandidateSettings
import com.example.engine.v8.FrameTones
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * No AI, model, lookup table, image labels, coordinates or image-specific rules.
 * All parameters are continuous functions of robust statistics from this photo.
 *
 * Unlike a correction-only plan, a grade intentionally shapes tonal separation,
 * the highlight shoulder and color. Validation remains in the existing renderer.
 */
data class CreativeGrade(
    val contrast: Float = 0f,
    val shadowDepth: Float = 0f,
    val highlightShoulder: Float = 0f,
    val vibrance: Float = 0f,
    val shadowWarmth: Float = 0f,
    val highlightWarmth: Float = 0f,
    val midtoneLift: Float = 0f
) {
    val isNeutral: Boolean get() = contrast == 0f && shadowDepth == 0f &&
        highlightShoulder == 0f && vibrance == 0f &&
        shadowWarmth == 0f && highlightWarmth == 0f && midtoneLift == 0f

    fun scaled(factor: Float) = CreativeGrade(
        contrast * factor, shadowDepth * factor, highlightShoulder * factor,
        vibrance * factor, shadowWarmth * factor, highlightWarmth * factor,
        midtoneLift * factor
    )
}

/** Scene-derived, full-frame photographic tone and color decisions. */
object CreativeGrading {
    private fun smooth(a: Float, b: Float, x: Float): Float {
        val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    data class ColorEvidence(val meanChroma: Float, val meanLuma: Float, val warmBias: Float)

    fun measureColor(pixels: IntArray): ColorEvidence {
        if (pixels.isEmpty()) return ColorEvidence(0f, .5f, 0f)
        var chroma = 0.0
        var luma = 0.0
        var warmth = 0.0
        var count = 0
        // Evenly sample throughout the entire frame; no per-photo pixel coordinates.
        val stride = max(1, pixels.size / 10000)
        for (i in pixels.indices step stride) {
            val p = pixels[i]
            val r = (p ushr 16 and 255) / 255f
            val g = (p ushr 8 and 255) / 255f
            val b = (p and 255) / 255f
            val y = EnhancementMath.luma(r, g, b)
            chroma += maxOf(r, g, b) - minOf(r, g, b)
            luma += y
            if(y in .12f.. .85f) warmth += (r - b).toDouble()
            count++
        }
        return ColorEvidence((chroma / count).toFloat(), (luma / count).toFloat(),
            (warmth / count).toFloat())
    }


    fun propose(before: FrameTones, pixels: IntArray, intentionalDark: Boolean): List<CandidateSettings> {
        val whole = before.whole
        val color = measureColor(pixels)
        val compressed = ((.77f - whole.range) / .48f).coerceIn(0f, 1f)
        val bright = ((whole.p90 - .72f) / .24f).coerceIn(0f, 1f)
        val muted = ((.31f - color.meanChroma) / .25f).coerceIn(0f, 1f)
        val dim = if (intentionalDark) 0f else ((.46f - whole.median) / .28f).coerceIn(0f, 1f)
        val darkFactor = if (intentionalDark) .38f else 1f
        // Editorial-level tonal decisions from measured distributions, not
        // global fixed presets, image categories or a memorized test photograph.
        val warmth = (-color.warmBias * .04f).coerceIn(-.010f, .010f)
        val base = CreativeGrade(
            contrast = (.29f + .21f * compressed) * darkFactor,
            shadowDepth = (.030f + .042f * compressed) * darkFactor,
            highlightShoulder = .12f + .23f * bright,
            vibrance = (.055f + .15f * muted) * darkFactor,
            shadowWarmth = -.008f + warmth * .45f,
            highlightWarmth = .010f + warmth * .45f,
            midtoneLift = .055f * dim
        )
        val restrained = base.scaled(.72f)
        val dimensional = base
        val controlled = base.copy(
            contrast = base.contrast * .84f,
            shadowDepth = base.shadowDepth * .75f,
            highlightShoulder = min(.43f, base.highlightShoulder * 1.27f),
            vibrance = base.vibrance * .78f
        )
        return listOf(
            CandidateSettings("Automatic natural grade", creativeGrade = restrained),
            CandidateSettings("Automatic dimensional grade", creativeGrade = dimensional),
            CandidateSettings("Automatic highlight-balanced grade", creativeGrade = controlled)
        )
    }

    /**
     * Compare the actual rendered distribution, not requested slider strengths.
     * Rewards tonal depth where source shadows allow it, tonal separation in
     * compressed scenes, and recovery of bright-heavy or overly dim midtones.
     * The original is still eligible: a strong source image may need no grade.
     */
    fun benefit(before: FrameTones, after: FrameTones, intentionalDark: Boolean): Float {
        val b = before.whole; val a = after.whole
        val flatNeed = ((.77f - b.range) / .38f).coerceIn(0f, 1f)
        val brightNeed = ((b.p90 - .76f) / .18f).coerceIn(0f, 1f)
        val dimNeed = if(intentionalDark) 0f else ((.47f - b.median)/.23f).coerceIn(0f,1f)
        val shadowHeadroom = if(intentionalDark) 0f else
            ((b.p10 - .045f) / .16f).coerceIn(0f, 1f)
        val usefulRange = (a.range - b.range).coerceIn(0f, .16f)
        val shoulder = (b.p90 - a.p90).coerceIn(0f, .095f)
        val shadowDepth = (b.p10 - a.p10).coerceIn(0f, .075f)
        val midtoneRecovery = (a.median - b.median).coerceIn(0f, .10f)
        val tonal = 135f * flatNeed * usefulRange +
            115f * brightNeed * shoulder +
            60f * shadowHeadroom * shadowDepth +
            95f * dimNeed * midtoneRecovery
        // A deep grade may shift the median slightly, but arbitrary darkening
        // or flattening is never a photographic improvement.
        val driftLimit = .022f + .05f * dimNeed
        val excessiveDrift = (abs(a.median - b.median) - driftLimit).coerceAtLeast(0f)
        val flatten = (b.range - a.range).coerceAtLeast(0f)
        val shadowCrush = if (b.p10 > .08f && a.p10 < .015f) .12f else 0f
        return tonal - 145f * excessiveDrift - 95f * flatten - 60f * shadowCrush
    }

    /** Color saturation has photographic value only when the source is muted. */
    fun colorBenefit(source: IntArray, edited: IntArray): Float {
        require(source.size == edited.size)
        if(source.isEmpty())return 0f
        val original=measureColor(source)
        val after=measureColor(edited)
        val muted=((.31f-original.meanChroma)/.22f).coerceIn(0f,1f)
        val gained=(after.meanChroma-original.meanChroma).coerceIn(0f,.12f)
        val loss=(original.meanChroma-after.meanChroma).coerceAtLeast(0f)
        val overlyVivid=(after.meanChroma-.46f).coerceAtLeast(0f)
        return 110f * muted * gained - 90f * loss - 75f * overlyVivid
    }

    /**
     * Applies a smooth tone curve to luma; scales RGB by a common factor to
     * preserve hue, then adds bounded luminance-dependent chroma and split-tone.
     * Called after legacy corrections, before their existing gamut protection.
     */
    fun apply(rgb: FloatArray, grade: CreativeGrade) {
        if (grade.isNeutral) return
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val y = EnhancementMath.luma(r, g, b).coerceIn(0f, 1f)
        if (y < .003f || y > .995f) return
        val u = y - .5f
        val contrast = grade.contrast * 4f * u * (.25f - u * u)
        val toe = -grade.shadowDepth * y *
            (1f - smooth(.25f, .66f, y))
        val shoulder = -grade.highlightShoulder *
            smooth(.60f, .90f, y) * (1f - y) * 2.4f
        // Midtone recovery is image-specific and falls to zero at pure black/white.
        val midtone = grade.midtoneLift * 4f * y * (1f-y)
        val target = (y + contrast + toe + shoulder + midtone).coerceIn(.002f, .995f)
        val gain = target / y
        var rr = r * gain
        var gg = g * gain
        var bb = b * gain
        val chroma = (maxOf(rr, gg, bb) - minOf(rr, gg, bb)).coerceIn(0f, 1f)
        val colorGain = 1f + grade.vibrance * (1f - chroma) * (1f - chroma)
        rr = target + (rr - target) * colorGain
        gg = target + (gg - target) * colorGain
        bb = target + (bb - target) * colorGain
        val shadowWeight = 1f - smooth(.12f, .62f, target)
        val highlightWeight = smooth(.42f, .92f, target)
        val split = grade.shadowWarmth * shadowWeight +
            grade.highlightWarmth * highlightWeight
        // Preserve luminance to first order while applying restrained warmth.
        rr += split * .70f
        gg -= split * .15f
        bb -= split * 1.0f
        rgb[0] = rr
        rgb[1] = gg
        rgb[2] = bb
    }
}
