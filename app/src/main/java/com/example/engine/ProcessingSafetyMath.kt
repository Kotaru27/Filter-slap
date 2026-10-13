package com.example.engine

import kotlin.math.abs
import kotlin.math.min

/** Pure, independently testable invariants shared by preview gating and final rendering. */
internal object ProcessingSafetyMath {
    /** A detail delta measured in RGB luma must NOT replace HSL lightness. */
    fun detailLightness(hslL: Float, originalMid: Float, editedMid: Float,
                        originalHigh: Float, editedHigh: Float): Float =
        (hslL + (editedMid - originalMid) + (editedHigh - originalHigh)).coerceIn(0f, 1f)

    /** Transfer only an RGB-luma noise-reduction delta to HSL lightness. */
    fun denoisedLightness(hslL: Float, originalRgbLuma: Float, filteredRgbLuma: Float): Float =
        (hslL + (filteredRgbLuma - originalRgbLuma) / 255f).coerceIn(0f, 1f)

    /** Difference of hues in degrees, normalized to [0,1] over 180 degrees. */
    fun hueDrift(hueA: Float, hueB: Float): Float {
        val distance = abs(hueA - hueB) % 360f
        return min(distance, 360f - distance) / 180f
    }


    /**
     * Bounded, continuous noise suppression shared by frequency and spatial paths.
     * Stronger measured noise must never receive weaker suppression. Zero noise must
     * not turn an already-clean frame into a blur. The local map modulates the
     * strength, rather than determining it from a named scene or test photograph.
     */
    fun noiseSuppression(noiseConfidence: Float, localNoise: Float): Float {
        val global = ((noiseConfidence - 8f) / 24f).coerceIn(0f, 0.75f)
        return global * (0.35f + 0.65f * localNoise.coerceIn(0f, 1f))
    }

    /** Direction-aware, neutral-biased tint adjustment: never correct toward the
     * WRONG cast, and protect saturated material colors from arbitrary shifts. */
    fun correctedGreen(r: Int, g: Int, b: Int, signedTint: Float): Int {
        if (signedTint == 0f) return g
        val neutral = (r + b) * 0.5f
        val mismatch = g - neutral
        if (mismatch * signedTint >= 0f) return g // already on the requested side
        val span = maxOf(r, g, b) - minOf(r, g, b)
        val chromaProtect = (1f - span / 100f).coerceIn(0f, 1f)
        val shift = min(abs(mismatch), abs(signedTint) * 255f) * chromaProtect
        return (g + if (signedTint > 0) shift else -shift).toInt().coerceIn(0, 255)
    }

    /** More harm can never imply less throttling. */
    fun throttle(harm: Float): Float = if (harm > 0.1f) harm.coerceIn(0f, 1f) else 0f

    /** Each guard iteration reduces the CURRENT magnitude. No cumulative ledger can
     * block subsequent reductions; repeated calls converge monotonically to zero. */
    fun attenuate(magnitude: Float, harm: Float): Float = magnitude * (1f - throttle(harm))
}
