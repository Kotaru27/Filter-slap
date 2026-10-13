package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Test-side image metrics for photographic regression testing.
 *
 * These are MEASUREMENT TOOLS, not automatic quality judgments.
 * They quantify differences between images for regression detection.
 */
object ImageMetrics {

    // === PIXEL METRICS ===

    data class PixelMetrics(
        val mae: Float,
        val rmse: Float,
        val maxAbsoluteDiff: Int,
        val percentAboveThreshold: Float,
        val totalPixels: Int,
        val changedPixels: Int
    )

    /** Compute pixel-level metrics between two bitmaps of equal dimensions. */
    fun pixelMetrics(before: Bitmap, after: Bitmap, threshold: Int = 3): PixelMetrics {
        val w = before.width.coerceAtMost(after.width)
        val h = before.height.coerceAtMost(after.height)
        val totalPixels = w * h

        var sumAbsDiff = 0L
        var sumSqDiff = 0L
        var maxDiff = 0
        var changedCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val pb = before.getPixel(x, y)
                val pa = after.getPixel(x, y)
                val dr = abs(Color.red(pb) - Color.red(pa))
                val dg = abs(Color.green(pb) - Color.green(pa))
                val db = abs(Color.blue(pb) - Color.blue(pa))
                val maxChannelDiff = maxOf(dr, dg, db)

                sumAbsDiff += maxChannelDiff
                sumSqDiff += maxChannelDiff * maxChannelDiff
                if (maxChannelDiff > maxDiff) maxDiff = maxChannelDiff
                if (maxChannelDiff > threshold) changedCount++
            }
        }

        val mae = if (totalPixels > 0) sumAbsDiff.toFloat() / totalPixels else 0f
        val rmse = if (totalPixels > 0) sqrt(sumSqDiff.toFloat() / totalPixels) else 0f
        val percentAbove = if (totalPixels > 0) changedCount.toFloat() / totalPixels * 100f else 0f

        return PixelMetrics(mae, rmse, maxDiff, percentAbove, totalPixels, changedCount)
    }

    // === TONAL METRICS ===

    data class TonalMetrics(
        val histogram: IntArray,
        val p01: Float,
        val p05: Float,
        val p25: Float,
        val p50: Float,
        val p75: Float,
        val p95: Float,
        val p99: Float,
        val shadowOccupancy: Float,
        val highlightOccupancy: Float,
        val dynamicRange: Float,
        val meanLuma: Float
    )

    /** Compute tonal metrics from a bitmap. */
    fun tonalMetrics(bitmap: Bitmap): TonalMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val totalPixels = w * h
        val histogram = IntArray(256)
        var sumLuma = 0L

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap.getPixel(x, y)
                val luma = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
                histogram[luma.toInt().coerceIn(0, 255)]++
                sumLuma += luma.toLong()
            }
        }

        fun getP(p: Float): Float {
            val target = totalPixels * p
            var sum = 0f
            for (i in 0..255) {
                sum += histogram[i]
                if (sum >= target) return i / 255f
            }
            return 1f
        }

        val p01 = getP(0.01f)
        val p05 = getP(0.05f)
        val p25 = getP(0.25f)
        val p50 = getP(0.50f)
        val p75 = getP(0.75f)
        val p95 = getP(0.95f)
        val p99 = getP(0.99f)

        var shadowCount = 0
        var highlightCount = 0
        for (i in 0..255) {
            if (i < 85) shadowCount += histogram[i]
            else if (i >= 170) highlightCount += histogram[i]
        }

        return TonalMetrics(
            histogram = histogram,
            p01 = p01, p05 = p05, p25 = p25, p50 = p50,
            p75 = p75, p95 = p95, p99 = p99,
            shadowOccupancy = shadowCount.toFloat() / totalPixels,
            highlightOccupancy = highlightCount.toFloat() / totalPixels,
            dynamicRange = p95 - p05,
            meanLuma = sumLuma.toFloat() / totalPixels
        )
    }

    // === COLOR METRICS ===

    data class ColorMetrics(
        val meanR: Float,
        val meanG: Float,
        val meanB: Float,
        val channelSpread: Float,
        val meanSaturation: Float
    )

    /** Compute color metrics from a bitmap. */
    fun colorMetrics(bitmap: Bitmap): ColorMetrics {
        val w = bitmap.width
        val h = bitmap.height
        val totalPixels = w * h
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var sumS = 0f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                sumR += r; sumG += g; sumB += b

                val maxC = maxOf(r, g, b).toFloat()
                val minC = minOf(r, g, b).toFloat()
                val s = if (maxC > 0) (maxC - minC) / maxC else 0f
                sumS += s
            }
        }

        val meanR = sumR.toFloat() / totalPixels
        val meanG = sumG.toFloat() / totalPixels
        val meanB = sumB.toFloat() / totalPixels
        val channelSpread = (maxOf(meanR, meanG, meanB) - minOf(meanR, meanG, meanB))

        return ColorMetrics(meanR, meanG, meanB, channelSpread, sumS / totalPixels)
    }

    // === REGIONAL METRICS ===

    data class RegionalMetrics(
        val skyMeanLuma: Float,
        val skyPixelCount: Int,
        val skinMeanLuma: Float,
        val skinPixelCount: Int,
        val vegetationMeanLuma: Float,
        val vegetationPixelCount: Int,
        val brightMeanLuma: Float,
        val brightPixelCount: Int,
        val shadowMeanLuma: Float,
        val shadowPixelCount: Int
    )

    /** Compute regional metrics using analysis masks. */
    fun regionalMetrics(bitmap: Bitmap, analysis: AnalysisResult): RegionalMetrics {
        val w = bitmap.width.coerceAtMost(analysis.analysisWidth)
        val h = bitmap.height.coerceAtMost(analysis.analysisHeight)
        val aw = analysis.analysisWidth

        var skySum = 0f; var skyCount = 0
        var skinSum = 0f; var skinCount = 0
        var vegSum = 0f; var vegCount = 0
        var brightSum = 0f; var brightCount = 0
        var shadowSum = 0f; var shadowCount = 0

        for (y in 0 until h) {
            for (x in 0 until w) {
                val aIdx = y * aw + x
                if (aIdx >= analysis.skyMask.size) continue

                val c = bitmap.getPixel(x, y)
                val luma = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f

                if (analysis.skyMask[aIdx] > 0.3f) { skySum += luma; skyCount++ }
                if (analysis.skinMask[aIdx] > 0.3f) { skinSum += luma; skinCount++ }
                if (analysis.vegMask[aIdx] > 0.3f) { vegSum += luma; vegCount++ }
                if (analysis.brightMask[aIdx] > 0.3f) { brightSum += luma; brightCount++ }
                if (analysis.shadowMask[aIdx] > 0.3f) { shadowSum += luma; shadowCount++ }
            }
        }

        return RegionalMetrics(
            skyMeanLuma = if (skyCount > 0) skySum / skyCount else 0f,
            skyPixelCount = skyCount,
            skinMeanLuma = if (skinCount > 0) skinSum / skinCount else 0f,
            skinPixelCount = skinCount,
            vegetationMeanLuma = if (vegCount > 0) vegSum / vegCount else 0f,
            vegetationPixelCount = vegCount,
            brightMeanLuma = if (brightCount > 0) brightSum / brightCount else 0f,
            brightPixelCount = brightCount,
            shadowMeanLuma = if (shadowCount > 0) shadowSum / shadowCount else 0f,
            shadowPixelCount = shadowCount
        )
    }

    // === SELECTED PIXEL SAMPLE ===

    /**
     * Deterministic pixel sample at fixed grid positions.
     * Returns list of (x, y, argb) tuples.
     */
    fun pixelSample(bitmap: Bitmap, gridRows: Int = 4, gridCols: Int = 4): List<Triple<Int, Int, Int>> {
        val samples = mutableListOf<Triple<Int, Int, Int>>()
        val w = bitmap.width
        val h = bitmap.height
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val x = (col * (w - 1)) / (gridCols - 1).coerceAtLeast(1)
                val y = (row * (h - 1)) / (gridRows - 1).coerceAtLeast(1)
                samples.add(Triple(x, y, bitmap.getPixel(x, y)))
            }
        }
        return samples
    }

    // === DETERMINISTIC HASH ===

    /** Simple deterministic hash of bitmap pixel data for fast comparison. */
    fun bitmapHash(bitmap: Bitmap, sampleStep: Int = 1): Long {
        var hash = 0L
        val w = bitmap.width
        val h = bitmap.height
        for (y in 0 until h step sampleStep) {
            for (x in 0 until w step sampleStep) {
                hash = hash * 31 + bitmap.getPixel(x, y)
            }
        }
        return hash
    }
}
