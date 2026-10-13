package com.example.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Conservative lateral-color correction. The global analyzer's edge-displacement score is
 * ONLY a request to inspect pixels, never permission to desaturate every strong edge.
 *
 * A correction needs a narrow, anomalous chroma band BETWEEN two significantly different
 * luminances whose colors agree on both sides. A real colored-object/colored-background
 * boundary therefore abstains. There is no reliably recoverable correction when the two
 * sides provide conflicting color evidence. No image labels or scene-specific RGB targets.
 *
 * Uses five source rows, not a full-frame copy, even for full-resolution exports.
 */
internal object ChromaticFringeCorrection {
    private fun red(c: Int) = (c ushr 16) and 255
    private fun green(c: Int) = (c ushr 8) and 255
    private fun blue(c: Int) = c and 255
    private fun lum(c: Int) = 0.299f * red(c) + 0.587f * green(c) + 0.114f * blue(c)
    private fun chroma(c: Int, channel: Int): Float {
        val l = lum(c)
        val v = when (channel) { 0 -> red(c); 1 -> green(c); else -> blue(c) }
        return (v - l) / max(48f, l)
    }

    private data class Candidate(val targetR: Float, val targetG: Float, val targetB: Float, val evidence: Float)

    private fun candidate(center: Int, a: Int, b: Int): Candidate? {
        val lo = lum(a); val hi = lum(b); val at = lum(center)
        val span = abs(hi - lo)
        if (span < 55f || at < min(lo, hi) - 12f || at > max(lo, hi) + 12f) return null
        val ca = FloatArray(3) { chroma(a, it) }
        val cb = FloatArray(3) { chroma(b, it) }
        val cc = FloatArray(3) { chroma(center, it) }
        val disagreement = (0..2).maxOf { abs(ca[it] - cb[it]) }
        if (disagreement > 0.12f) return null // two genuinely different colors: abstain
        val blend = ((at - lo) / (hi - lo)).coerceIn(0f, 1f)
        val expected = FloatArray(3) { ca[it] + (cb[it] - ca[it]) * blend }
        val outlier = (0..2).maxOf { abs(cc[it] - expected[it]) }
        if (outlier < 0.22f) return null
        val correctionWeight = ((outlier - 0.16f) / 0.45f).coerceIn(0f, 1f)
        return Candidate(
            (at + expected[0] * max(48f, at)).coerceIn(0f, 255f),
            (at + expected[1] * max(48f, at)).coerceIn(0f, 255f),
            (at + expected[2] * max(48f, at)).coerceIn(0f, 255f),
            correctionWeight * (span / 255f).coerceIn(0f, 1f)
        )
    }

    /** Number of changed pixels, for truthful 'applied corrections' reporting. */
    fun correctInPlace(pixels: IntArray, width: Int, height: Int, magnitude: Float): Int {
        if (magnitude <= 0f || width < 5 || height < 5) return 0
        require(pixels.size >= width.toLong() * height) { "Frame size exceeds pixel buffer" }
        val rows = Array(5) { offset ->
            IntArray(width).also { System.arraycopy(pixels, (offset - 2).coerceIn(0, height - 1) * width, it, 0, width) }
        }
        var changed = 0
        for (y in 0 until height) {
            for (x in 2 until width - 2) {
                val center = rows[2][x]
                if ((center ushr 24) == 0) continue
                val horizontal = candidate(center, rows[2][x - 2], rows[2][x + 2])
                val vertical = if (y >= 2 && y + 2 < height)
                    candidate(center, rows[0][x], rows[4][x]) else null
                val choice = when {
                    horizontal == null -> vertical
                    vertical == null -> horizontal
                    horizontal.evidence >= vertical.evidence -> horizontal
                    else -> vertical
                } ?: continue
                val strength = (magnitude * choice.evidence).coerceIn(0f, 0.55f)
                if (strength < 0.015f) continue
                fun mix(c: Int, target: Float) = (c + (target - c) * strength).toInt().coerceIn(0, 255)
                val nr = mix(red(center), choice.targetR)
                val ng = mix(green(center), choice.targetG)
                val nb = mix(blue(center), choice.targetB)
                val corrected = (center and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
                if (corrected != center) { pixels[y * width + x] = corrected; changed++ }
            }
            if (y + 1 < height) {
                val oldest = rows[0]
                for (i in 0..3) rows[i] = rows[i + 1]
                rows[4] = oldest
                System.arraycopy(pixels, min(height - 1, y + 3) * width, oldest, 0, width)
            }
        }
        return changed
    }
}
