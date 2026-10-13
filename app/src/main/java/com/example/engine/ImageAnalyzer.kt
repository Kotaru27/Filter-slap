package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.max
import kotlin.math.min

data class CropRecommendation(val crop: CropFrame, val confidence: Int, val reason: String)

data class AnalysisReport(
    val sceneCategory: String,
    val photographyGenre: String,
    val primarySubject: String,
    val lighting: String,
    val composition: String,
    val dynamicRange: String,
    val histogramBias: String,
    val colorTemperature: String,
    val suggestedEditingStyle: String,
    val toneAdjustments: String,
    val colorAdjustments: String,
    val confidenceScore: Int,
    val perspectiveCorrection: String? = null
)

data class TonalStructure(
    val p01: Float, val p05: Float, val p25: Float, val p50: Float, val p75: Float, val p95: Float, val p99: Float,
    val shadowDensity: Float, val midtoneDensity: Float, val highlightDensity: Float,
    val iqr: Float, val occupancy: Float, val asymmetry: Float,
    val blackPoint: Float, val whitePoint: Float
)

data class SignalQuality(
    val noiseConfidence: Float,
    val blurConfidence: Float,
    val edgeStrength: Float,
    val textureStrength: Float,
    val snr: Float,
    val highlightClipping: Float,
    val shadowCrushing: Float
)

data class ColorAnalysis(
    val avgS: Float,
    val p95S: Float,          // 95th percentile of saturation: the vivid-pixel ceiling
    val hueStability: Float,   // 1 - circular hue variance of saturated pixels (1 = coherent palette)
    val chromaNoise: Float,    // mean local saturation variance in flat regions (color noise)
    val neutralEvidence: Float, // fraction of near-gray pixels usable as white-balance anchors
    val channelClipping: Float,
    // Dispersion of normalized channel-opponent signatures across the whole frame.
    // Low values mean one apparent chromaticity dominates: a colored object and
    // a neutral subject under tinted light are observationally indistinguishable.
    // NaN is used for synthetic callers that do not provide image evidence.
    val chromaDispersion: Float = Float.NaN
)

// Measured white-balance anchors: near-neutral pixels (low saturation, tight channel spread)
// that should be equal R=G=B if the sensor's white point were correct. Their channel means
// define the gray-world correction; their coverage defines how much we can trust it.
data class NeutralAnchors(
    val count: Int,            // number of anchor pixels found
    val coverage: Float,       // anchors / pixelCount
    val avgR: Float,           // mean channel values across anchor pixels (0..255)
    val avgG: Float,
    val avgB: Float,
    val spread: Float,         // mean |channel - luma| across anchors: 0 = perfectly gray anchors
    // TINT AXIS (green-magenta): how far the anchors' G sits above/below the R/B midpoint.
    // > 0 = green cast (G too high), < 0 = magenta cast (G too low). Normalized 0..±1.
    val tintNorm: Float = 0f,
    // MIXED-LIGHTING EVIDENCE: max channel-mean disagreement between the left/right and
    // top/bottom anchor halves (normalized 0..1). A frame under one light source has agreeing
    // halves; tungsten window + blue sky produce halves that disagree — a global WB "fix"
    // would damage one of the lights, so the CDE uses this to stay its hand.
    val halfDisagreement: Float = 0f
)

// Lens defect measurements (0..1 severity unless noted). Vignette = corner darkening of the
// frame relative to its center; chromaticAberration = R/B gradient divergence at strong edges
// (lateral color fringing). centerLuma = the measured smooth-center luma (0..255) the
// correction restores corners TOWARD — never beyond it. Both are classic deterministic
// optical forensics.
data class LensQuality(
    val vignette: Float,
    val chromaticAberration: Float,
    val centerLuma: Float = 0f
)

// Region-relative deficiency metrics (0..1, higher = more deficient). Computed so that the
// decision engine can tell whether a detected region actually has a photographic defect rather
// than simply treating "detection" as permission to edit.
data class RegionDeficiency(
    val skyBlown: Float,        // fraction of sky-region pixels blown (luma > 240)
    val skyDull: Float,         // 1 - avg saturation in sky region (1 = fully grey sky)
    val skyLocalContrast: Float,// IQR of luma within sky region (low = milky sky)
    val vegDrab: Float,         // 1 - avg saturation in vegetation region (1 = fully desaturated)
    val subjectDark: Float,     // how under-exposed the salient (non-sky) region is
    val subjectBlown: Float,    // how over-exposed the salient (non-sky) region is
    val shadowDetailLoss: Float // fraction of shadow-region pixels crushed with no recoverable detail
)


// P4: measured hue-band statistics for a color region (circular stats, mask-weighted).
// meanDeg is the circular mean hue angle (0..360); coherence is 1 - circular variance
// (1 = one coherent hue, ~0 = scattered); weight is the summed mask energy that voted —
// a band with no significant weight is not measured, not "at 0°".
data class HueBandStats(
    val meanDeg: Float,
    val coherence: Float,
    val weight: Float
)

data class AnalysisResult(
    val avgLuma: Float,
    val contrastRatio: Float,
    val isLowContrast: Boolean,
    val isDarkImage: Boolean,
    val isBrightImage: Boolean,
    val isWarm: Boolean,
    val isCool: Boolean,
    val sharpnessScore: Float,
    val noiseScore: Float,
    val avgR: Float,
    val avgG: Float,
    val avgB: Float,
    val minLuma: Int,
    val maxLuma: Int,
    val minR: Int,
    val maxR: Int,
    val minG: Int,
    val maxG: Int,
    val minB: Int,
    val maxB: Int,
    
    // Feature Maps (Continuous 0.0 - 1.0)
    val analysisWidth: Int,
    val analysisHeight: Int,
    val skyMask: FloatArray,
    val vegMask: FloatArray,
    val skinMask: FloatArray,
    val metalMask: FloatArray,
    val stoneMask: FloatArray,
    val detailMask: FloatArray,
    val blurMask: FloatArray,
    val noiseMask: FloatArray,

    // Per-pixel TEXTURE channel (0..1): genuine fine micro-structure (stone grain, fabric weave,
    // foliage) that is spread across many directions — NOT a single directional outline and NOT
    // isolated sensor noise. This is what lets the engine add "texture" without merely
    // strengthening edges/outlines. Computed as local micro-energy x isotropy x (1 - noise).
    val textureMask: FloatArray = FloatArray(0),

    val brightMask: FloatArray,
    val shadowMask: FloatArray,
    
    // Focus-saliency × center-prior subject mask (0..1): high on the in-focus, detail-rich
    // central subject, low on blurred/soft background. bgMask is its complement. These are the
    // channels that let the engine grade the subject differently from the background.
    val subjectMask: FloatArray = FloatArray(0),
    val bgMask: FloatArray = FloatArray(0),
    
    // Scores and Features
    val skinToneScore: Float,
    val stoneScore: Float,
    val greenScore: Float,
    val goldBrassScore: Float,
    val skyScore: Float,

    // P4: hue-band evidence for the sky and vegetation regions (circular hue stats of
    // confirmed mask members). Defaults = "not measured" (zero weight).
    val skyHueStats: HueBandStats = HueBandStats(0f, 0f, 0f),
    val vegHueStats: HueBandStats = HueBandStats(0f, 0f, 0f),
    
    val report: AnalysisReport,
    val recommendedCrops: List<CropRecommendation>,
    
    val lumaHistogram: IntArray = IntArray(256),
    val rHistogram: IntArray = IntArray(256),
    val gHistogram: IntArray = IntArray(256),
    val bHistogram: IntArray = IntArray(256),
    val pixelCount: Int = 0,
    val detailWeightedLumaHistogram: FloatArray = FloatArray(256),
    val detailTotalWeight: Float = 0f,
    
    val tonalStructure: TonalStructure,
    val signalQuality: SignalQuality,
    val colorAnalysis: ColorAnalysis,
    val regionDeficiencies: RegionDeficiency,
    val neutralAnchors: NeutralAnchors = NeutralAnchors(0, 0f, 0f, 0f, 0f, 0f),
    val lensQuality: LensQuality = LensQuality(0f, 0f)
)

