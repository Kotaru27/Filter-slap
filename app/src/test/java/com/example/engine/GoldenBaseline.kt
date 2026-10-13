package com.example.engine

import android.graphics.Bitmap

/**
 * Deterministic representation of pipeline output for golden baseline.
 *
 * Stores compact, reproducible data rather than full image bitmaps.
 * This balances regression detection against repository size.
 */
data class PipelineSnapshot(
    val fixtureName: String,
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,

    // Analysis summary
    val analysisPixelCount: Int,
    val isDarkImage: Boolean,
    val isBrightImage: Boolean,
    val isLowContrast: Boolean,
    val isWarm: Boolean,
    val isCool: Boolean,
    val avgLuma: Float,
    val avgR: Float,
    val avgG: Float,
    val avgB: Float,

    // Tonal structure
    val p01: Float, val p05: Float, val p25: Float, val p50: Float,
    val p75: Float, val p95: Float, val p99: Float,
    val iqr: Float, val occupancy: Float, val asymmetry: Float,
    val shadowDensity: Float, val highlightDensity: Float,

    // Signal quality
    val noiseConfidence: Float,
    val blurConfidence: Float,
    val edgeStrength: Float,
    val highlightClipping: Float,
    val shadowCrushing: Float,

    // Color
    val avgSaturation: Float,
    val channelClipping: Float,
    val colorTemperature: String,

    // Scores
    val skyScore: Float,
    val skinToneScore: Float,
    val greenScore: Float,

    // Report
    val sceneCategory: String,
    val lighting: String,
    val dynamicRange: String,
    val histogramBias: String,

    // EditPlan
    val decisionCount: Int,
    val decisionTypes: List<String>,
    val decisionMagnitudes: List<Float>,
    val decisionMasks: List<String>,

    // Output metrics
    val outputMeanLuma: Float,
    val outputShadowOccupancy: Float,
    val outputHighlightOccupancy: Float,
    val outputMeanR: Float,
    val outputMeanG: Float,
    val outputMeanB: Float,

    // Selected pixel samples (4x4 grid)
    val outputPixelSamples: List<Triple<Int, Int, Int>>,

    // Deterministic hash
    val outputHash: Long,

    // Performance
    val analyzerDurationMs: Long,
    val cdeDurationMs: Long,
    val processorDurationMs: Long,
    val totalDurationMs: Long
)

/**
 * Generate a PipelineSnapshot from the current implementation.
 * This IS the baseline â€” it captures current behavior exactly.
 */
object GoldenBaseline {

    fun captureSnapshot(
        fixtureName: String,
        bitmap: Bitmap
    ): PipelineSnapshot {
        val inputW = bitmap.width
        val inputH = bitmap.height

        // 1. Analyze
        val analyzeStart = System.nanoTime()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val analyzeEnd = System.nanoTime()

        // 2. CDE
        val cdeStart = System.nanoTime()
        val plan = ContextualDecisionEngine.evaluate(analysis)
        val cdeEnd = System.nanoTime()

        // 3. Process
        val processStart = System.nanoTime()
        val (output, stats, _, _) = ImageProcessor.process(bitmap, analysis)
        val processEnd = System.nanoTime()

        // 4. Compute output metrics
        val outputTonal = ImageMetrics.tonalMetrics(output)
        val outputColor = ImageMetrics.colorMetrics(output)
        val outputSamples = ImageMetrics.pixelSample(output)
        val outputHash = ImageMetrics.bitmapHash(output)

        return PipelineSnapshot(
            fixtureName = fixtureName,
            inputWidth = inputW, inputHeight = inputH,
            outputWidth = output.width, outputHeight = output.height,

            analysisPixelCount = analysis.pixelCount,
            isDarkImage = analysis.isDarkImage,
            isBrightImage = analysis.isBrightImage,
            isLowContrast = analysis.isLowContrast,
            isWarm = analysis.isWarm,
            isCool = analysis.isCool,
            avgLuma = analysis.avgLuma,
            avgR = analysis.avgR, avgG = analysis.avgG, avgB = analysis.avgB,

            p01 = analysis.tonalStructure.p01, p05 = analysis.tonalStructure.p05,
            p25 = analysis.tonalStructure.p25, p50 = analysis.tonalStructure.p50,
            p75 = analysis.tonalStructure.p75, p95 = analysis.tonalStructure.p95,
            p99 = analysis.tonalStructure.p99,
            iqr = analysis.tonalStructure.iqr,
            occupancy = analysis.tonalStructure.occupancy,
            asymmetry = analysis.tonalStructure.asymmetry,
            shadowDensity = analysis.tonalStructure.shadowDensity,
            highlightDensity = analysis.tonalStructure.highlightDensity,

            noiseConfidence = analysis.signalQuality.noiseConfidence,
            blurConfidence = analysis.signalQuality.blurConfidence,
            edgeStrength = analysis.signalQuality.edgeStrength,
            highlightClipping = analysis.signalQuality.highlightClipping,
            shadowCrushing = analysis.signalQuality.shadowCrushing,

            avgSaturation = analysis.colorAnalysis.avgS,
            channelClipping = analysis.colorAnalysis.channelClipping,
            colorTemperature = analysis.report.colorTemperature,

            skyScore = analysis.skyScore,
            skinToneScore = analysis.skinToneScore,
            greenScore = analysis.greenScore,

            sceneCategory = analysis.report.sceneCategory,
            lighting = analysis.report.lighting,
            dynamicRange = analysis.report.dynamicRange,
            histogramBias = analysis.report.histogramBias,

            decisionCount = plan.decisions.size,
            decisionTypes = plan.decisions.map { it.parameter },
            decisionMagnitudes = plan.decisions.map { it.magnitude },
            decisionMasks = plan.decisions.map { it.regionMask },

            outputMeanLuma = outputTonal.meanLuma,
            outputShadowOccupancy = outputTonal.shadowOccupancy,
            outputHighlightOccupancy = outputTonal.highlightOccupancy,
            outputMeanR = outputColor.meanR,
            outputMeanG = outputColor.meanG,
            outputMeanB = outputColor.meanB,

            outputPixelSamples = outputSamples,
            outputHash = outputHash,

            analyzerDurationMs = (analyzeEnd - analyzeStart) / 1_000_000,
            cdeDurationMs = (cdeEnd - cdeStart) / 1_000_000,
            processorDurationMs = (processEnd - processStart) / 1_000_000,
            totalDurationMs = (processEnd - analyzeStart) / 1_000_000
        )
    }

    /**
     * Generate baseline snapshots for all fixtures.
     * This captures the CURRENT behavior as the regression baseline.
     */
    fun generateFullBaseline(): List<PipelineSnapshot> {
        return PhotoRegressionFixtures.allFixtures().map { (name, factory) ->
            val bitmap = factory()
            try {
                captureSnapshot(name, bitmap)
            } finally {
                bitmap.recycle()
            }
        }
    }
}
