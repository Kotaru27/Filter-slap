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
    val highlightWarmth: Float = 0f
) {
    val isNeutral: Boolean get() = contrast == 0f && shadowDepth == 0f &&
        highlightShoulder == 0f && vibrance == 0f &&
        shadowWarmth == 0f && highlightWarmth == 0f

    fun scaled(factor: Float) = CreativeGrade(
        contrast * factor, shadowDepth * factor, highlightShoulder * factor,
        vibrance * factor, shadowWarmth * factor, highlightWarmth * factor
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
        val flat = ((.75f - whole.range) / .42f).coerceIn(0f, 1f)
        val bright = ((whole.p90 - .73f) / .23f).coerceIn(0f, 1f)
        val vivid = ((.30f - color.meanChroma) / .25f).coerceIn(0f, 1f)
        // Avoid introducing conspicuous casts or boosting already vivid colors.
        // Warm/cool values are small photographic split-toning biases, not white balance.
        val warmth = (-color.warmBias * .06f).coerceIn(-.012f, .012f)
        val darkFactor = if (intentionalDark) .40f else 1f
        val base = CreativeGrade(
            contrast = (.13f + .15f * flat) * darkFactor,
            shadowDepth = (.012f + .028f * flat) * darkFactor,
            highlightShoulder = .08f + .22f * bright,
            vibrance = (.025f + .095f * vivid) * darkFactor,
            shadowWarmth = -.007f + warmth * .40f,
            highlightWarmth = .009f + warmth * .40f
        )
        return listOf(
            CandidateSettings("Creative natural grade", creativeGrade = base.scaled(.78f)),
            CandidateSettings("Creative dimensional grade", creativeGrade = base),
            CandidateSettings("Creative highlight grade",
                creativeGrade = base.copy(contrast = base.contrast * .85f,
                    shadowDepth = base.shadowDepth * .75f,
                    highlightShoulder = min(.40f, base.highlightShoulder * 1.55f),
                    vibrance = base.vibrance * .70f))
        )
    }

    /**
     * Gain is allowed only for source-evidenced improvements in tonal separation
     * and bright-end control. Changing pixels for its own sake earns no points.
     * Artifact, hue and clipping penalties are separately applied by CandidatePlanner.
     */
    fun benefit(before: FrameTones, after: FrameTones, intentionalDark: Boolean): Float {
        val source = before.whole
        val result = after.whole
        val flatNeed = ((.76f - source.range) / .35f).coerceIn(0f, 1f)
        val highlightNeed = ((source.p90 - .75f) / .18f).coerceIn(0f, 1f)
        val usefulRange = (result.range - source.range).coerceIn(0f, .13f)
        val usefulShoulder = (source.p90 - result.p90).coerceIn(0f, .09f)
        val rangeReward = (if (intentionalDark) .45f else 1f) * 115f * flatNeed * usefulRange
        val shoulderReward = 105f * highlightNeed * usefulShoulder
        val midtoneDrift = abs(result.median - source.median)
        val lostRange = (source.range - result.range).coerceAtLeast(0f)
        // Avoid calling flattened, darkened or heavily shifted photos more creative.
        return rangeReward + shoulderReward - midtoneDrift * 22f -
            lostRange * 80f * (1f - highlightNeed * .6f)
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
            smooth(.47f, .96f, y) * (1f - y)
        val target = (y + contrast + toe + shoulder).coerceIn(.002f, .995f)
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