object ImageAnalyzer {
    fun analyze(bitmap: Bitmap): AnalysisResult {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = 256
        // Cap the analysis scale at 1.0: upsampling a small image invents pixels (bilinear
        // interpolation) and fabricates masks/measurements that don't exist in the source.
        val scale = min(1f, min(maxDim.toFloat() / width, maxDim.toFloat() / height))
        val analysisWidth = max(1, (width * scale).toInt())
        val analysisHeight = max(1, (height * scale).toInt())
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, analysisWidth, analysisHeight, true)

        val pixelCount = analysisWidth * analysisHeight
        val pixels = IntArray(pixelCount)
        scaledBitmap.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)
        scaledBitmap.recycle()

        var sumR = 0L; var sumG = 0L; var sumB = 0L; var sumLuma = 0L
        var sumS = 0f
        // Frame-level chromaticity evidence (not raw RGB variance: brightness changes
        // of one uniformly colored material must not masquerade as color diversity).
        var opponentRBSum = 0.0
        var opponentRBSqSum = 0.0
        var opponentGSum = 0.0
        var opponentGSqSum = 0.0

        val lumaHistogram = IntArray(256)
        val detailWeightedLumaHistogram = FloatArray(256)
        var detailTotalWeight = 0f
        val rHistogram = IntArray(256)
        val gHistogram = IntArray(256)
        val bHistogram = IntArray(256)

        // Color-forensics accumulators (computed in the same pass; no extra full scan).
        val saturationHistogram = IntArray(101)   // s in 0..100 bins
        var hueSinSum = 0f; var hueCosSum = 0f; var hueCount = 0
        var anchorR = 0L; var anchorG = 0L; var anchorB = 0L; var anchorCount = 0L
        var anchorSpreadSum = 0f
        // P1 half-bins: [0]=left, [1]=right, [2]=top, [3]=bottom — channel sums + counts so
        // mixed lighting is measurable from the same census (no extra pass).
        val anchorHalfR = LongArray(4); val anchorHalfG = LongArray(4); val anchorHalfB = LongArray(4)
        val anchorHalfCount = LongArray(4)
        
        val skyMask = FloatArray(pixelCount)
        val vegMask = FloatArray(pixelCount)
        val skinMask = FloatArray(pixelCount)
        val metalMask = FloatArray(pixelCount)
        val stoneMask = FloatArray(pixelCount)
        val detailMask = FloatArray(pixelCount)
        val blurMask = FloatArray(pixelCount)
        val noiseMask = FloatArray(pixelCount)
        val brightMask = FloatArray(pixelCount)
        val shadowMask = FloatArray(pixelCount)
        
        val lumaMap = FloatArray(pixelCount)
        val gradMap = FloatArray(pixelCount)
        val gxMap = FloatArray(pixelCount)
        val gyMap = FloatArray(pixelCount)
        val varMap = FloatArray(pixelCount)
        val sMap = FloatArray(pixelCount)
        val hMap = FloatArray(pixelCount)

        // Pass 1: Base features
        var clippedHighlights = 0
        var crushedShadows = 0
        var channelClipping = 0

        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val idx = y * analysisWidth + x
                val pixel = pixels[idx]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)

                sumR += r; sumG += g; sumB += b
                val norm = (r + g + b).coerceAtLeast(1).toDouble()
                val rbOpponent = (r - b) / norm
                val gOpponent = (2 * g - r - b) / norm
                opponentRBSum += rbOpponent
                opponentRBSqSum += rbOpponent * rbOpponent
                opponentGSum += gOpponent
                opponentGSqSum += gOpponent * gOpponent
                if (r == 255 || g == 255 || b == 255) channelClipping++

                val luma = (r * 0.299f + g * 0.587f + b * 0.114f)
                sumLuma += luma.toLong()
                lumaMap[idx] = luma

                if (luma < 5f) crushedShadows++
                if (luma > 250f) clippedHighlights++

                // Gradients
                val leftLuma = if (x > 0) lumaMap[y * analysisWidth + x - 1] else luma
                val topLuma = if (y > 0) lumaMap[(y - 1) * analysisWidth + x] else luma
                
                val rightPixel = if (x < analysisWidth - 1) pixels[y * analysisWidth + x + 1] else pixel
                val rightLuma = (Color.red(rightPixel) * 0.299f + Color.green(rightPixel) * 0.587f + Color.blue(rightPixel) * 0.114f)
                
                val bottomPixel = if (y < analysisHeight - 1) pixels[(y + 1) * analysisWidth + x] else pixel
                val bottomLuma = (Color.red(bottomPixel) * 0.299f + Color.green(bottomPixel) * 0.587f + Color.blue(bottomPixel) * 0.114f)

                val gx = rightLuma - leftLuma
                val gy = bottomLuma - topLuma
                val gradMag = sqrt((gx * gx + gy * gy).toDouble()).toFloat()
                gradMap[idx] = gradMag
                gxMap[idx] = gx
                gyMap[idx] = gy
                
                val detailWeight = 1f + gradMag
                detailWeightedLumaHistogram[luma.toInt().coerceIn(0, 255)] += detailWeight
                detailTotalWeight += detailWeight

                lumaHistogram[luma.toInt().coerceIn(0, 255)]++
                rHistogram[r]++
                gHistogram[g]++
                bHistogram[b]++
                
                val hsl = FloatArray(3)
                ColorUtils.RGBToHSL(r, g, b, hsl)
                hMap[idx] = hsl[0]
                sMap[idx] = hsl[1]
                sumS += hsl[1]
                saturationHistogram[(hsl[1] * 100f).toInt().coerceIn(0, 100)]++

                // Hue coherence (circular mean): only saturated pixels carry a meaningful hue.
                if (hsl[1] > 0.15f) {
                    val rad = Math.toRadians(hsl[0].toDouble())
                    hueSinSum += sin(rad.toFloat())
                    hueCosSum += cos(rad.toFloat())
                    hueCount++
                }
            }
        }
        
        // Pass 2: Local variance and spatial masking
        var totalDetail = 0f
        var skinSum = 0f; var stoneSum = 0f; var vegSum = 0f; var metalSum = 0f; var skySum = 0f
        var totalNoise = 0f
        var totalBlur = 0f
        // P4: circular hue stats for the sky and vegetation hue bands.
        var skyHueSin = 0f; var skyHueCos = 0f; var skyHueWeight = 0f
        var vegHueSin = 0f; var vegHueCos = 0f; var vegHueWeight = 0f
        // Chroma-noise accumulators: saturation scatter inside flat (low-detail) neighborhoods.
        var chromaNoiseAcc = 0f; var chromaNoiseCnt = 0

        // Sky lives in the upper portion of the frame. Use an explicit row boundary so the mask
        // cleanly drops to zero at the zone edge (robust against downsampling interpolation that
        // would otherwise smear tinted pixels across the seam).
        val skyZoneRatio = 0.6f
        val skyZoneRows = (skyZoneRatio * analysisHeight).toInt()
        
        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val idx = y * analysisWidth + x
                val luma = lumaMap[idx]
                val grad = gradMap[idx]
                val s = sMap[idx]
                val h = hMap[idx]
                
                // 3x3 variance
                var sumSq = 0f; var cnt = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx in 0 until analysisWidth && ny in 0 until analysisHeight) {
                            val nluma = lumaMap[ny * analysisWidth + nx]
                            val diff = nluma - luma
                            sumSq += diff * diff
                            cnt++
                        }
                    }
                }
                val variance = sumSq / cnt
                varMap[idx] = variance
                // Chroma noise: local saturation spread in FLAT regions (low variance = no real
                // structure, so any saturation scatter there is sensor color noise, not content).
                if (variance < 10f) {
                    var sSum = 0f; var sCnt = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until analysisWidth && ny in 0 until analysisHeight) {
                                sSum += sMap[ny * analysisWidth + nx]; sCnt++
                            }
                        }
                    }
                    val sMean = sSum / sCnt
                    var sSq = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until analysisWidth && ny in 0 until analysisHeight) {
                                val d = sMap[ny * analysisWidth + nx] - sMean
                                sSq += d * d
                            }
                        }
                    }
                    chromaNoiseAcc += sqrt(sSq / sCnt)
                    chromaNoiseCnt++
                }
                
                // Detail/Blur classification
                val detailConf = (grad / 50f).coerceIn(0f, 1f) * (variance / 200f).coerceIn(0f, 1f)
                detailMask[idx] = detailConf
                totalDetail += detailConf
                
                if (variance < 10f && luma > 20f && luma < 240f) {
                    val blurC = (1f - (variance / 10f)).coerceIn(0f, 1f)
                    blurMask[idx] = blurC
                    totalBlur += blurC
                }
                
                if (variance > 20f && grad < 20f) {
                    val noiseC = ((variance - 20f) / 100f).coerceIn(0f, 1f)
                    noiseMask[idx] = noiseC
                    totalNoise += noiseC
                }

                shadowMask[idx] = (1f - (luma / 50f)).coerceIn(0f, 1f)
                brightMask[idx] = ((luma - 205f) / 50f).coerceIn(0f, 1f)
                
                if (y < skyZoneRows && h in 190f..250f && luma > 100f && s >= 0.25f) {
                    val hueConf = 1f - abs(h - 220f) / 30f
                    val smoothConf = (1f - (variance / 30f)).coerceIn(0f, 1f)
                    val skyC = hueConf * smoothConf * s
                    skyMask[idx] = skyC.coerceIn(0f, 1f)
                    skySum += skyC
                    // P4: circular hue stats of CONFIRMED sky pixels — mean angle + coherence
                    // (1 - circular variance). This is the hue-band evidence: a phone-sky that
                    // drifted cyan reads here, a natural blue sky sits at ~220°.
                    if (skyC > 0.3f) {
                        val rad = Math.toRadians(h.toDouble())
                        skyHueSin += sin(rad.toFloat()) * skyC
                        skyHueCos += cos(rad.toFloat()) * skyC
                        skyHueWeight += skyC
                    }
                }

                if (h in 70f..150f && s > 0.1f) {
                    val hueConf = 1f - abs(h - 110f) / 40f
                    val vegC = hueConf * s
                    vegMask[idx] = vegC.coerceIn(0f, 1f)
                    vegSum += vegC
                    // P4: circular hue stats of confirmed foliage — natural greens sit at
                    // ~110°; a yellow-shifted (sodium-lit / drought-stressed) canopy reads here.
                    if (vegC > 0.3f) {
                        val rad = Math.toRadians(h.toDouble())
                        vegHueSin += sin(rad.toFloat()) * vegC
                        vegHueCos += cos(rad.toFloat()) * vegC
                        vegHueWeight += vegC
                    }
                }
                
                if (h in 10f..40f && s in 0.2f..0.7f && luma in 50f..200f) {
                    val smoothConf = (1f - (variance / 50f)).coerceIn(0f, 1f)
                    val skinC = s * smoothConf
                    skinMask[idx] = skinC.coerceIn(0f, 1f)
                    skinSum += skinC
                }
                
                if (h in 30f..60f && s > 0.15f) {
                    val structConf = (grad / 30f).coerceIn(0f, 1f)
                    val metalC = s * structConf
                    metalMask[idx] = metalC.coerceIn(0f, 1f)
                    metalSum += metalC
                }
                
                if (s < 0.15f && luma in 50f..200f) {
                    val structConf = (variance / 40f).coerceIn(0f, 1f)
                    val stoneC = (1f - s / 0.15f) * structConf
                    stoneMask[idx] = stoneC.coerceIn(0f, 1f)
                    stoneSum += stoneC
                }

                // Neutral-anchor census: SMOOTH, NEAR-NEUTRAL pixels are the gray-world
                // white-balance evidence (pavement, walls, gray clothing). The gate is on
                // smoothness + ABSOLUTE channel spread (excluding strongly colored objects
                // like sky/foliage) — a cast-tinted neutral MUST qualify, because its channel
                // imbalance is precisely the cast signal we want to measure.
                // P1: anchors are additionally binned into left/right and top/bottom halves so
                // mixed lighting (halves disagreeing) is measurable — same pass, no extra cost.
                if (variance < 10f && luma in 40f..230f) {
                    val pixel = pixels[idx]
                    val ar = (pixel shr 16) and 0xFF
                    val ag = (pixel shr 8) and 0xFF
                    val ab = pixel and 0xFF
                    val maxCh = max(ar, max(ag, ab)); val minCh = min(ar, min(ag, ab))
                    if (maxCh - minCh < 100) {
                        anchorR += ar; anchorG += ag; anchorB += ab; anchorCount++
                        val lumaF = (ar + ag + ab) / 3f
                        anchorSpreadSum += (abs(ar - lumaF) + abs(ag - lumaF) + abs(ab - lumaF)) / 3f
                        // Half bins: left/right by x, top/bottom by y (channel + count each).
                        val inLeft = x < analysisWidth / 2
                        val inTop = y < analysisHeight / 2
                        val hb = if (inLeft) 0 else 1
                        val vb = if (inTop) 2 else 3
                        anchorHalfR[hb] += ar.toLong(); anchorHalfR[vb] += ar.toLong()
                        anchorHalfG[hb] += ag.toLong(); anchorHalfG[vb] += ag.toLong()
                        anchorHalfB[hb] += ab.toLong(); anchorHalfB[vb] += ab.toLong()
                        anchorHalfCount[hb]++; anchorHalfCount[vb]++
                    }
                }
            }
        }

        val avgLuma = sumLuma.toFloat() / pixelCount
        val avgS = sumS / pixelCount

        // Pass 3: Region-relative deficiency metrics. For each detected region we measure
        // defects locally, so the engine can act only when a region is genuinely deficient.
        val skyLumaHist = IntArray(256)
        var skyCnt = 0; var skyBlownCnt = 0; var skySCnt = 0f
        var vegCnt = 0; var vegSCnt = 0f
        var subjectDarkCnt = 0; var subjectBlownCnt = 0; var subjectCrushedCnt = 0; var subjectCnt = 0
        var shadowCrushedCnt = 0; var shadowCnt = 0
        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val idx = y * analysisWidth + x
                val luma = lumaMap[idx]
                val s = sMap[idx]
                val isSky = skyMask[idx] > 0.3f
                if (isSky) {
                    skyCnt++; skySCnt += s; skyLumaHist[luma.toInt().coerceIn(0, 255)]++
                    if (luma > 240f) skyBlownCnt++
                }
                if (vegMask[idx] > 0.3f) { vegCnt++; vegSCnt += s }
                if (!isSky) {
                    subjectCnt++
                    if (luma < 40f) subjectDarkCnt++
                    if (luma > 240f) subjectBlownCnt++
                    if (luma < 10f) subjectCrushedCnt++
                }
                if (shadowMask[idx] > 0.5f) {
                    shadowCnt++
                    if (luma < 10f) shadowCrushedCnt++
                }
            }
        }
        fun regionIqr(hist: IntArray, total: Int): Float {
            if (total <= 0) return 0f
            val t25 = total * 0.25f; val t75 = total * 0.75f
            var c25 = 0f; var c75 = 0f; var p25 = 0f; var p75 = 1f
            var acc = 0f
            for (i in 0..255) { acc += hist[i]; if (acc >= t25) { p25 = i / 255f; break } }
            acc = 0f
            for (i in 0..255) { acc += hist[i]; if (acc >= t75) { p75 = i / 255f; break } }
            return p75 - p25
        }
        val skyAvgS = if (skyCnt > 0) skySCnt / skyCnt else 0f
        val vegAvgS = if (vegCnt > 0) vegSCnt / vegCnt else 0f
        val regionDeficiencies = RegionDeficiency(
            skyBlown = if (skyCnt > 0) skyBlownCnt.toFloat() / skyCnt else 0f,
            skyDull = (1f - skyAvgS).coerceIn(0f, 1f),
            skyLocalContrast = if (skyCnt > 0) regionIqr(skyLumaHist, skyCnt) else 0f,
            vegDrab = (1f - vegAvgS).coerceIn(0f, 1f),
            subjectDark = if (subjectCnt > 0) subjectDarkCnt.toFloat() / subjectCnt else 0f,
            subjectBlown = if (subjectCnt > 0) subjectBlownCnt.toFloat() / subjectCnt else 0f,
            shadowDetailLoss = if (shadowCnt > 0) shadowCrushedCnt.toFloat() / shadowCnt else 0f
        )
        
        // ---- Lens forensics (deterministic optical measurements) ----
        // Vignette: corner-vs-center luma falloff measured only on SMOOTH pixels, with a
        // RADIAL SYMMETRY check across opposing side pairs (E-vs-W, N-vs-S at the same
        // radius). A real lens vignette darkens all radial directions alike; a scene
        // boundary (bright sky on one side, dark buildings on the other) does not. When an
        // opposing pair disagrees, the falloff is scene content — severity collapses to 0.
        var centerLumaAcc = 0f; var centerCnt = 0
        var cornerLumaAcc = 0f; var cornerCnt = 0
        val cx0 = (analysisWidth * 0.4f).toInt(); val cx1 = (analysisWidth * 0.6f).toInt()
        val cy0 = (analysisHeight * 0.4f).toInt(); val cy1 = (analysisHeight * 0.6f).toInt()
        val mx = (min(analysisWidth, analysisHeight) * 0.15f).toInt()
        // Side-midpoint strips (E, W, N, S) at ~85% radial distance — same radius band as
        // corner zones, orthogonal directions.
        var sideWLuma = 0f; var sideWCnt = 0
        var sideELuma = 0f; var sideECnt = 0
        var sideNLuma = 0f; var sideNCnt = 0
        var sideSLuma = 0f; var sideSCnt = 0
        fun inSideZone(x: Int, y: Int): Int {
            val fx = x.toFloat() / analysisWidth; val fy = y.toFloat() / analysisHeight
            val west = fx < 0.15f && fy in 0.35f..0.65f
            val east = fx > 0.85f && fy in 0.35f..0.65f
            val north = fy < 0.15f && fx in 0.35f..0.65f
            val south = fy > 0.85f && fx in 0.35f..0.65f
            return when {
                west -> 1; east -> 2; north -> 3; south -> 4
                else -> 0
            }
        }
        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val idx = y * analysisWidth + x
                if (varMap[idx] > 10f) continue  // smooth pixels only
                val inCenter = x in cx0..cx1 && y in cy0..cy1
                val inCorner = (x < mx || x >= analysisWidth - mx || y < mx || y >= analysisHeight - mx) && !inCenter
                when (inSideZone(x, y)) {
                    1 -> { sideWLuma += lumaMap[idx]; sideWCnt++ }
                    2 -> { sideELuma += lumaMap[idx]; sideECnt++ }
                    3 -> { sideNLuma += lumaMap[idx]; sideNCnt++ }
                    4 -> { sideSLuma += lumaMap[idx]; sideSCnt++ }
                    else -> {
                        if (inCenter) { centerLumaAcc += lumaMap[idx]; centerCnt++ }
                        else if (inCorner) { cornerLumaAcc += lumaMap[idx]; cornerCnt++ }
                    }
                }
            }
        }
        val sideLumaAcc = sideWLuma + sideELuma + sideNLuma + sideSLuma
        val sideCnt = sideWCnt + sideECnt + sideNCnt + sideSCnt
        val vignetteSeverity = if (centerCnt > 0 && cornerCnt > 0 && sideCnt > 0) {
            val centerAvg = centerLumaAcc / centerCnt
            val cornerAvg = cornerLumaAcc / cornerCnt
            // RADIAL SYMMETRY, pairwise: opposing sides (E vs W, N vs S) sit at the SAME
            // radius — a lens vignette darkens both directions of a pair alike. Bright sky
            // on one side vs dark buildings on the other is SCENE content: the pair
            // disagrees and the measurement collapses to 0. Tolerance 0.25 allows natural
            // scene variation between directions while catching one-sided darkening.
            val wAvg = if (sideWCnt > 0) sideWLuma / sideWCnt else 0f
            val eAvg = if (sideECnt > 0) sideELuma / sideECnt else 0f
            val nAvg = if (sideNCnt > 0) sideNLuma / sideNCnt else 0f
            val sAvg = if (sideSCnt > 0) sideSLuma / sideSCnt else 0f
            fun pairAgreement(a: Float, b: Float): Boolean {
                if (a <= 0f || b <= 0f) return false
                return abs(a - b) / max(a, b) < 0.25f
            }
            val symmetric = pairAgreement(wAvg, eAvg) && pairAgreement(nAvg, sAvg)
            // A healthy frame can naturally be a couple of percent darker at corners; only
            // falloff beyond that, AND radially symmetric, is reported as lens vignetting.
            if (centerAvg > 1f && symmetric) {
                (((centerAvg - cornerAvg) / centerAvg - 0.03f) / 0.25f).coerceIn(0f, 1f)
            } else 0f
        } else 0f

        // Chromatic aberration: at STRONG edges, lateral CA shows up as R and B edges that are
        // DISPLACED from the luma edge (a fringe zone where one channel leads/lags). We measure
        // the local channel-vs-luma gradient misalignment across a ±2px window: a fringeless
        // edge aligns everywhere; a fringed edge has channel gradients peaking at an offset.
        // Scale: 1.0 would mean every strong edge fully fringed.
        var caAcc = 0f; var caCnt = 0
        val caThreshold = 40f
        for (y in 2 until analysisHeight - 2) {
            for (x in 2 until analysisWidth - 2) {
                val idx = y * analysisWidth + x
                if (gradMap[idx] < caThreshold) continue
                fun channelAt(xi: Int, yi: Int, shift: Int): Int {
                    val p = pixels[yi * analysisWidth + xi]
                    return when (shift) {
                        0 -> (p shr 16) and 0xFF   // red
                        else -> p and 0xFF          // blue
                    }
                }
                for (ch in 0..1) {
                    // Channel edge position: where the channel's x-gradient magnitude peaks
                    // within [-2..2] of the luma edge. Aligned offsets are checked FIRST so a
                    // perfectly aligned (fringeless) channel edge wins ties (offset 0).
                    var bestOff = 0; var bestGrad = -1f
                    for (off in intArrayOf(0, -1, 1, -2, 2)) {
                        val xi = (x + off).coerceIn(1, analysisWidth - 2)
                        val g1 = channelAt(xi - 1, y, ch); val g2 = channelAt(xi + 1, y, ch)
                        val cg = abs(g2 - g1).toFloat()
                        if (cg > bestGrad) { bestGrad = cg; bestOff = off }
                    }
                    // Misalignment penalty: 1px displacement = 0.5 severity, 2px = 1.0.
                    if (bestGrad > 20f) {
                        caAcc += (abs(bestOff) / 2f)
                        caCnt++
                    }
                }
            }
        }
        val caSeverity = if (caCnt > 0) (caAcc / caCnt * 2f).coerceIn(0f, 1f) else 0f
        val lensQuality = LensQuality(
            vignette = vignetteSeverity,
            chromaticAberration = caSeverity,
            centerLuma = if (centerCnt > 0) centerLumaAcc / centerCnt else 0f
        )

        // Neutral anchors: the gray-world white-balance evidence gathered in Pass 1.
        // P1: also derive the TINT axis (G vs R/B midpoint) and MIXED-LIGHTING disagreement
        // from the half-binned census. The G/R/B asymmetry IS the tint signal — no extra
        // gate on channel tightness, mirroring the warmth-trust reasoning above.
        val neutralAnchors = if (anchorCount > 0) NeutralAnchors(
            count = anchorCount.toInt(),
            coverage = anchorCount.toFloat() / pixelCount,
            avgR = anchorR.toFloat() / anchorCount,
            avgG = anchorG.toFloat() / anchorCount,
            avgB = anchorB.toFloat() / anchorCount,
            spread = anchorSpreadSum / anchorCount,
            tintNorm = ((anchorG.toFloat() / anchorCount) -
                ((anchorR.toFloat() + anchorB.toFloat()) / (2f * anchorCount))) / 255f,
            halfDisagreement = run {
                // Max channel-mean disagreement between the two halves of each axis
                // (L/R and T/B), normalized to 0..1. Only halves with enough anchors vote.
                fun axisDisagreement(binA: Int, binB: Int): Float {
                    val ca = anchorHalfCount[binA]; val cb = anchorHalfCount[binB]
                    if (ca < analysisWidth / 8 || cb < analysisWidth / 8) return 0f
                    fun chDelta(sumA: Long, sumB: Long): Float =
                        abs(sumA.toFloat() / ca - sumB.toFloat() / cb) / 255f
                    return maxOf(
                        chDelta(anchorHalfR[binA], anchorHalfR[binB]),
                        chDelta(anchorHalfG[binA], anchorHalfG[binB]),
                        chDelta(anchorHalfB[binA], anchorHalfB[binB])
                    )
                }
                maxOf(axisDisagreement(0, 1), axisDisagreement(2, 3)).coerceIn(0f, 1f)
            }
        ) else NeutralAnchors(0, 0f, 0f, 0f, 0f, 0f)

        fun getP(hist: IntArray, p: Float): Float {
            val target = pixelCount * p
            var sum = 0f
            for (i in 0..255) {
                sum += hist[i]
                if (sum >= target) return i / 255f
            }
            return 1f
        }
        
        val p01 = getP(lumaHistogram, 0.01f)
        val p05 = getP(lumaHistogram, 0.05f)
        val p25 = getP(lumaHistogram, 0.25f)
        val p50 = getP(lumaHistogram, 0.50f)
        val p75 = getP(lumaHistogram, 0.75f)
        val p95 = getP(lumaHistogram, 0.95f)
        val p99 = getP(lumaHistogram, 0.99f)
        
        val iqr = p75 - p25
        val occupancy = p95 - p05
        val asymmetry = if (occupancy > 0f) (p50 - p05) / occupancy else 0.5f

        // Saturating true black/white points: walk from the extremes until we find a bin
        // that holds real signal (>= 0.5% of pixels), ignoring isolated hot/dead pixels.
        val endpointThreshold = pixelCount * 0.005f
        var blackPoint = 0f
        for (i in 0..255) {
            if (lumaHistogram[i] >= endpointThreshold) { blackPoint = i / 255f; break }
        }
        var whitePoint = 1f
        for (i in (255 downTo 0)) {
            if (lumaHistogram[i] >= endpointThreshold) { whitePoint = i / 255f; break }
        }

        // Per-channel saturating endpoints (same 0.5%-bin logic as luma) — real measurement,
        // not the 0/255 placeholders.
        fun channelEndpoint(hist: IntArray, fromTop: Boolean): Int {
            if (fromTop) {
                for (i in 255 downTo 0) { if (hist[i] >= endpointThreshold) return i }
            } else {
                for (i in 0..255) { if (hist[i] >= endpointThreshold) return i }
            }
            return if (fromTop) 255 else 0
        }
        val minRVal = channelEndpoint(rHistogram, false)
        val maxRVal = channelEndpoint(rHistogram, true)
        val minGVal = channelEndpoint(gHistogram, false)
        val maxGVal = channelEndpoint(gHistogram, true)
        val minBVal = channelEndpoint(bHistogram, false)
        val maxBVal = channelEndpoint(bHistogram, true)
        
        var shadowDensityCount = 0
        var midtoneDensityCount = 0
        var highlightDensityCount = 0
        for (i in 0..255) {
            if (i < 85) shadowDensityCount += lumaHistogram[i]
            else if (i < 170) midtoneDensityCount += lumaHistogram[i]
            else highlightDensityCount += lumaHistogram[i]
        }
        
        val tonalStructure = TonalStructure(
            p01, p05, p25, p50, p75, p95, p99,
            shadowDensityCount.toFloat() / pixelCount,
            midtoneDensityCount.toFloat() / pixelCount,
            highlightDensityCount.toFloat() / pixelCount,
            iqr, occupancy, asymmetry,
            blackPoint, whitePoint
        )
        
        val noiseConfidence = (totalNoise / pixelCount) * 100f

        // Color forensics from the real accumulators:
        // - p95S: 95th percentile of the saturation histogram (vivid-pixel ceiling)
        // - hueStability: 1 - circular hue variance; 1 = one coherent palette, ~0 = mixed light
        // - chromaNoise: mean local saturation scatter in flat regions
        // - neutralEvidence: anchor coverage (near-gray white-balance evidence)
        fun histP(hist: IntArray, total: Int, p: Float): Float {
            if (total <= 0) return 0f
            val target = total * p
            var sum = 0f
            for (i in hist.indices) { sum += hist[i]; if (sum >= target) return i / 100f }
            return 1f
        }
        val p95Saturation = histP(saturationHistogram, pixelCount, 0.95f)
        val hueStability = if (hueCount > 0) {
            val meanLen = sqrt(hueSinSum * hueSinSum + hueCosSum * hueCosSum) / hueCount
            meanLen.coerceIn(0f, 1f)
        } else 0f
        val chromaNoiseMeasured = if (chromaNoiseCnt > 0) chromaNoiseAcc / chromaNoiseCnt else 0f
        // A coherent single-color scene has nearly zero opponent dispersion even
        // when it has strong absolute R/G/B imbalance. That is *not* evidence
        // of a recoverable white balance cast without a known neutral reference.
        val opponentCount = pixelCount.coerceAtLeast(1).toDouble()
        val rbVariance = (opponentRBSqSum / opponentCount -
            (opponentRBSum / opponentCount) * (opponentRBSum / opponentCount)).coerceAtLeast(0.0)
        val greenVariance = (opponentGSqSum / opponentCount -
            (opponentGSum / opponentCount) * (opponentGSum / opponentCount)).coerceAtLeast(0.0)
        val chromaDispersion = sqrt(rbVariance + greenVariance).toFloat()
        val colorAnalysis = ColorAnalysis(
            avgS = avgS,
            p95S = p95Saturation,
            hueStability = hueStability,
            chromaNoise = chromaNoiseMeasured,
            neutralEvidence = neutralAnchors.coverage,
            channelClipping = channelClipping.toFloat() / pixelCount,
            chromaDispersion = chromaDispersion
        )

        val avgR = sumR.toFloat() / pixelCount
        val avgG = sumG.toFloat() / pixelCount
        val avgB = sumB.toFloat() / pixelCount
        val isDarkImage = p75 < 0.4f
        val isBrightImage = p25 > 0.6f

        val skinToneScoreVal = (skinSum / pixelCount) * 100f
        val greenScoreVal = (vegSum / pixelCount) * 100f
        val skyScoreVal = (skySum / pixelCount) * 100f
        val stoneScoreVal = (stoneSum / pixelCount) * 100f
        val metalScoreVal = (metalSum / pixelCount) * 100f

        val sceneCategory = when {
            skyScoreVal > 20f -> "Landscape"
            skinToneScoreVal > 20f -> "Portrait"
            greenScoreVal > 20f -> "Nature"
            else -> "General"
        }
        val photographyGenre = when {
            skinToneScoreVal > 20f -> "Portrait"
            skyScoreVal > 20f -> "Landscape"
            else -> "General"
        }
        val primarySubject = when {
            skinToneScoreVal > 20f -> "Person"
            skyScoreVal > 20f -> "Sky"
            greenScoreVal > 20f -> "Vegetation"
            // Metallic-looking hues and highlights provide limited evidence of a metal
            // region. This is a heuristic label, NOT object recognition.
            metalScoreVal > 20f -> "Metal"
            stoneScoreVal > 20f -> "Structure"
            else -> "Scene"
        }
        val lighting = when {
            isDarkImage -> "Low Light"
            isBrightImage -> "Bright"
            asymmetry < 0.3f -> "Shadow-heavy"
            asymmetry > 0.7f -> "Highlight-heavy"
            else -> "Balanced"
        }
        val dynamicRange = when {
            iqr < 0.2f -> "Narrow"
            iqr > 0.5f -> "Wide"
            else -> "Moderate"
        }
        val histogramBias = when {
            asymmetry < 0.3f -> "Dark-biased"
            asymmetry > 0.7f -> "Bright-biased"
            else -> "Balanced"
        }
        val colorTemperature = when {
            avgR - avgB > 15f -> "Warm"
            avgB - avgR > 15f -> "Cool"
            else -> "Neutral"
        }

        // TEXTURE channel: genuine fine micro-structure distinct from edges and from noise.
        // Per pixel, build the 2x2 structure tensor (Sxx,Syy,Sxy) from signed gradients over a
        // 5x5 window. Coherence = |Sxx-Syy, 2Sxy| / (Sxx+Syy) → 1 for a single directional edge,
        // 0 for isotropic grain. isotropy = 1 - coherence, so texture (fabric, stone, foliage)
        // scores high while a clean outline scores ~0 (that outline is a SHAPE, not texture).
        // Combined with local micro-energy and the existing noise guard, this is the signal that
        // lets the engine add texture without merely strengthening edges or amplifying noise.
        val textureMask = FloatArray(pixelCount)
        val isoRadius = 2
        for (y in 1 until analysisHeight - 1) {
            for (x in 1 until analysisWidth - 1) {
                var sxx = 0f; var syy = 0f; var sxy = 0f
                for (dy in -isoRadius..isoRadius) {
                    for (dx in -isoRadius..isoRadius) {
                        val ix = x + dx; val iy = y + dy
                        if (ix in 0 until analysisWidth && iy in 0 until analysisHeight) {
                            val gi = iy * analysisWidth + ix
                            val gxv = gxMap[gi]; val gyv = gyMap[gi]
                            sxx += gxv * gxv; syy += gyv * gyv; sxy += gxv * gyv
                        }
                    }
                }
                val trace = sxx + syy
                var isotropy = 0f
                if (trace > 1e-6f) {
                    val coherence = sqrt((((sxx - syy) * (sxx - syy) + 4f * sxy * sxy).toDouble())).toFloat() / trace
                    isotropy = (1f - coherence).coerceIn(0f, 1f)
                }
                val idx = y * analysisWidth + x
                val microEnergy = (varMap[idx] / 120f).coerceIn(0f, 1f)
                val noiseGuard = (1f - noiseMask[idx]).coerceIn(0f, 1f)
                textureMask[idx] = (microEnergy * isotropy * noiseGuard).coerceIn(0f, 1f)
            }
        }

        // Focus-saliency × center-prior subject mask (0..1). Subject = the in-focus, detail-rich
        // region near frame center; background = the smooth/blurred margins (bokeh). Built from
        // the existing detailMask (sharp edges) and blurMask (smooth regions), weighted by a
        // Gaussian center-prior. This is saliency estimation, NOT semantic segmentation;
        // keep any region-specific edits conservative when the evidence is weak.
        val subjectMask = FloatArray(pixelCount)
        val bgMask = FloatArray(pixelCount)
        val pxMax = max(1, analysisWidth - 1)
        val pyMax = max(1, analysisHeight - 1)
        val centerSigma = 0.40f
        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val idx = y * analysisWidth + x
                val nx = (2f * x / pxMax) - 1f
                val ny = (2f * y / pyMax) - 1f
                val d2 = nx * nx + ny * ny
                val center = exp(-d2 / (2f * centerSigma * centerSigma))
                val sharp = detailMask[idx]
                val soft = blurMask[idx]
                val saliency = (sharp * (1f - soft)).coerceIn(0f, 1f)
                subjectMask[idx] = (saliency * center).coerceIn(0f, 1f)
                bgMask[idx] = (1f - subjectMask[idx]).coerceIn(0f, 1f)
            }
        }

        // Signal quality with the REAL texture strength: mean of the genuine texture mask
        // (isotropic micro-structure), replacing the old IQR surrogate.
        var textureStrengthAcc = 0f
        for (i in textureMask.indices) textureStrengthAcc += textureMask[i]
        val textureStrengthVal = textureStrengthAcc / pixelCount
        val signalQuality = SignalQuality(
            noiseConfidence = noiseConfidence,
            blurConfidence = (totalBlur / pixelCount) * 100f,
            edgeStrength = (totalDetail / pixelCount) * 100f,
            textureStrength = textureStrengthVal,
            snr = if (noiseConfidence > 0) (totalDetail / pixelCount) / (noiseConfidence / 100f) else 100f,
            highlightClipping = clippedHighlights.toFloat() / pixelCount,
            shadowCrushing = crushedShadows.toFloat() / pixelCount
        )

        // ---- Composition analysis (from the subject mask; no ML needed) ----
        // Subject centroid + mass vs rule-of-thirds intersections, symmetry, headroom balance.
        var subjCx = 0f; var subjCy = 0f; var subjMass = 0f
        for (y in 0 until analysisHeight) {
            for (x in 0 until analysisWidth) {
                val w = subjectMask[y * analysisWidth + x]
                subjCx += x * w; subjCy += y * w; subjMass += w
            }
        }
        val composition = if (subjMass > pixelCount * 0.02f) {
            val nx = (subjCx / subjMass) / pxMax
            val ny = (subjCy / subjMass) / pyMax
            // Distance to the nearest rule-of-thirds intersection.
            val thirds = listOf(1f/3f, 2f/3f)
            var bestDist = 1f
            for (tx in thirds) for (ty in thirds) {
                val d = sqrt(((nx - tx) * (nx - tx) + (ny - ty) * (ny - ty)).toDouble()).toFloat()
                if (d < bestDist) bestDist = d
            }
            val centered = abs(nx - 0.5f) < 0.08f && abs(ny - 0.5f) < 0.08f
            val headroom = ny  // subject vertical position: >0.5 = low in frame (much headroom above)
            when {
                centered -> "Centered subject"
                bestDist < 0.12f -> "Rule of thirds"
                nx < 0.35f && ny > 0.45f && ny < 0.75f -> "Left-weighted, natural headroom"
                nx > 0.65f && ny > 0.45f && ny < 0.75f -> "Right-weighted, natural headroom"
                headroom < 0.35f -> "Tight crop, low headroom"
                headroom > 0.72f -> "High headroom above subject"
                else -> "Off-center subject"
            }
        } else "Scene-wide interest (no dominant subject)"

        // ---- Horizon tilt (perspective) from the dominant edge orientation in the upper
        // part of the frame (sky/ground boundary rows carry the horizon's orientation signal).
        // Accumulate edge direction (gradient +90°) weighted by gradient magnitude, upper zone
        // only; a level horizon means the dominant edge direction is horizontal.
        var angSin = 0f; var angCos = 0f; var angCnt = 0
        val horizonZoneRows = (analysisHeight * 0.6f).toInt()
        for (y in 0 until min(analysisHeight, horizonZoneRows)) {
            for (x in 1 until analysisWidth - 1) {
                val idx = y * analysisWidth + x
                if (gradMap[idx] < 30f) continue
                val gAng = atan2(gyMap[idx], gxMap[idx])
                val edgeAng = gAng + (Math.PI.toFloat() / 2f)
                angSin += sin(edgeAng) * gradMap[idx]
                angCos += cos(edgeAng) * gradMap[idx]
                angCnt++
            }
        }
        val perspectiveCorrection = if (angCnt > 50) {
            val meanAng = atan2(angSin, angCos)
            // Level edge direction = horizontal (angle 0 or PI). Deviation from that = tilt.
            var tiltDeg = Math.toDegrees(meanAng.toDouble())
            if (tiltDeg > 90.0) tiltDeg -= 180.0
            if (tiltDeg < -90.0) tiltDeg += 180.0
            if (abs(tiltDeg) > 1.0 && abs(tiltDeg) < 30.0) {
                "Horizon appears tilted ${"%.1f".format(abs(tiltDeg))}° ${if (tiltDeg > 0) "CW" else "CCW"}"
            } else null
        } else null

        // ---- Crop recommendations: score each candidate frame by subject retention ----
        val recommendedCrops = mutableListOf<CropRecommendation>()
        if (subjMass > pixelCount * 0.02f) {
            val subjCxPix = subjCx / subjMass
            val subjCyPix = subjCy / subjMass
            // Rough subject extent: radius covering 90% of subject-mask energy around centroid.
            var rAcc = 0f
            for (y in 0 until analysisHeight step 2) {
                for (x in 0 until analysisWidth step 2) {
                    val w = subjectMask[y * analysisWidth + x]
                    if (w > 0.2f) {
                        val dx = x - subjCxPix; val dy = y - subjCyPix
                        rAcc = max(rAcc, sqrt((dx * dx + dy * dy).toDouble()).toFloat())
                    }
                }
            }
            val subjRadius = if (rAcc > 0f) rAcc else min(analysisWidth, analysisHeight) * 0.2f
            val aspect = analysisWidth.toFloat() / analysisHeight.toFloat()
            val candidates = listOf(
                CropFrame.SQUARE, CropFrame.PORTRAIT_4_5, CropFrame.LANDSCAPE_16_9
            )
            data class CropScore(val frame: CropFrame, val retention: Float)
            val scores = candidates.map { frame ->
                val ratio = frame.aspectRatio!!
                // Center-crop box dims in analysis pixels.
                val ch: Float; val cw: Float
                if (aspect > ratio) { ch = analysisHeight.toFloat(); cw = ch * ratio }
                else { cw = analysisWidth.toFloat(); ch = cw / ratio }
                // Fraction of a circle of radius subjRadius centered at centroid that stays
                // inside the crop box (center crop, so offset = box center).
                val halfW = cw / 2f; val halfH = ch / 2f
                val dx = abs(subjCxPix - analysisWidth / 2f)
                val dy = abs(subjCyPix - analysisHeight / 2f)
                val reachX = (dx + subjRadius) / halfW
                val reachY = (dy + subjRadius) / halfH
                val retention = (1f / max(1f, max(reachX, reachY))).coerceIn(0f, 1f)
                CropScore(frame, retention)
            }
            // Rank: retention is the dominant factor; only recommend crops that keep >=85% of
            // the subject AND differ from the current aspect enough to be a meaningful change.
            val best = scores.filter { it.retention >= 0.85f }
                .filter { abs(it.frame.aspectRatio!! - aspect) > 0.08f }
                .maxByOrNull { it.retention }
            if (best != null) {
                val pct = (best.retention * 100).toInt()
                val reason = when (best.frame) {
                    CropFrame.LANDSCAPE_16_9 -> "Widens the scene, keeps $pct% of subject energy"
                    CropFrame.PORTRAIT_4_5 -> "Tightens framing, keeps $pct% of subject energy"
                    else -> "Balances the frame, keeps $pct% of subject energy"
                }
                recommendedCrops.add(CropRecommendation(best.frame, (best.retention * 100).toInt().coerceIn(60, 95), reason))
            }
        }

        // ---- Report: complete, derived from real measurements ----
        val suggestedEditingStyle = when {
            skinToneScoreVal > 20f -> "Portrait — skin-safe soft lift"
            skyScoreVal > 20f && greenScoreVal > 10f -> "Landscape — depth-first recovery"
            metalScoreVal > 20f -> "Material-aware — highlight-preserving structure"
            isDarkImage -> "Low-light recovery — restrained shadow lift"
            else -> "Standard — defect-first, restrained enhancement"
        }
        val confidenceScore = (
            (if (skinToneScoreVal > 20f || skyScoreVal > 20f || greenScoreVal > 20f || metalScoreVal > 20f) 40 else 15) +
                (if (neutralAnchors.count > pixelCount * 0.005f) 20 else 5) +
                (if (signalQuality.snr > 5f) 20 else 8) +
                (if (subjMass > pixelCount * 0.02f) 20 else 10)
        ).coerceIn(0, 100)

        val report = AnalysisReport(
            sceneCategory = sceneCategory, photographyGenre = photographyGenre, primarySubject = primarySubject,
            lighting = lighting, composition = composition, dynamicRange = dynamicRange,
            histogramBias = histogramBias, colorTemperature = colorTemperature, suggestedEditingStyle = suggestedEditingStyle,
            toneAdjustments = "", colorAdjustments = "", confidenceScore = confidenceScore,
            perspectiveCorrection = perspectiveCorrection
        )

        return AnalysisResult(
            avgLuma = avgLuma, contrastRatio = occupancy, isLowContrast = iqr < 0.2f,
            isDarkImage = isDarkImage, isBrightImage = isBrightImage,
            isWarm = avgR - avgB > 15f, isCool = avgB - avgR > 15f,
            sharpnessScore = signalQuality.edgeStrength, noiseScore = signalQuality.noiseConfidence,
            avgR = avgR, avgG = avgG, avgB = avgB,
            minLuma = (p01 * 255).toInt(), maxLuma = (p99 * 255).toInt(),
            minR = minRVal, maxR = maxRVal, minG = minGVal, maxG = maxGVal, minB = minBVal, maxB = maxBVal,
            analysisWidth = analysisWidth, analysisHeight = analysisHeight,
            skyMask = skyMask, vegMask = vegMask, skinMask = skinMask, metalMask = metalMask, stoneMask = stoneMask,
            detailMask = detailMask, blurMask = blurMask, noiseMask = noiseMask, textureMask = textureMask,
            brightMask = brightMask, shadowMask = shadowMask,
            subjectMask = subjectMask, bgMask = bgMask,
            skinToneScore = skinToneScoreVal, stoneScore = stoneScoreVal,
            greenScore = greenScoreVal, goldBrassScore = metalScoreVal,
            skyScore = skyScoreVal,
            skyHueStats = run {
                val len = sqrt(skyHueSin * skyHueSin + skyHueCos * skyHueCos)
                if (skyHueWeight > 0f && len > 1e-6f) HueBandStats(
                    meanDeg = ((Math.toDegrees(atan2(skyHueSin, skyHueCos).toDouble()))).toFloat().let { if (it < 0) it + 360f else it },
                    coherence = (len / skyHueWeight).coerceIn(0f, 1f),
                    weight = skyHueWeight
                ) else HueBandStats(0f, 0f, 0f)
            },
            vegHueStats = run {
                val len = sqrt(vegHueSin * vegHueSin + vegHueCos * vegHueCos)
                if (vegHueWeight > 0f && len > 1e-6f) HueBandStats(
                    meanDeg = ((Math.toDegrees(atan2(vegHueSin, vegHueCos).toDouble()))).toFloat().let { if (it < 0) it + 360f else it },
                    coherence = (len / vegHueWeight).coerceIn(0f, 1f),
                    weight = vegHueWeight
                ) else HueBandStats(0f, 0f, 0f)
            },
            report = report, recommendedCrops = recommendedCrops,
            lumaHistogram = lumaHistogram, rHistogram = rHistogram, gHistogram = gHistogram, bHistogram = bHistogram,
            pixelCount = pixelCount, detailWeightedLumaHistogram = detailWeightedLumaHistogram, detailTotalWeight = detailTotalWeight,
            tonalStructure = tonalStructure, signalQuality = signalQuality, colorAnalysis = colorAnalysis,
            regionDeficiencies = regionDeficiencies,
            neutralAnchors = neutralAnchors,
            lensQuality = lensQuality
        )
    }
}
