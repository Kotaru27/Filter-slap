package com.example.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.ColorUtils
import boofcv.alg.filter.blur.BlurImageOps
import boofcv.struct.image.GrayF32
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class CropFrame { ORIGINAL, SQUARE, PORTRAIT_4_5, LANDSCAPE_16_9; val displayName: String get() = name.lowercase().replaceFirstChar { it.uppercaseChar() }.replace("_", " "); val aspectRatio: Float? get() = when(this) { SQUARE -> 1f; PORTRAIT_4_5 -> 4f/5f; LANDSCAPE_16_9 -> 16f/9f; ORIGINAL -> null } }

fun cropBitmap(source: Bitmap, targetAspectRatio: Float?): Bitmap {
    if (targetAspectRatio == null) return source
    val currentRatio = source.width.toFloat() / source.height.toFloat()
    if (abs(currentRatio - targetAspectRatio) < (1f / max(source.width, source.height))) return source
    var newWidth = source.width; var newHeight = source.height
    if (currentRatio > targetAspectRatio) { newWidth = (source.height * targetAspectRatio).toInt().coerceIn(1, source.width) } else { newHeight = (source.width / targetAspectRatio).toInt().coerceIn(1, source.height) }
    val xOffset = (source.width - newWidth) / 2; val yOffset = (source.height - newHeight) / 2
    val cropped = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(cropped)
    canvas.drawBitmap(source, Rect(xOffset, yOffset, xOffset + newWidth, yOffset + newHeight), Rect(0, 0, newWidth, newHeight), null)
    return cropped
}
data class LiveEditFrame(val bitmap: Bitmap, val mask: Bitmap?, val stageIndex: Int, val totalStages: Int)
data class ProcessingStats(val computeTimeMs: Long = 0, val artifactsFixed: Int = 0, val stagesApplied: Set<String> = emptySet())

// The truth about one render: the bitmap produced, plus the plan and analysis that
// ACTUALLY produced it (post harm-guard and convergence). The UI reports from this,
// never from a pre-evaluation of its own, so "what the engine did" is what the user sees.
data class RenderResult(
    val bitmap: Bitmap,
    val stats: ProcessingStats,
    val appliedPlan: EditPlan,
    val appliedAnalysis: AnalysisResult
)

object ImageProcessor {
    fun processFullRes(input: Bitmap, analysis: AnalysisResult): Bitmap {
        return process(input, analysis).bitmap
    }

    // No more fake delay or fake stages. Emits the final result immediately.
    fun processLive(input: Bitmap, analysis: AnalysisResult): Flow<LiveEditFrame> = flow {
        val (result, _, _, _) = process(input, analysis)
        emit(LiveEditFrame(result, null, 1, 1))
    }

    private fun curveMap(v: Float, mag: Float): Float {
        if (mag == 0f) return v
        // Magnitude maps 1:1 onto a mild power curve: mag>0 brightens, mag<0 darkens.
        return if (mag > 0) v.pow(1f / (1f + mag.coerceIn(0f, 1f)))
        else v.pow(1f + (-mag).coerceIn(0f, 1f))
    }

    private fun shadowCurve(v: Float, mag: Float): Float {
        val weight = (1f - v) * (1f - v)
        val boost = v.pow(0.5f) - v
        return v + boost * mag * weight
    }

    private fun highlightCurve(v: Float, mag: Float): Float {
        val m = mag.coerceIn(0f, 1f)
        val t = ((v - 0.5f) / 0.5f).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return (v - m * smooth * (1f - v)).coerceIn(0f, 1f)
    }
    
    private fun contrastCurve(v: Float, mag: Float): Float {
        val m = mag.coerceIn(-0.5f, 0.5f)
        val u = v - 0.5f
        return (v + m * 4f * u * (0.25f - u * u)).coerceIn(0f, 1f)
    }

    private fun applyPlanToPixels(
        pixels: IntArray, width: Int, height: Int,
        analysis: AnalysisResult, plan: EditPlan,
        hasDetailEdits: Boolean, midFreq: FloatArray, highFreq: FloatArray
    ) {
        val aw = analysis.analysisWidth
        val ah = analysis.analysisHeight
        val scaleX = aw.toFloat() / width
        val scaleY = ah.toFloat() / height
        
        val exposureDecisions = plan.decisions.filter { it.parameter == "Exposure" }
        val contrastDecisions = plan.decisions.filter { it.parameter == "Contrast" }
        val shadowDecisions = plan.decisions.filter { it.parameter == "Shadows" }
        val highlightDecisions = plan.decisions.filter { it.parameter == "Highlights" }
        val blackDecisions = plan.decisions.filter { it.parameter == "Blacks" }
        val whiteDecisions = plan.decisions.filter { it.parameter == "Whites" }
        val dehazeDecisions = plan.decisions.filter { it.parameter == "Dehaze" }
        val clarityDecisions = plan.decisions.filter { it.parameter == "Clarity" }
        val textureDecisions = plan.decisions.filter { it.parameter == "Texture" }
        // Lens corrections: single bounded magnitude each (defect-gated upstream).
        val vignetteMag = plan.decisions.filter { it.parameter == "Vignette" }
            .sumOf { it.magnitude.toDouble() }.toFloat().coerceIn(0f, 0.6f)
        val caMag = plan.decisions.filter { it.parameter == "CA" }
            .sumOf { it.magnitude.toDouble() }.toFloat().coerceIn(0f, 0.8f)
        // Precompute the radial vignette gain per pixel row/col (normalized distance from
        // frame center): gain = 1 + mag * (d^2 - 0.35), so corners lift, center untouched.
        val vignetteGainX = if (vignetteMag > 0f) FloatArray(width) { i ->
            val nx = (2f * i / max(1, width - 1)) - 1f
            nx * nx
        } else null
        val vignetteGainY = if (vignetteMag > 0f) FloatArray(height) { j ->
            val ny = (2f * j / max(1, height - 1)) - 1f
            ny * ny
        } else null
        // CA correction is computed in the pixel loop. Do not allocate a full-frame
        // float array (64 MB at 16 megapixels) for a value only used once.

        fun getMaskWeight(region: String, x: Int, y: Int): Float {
            if (region == "Global") return 1f
            val ax = (x * scaleX).toInt().coerceIn(0, aw - 1)
            val ay = (y * scaleY).toInt().coerceIn(0, ah - 1)
            val aIdx = ay * aw + ax
            return when (region) {
                "Sky" -> analysis.skyMask[aIdx]
                "Vegetation" -> analysis.vegMask[aIdx]
                "Skin" -> analysis.skinMask[aIdx]
                "Metal" -> analysis.metalMask[aIdx]
                "Detail" -> analysis.detailMask[aIdx]
                "Subject" -> if (analysis.subjectMask.size > aIdx) analysis.subjectMask[aIdx] else 0f
                "Background" -> if (analysis.bgMask.size > aIdx) analysis.bgMask[aIdx] else 0f
                // Texture boost fires only on GENUINE micro-texture (isotropic grain) on the
                // subject — so we add "texture" without strengthening outlines or background noise.
                "Texture" -> {
                    val tex = if (analysis.textureMask.size > aIdx) analysis.textureMask[aIdx] else 0f
                    val subj = if (analysis.subjectMask.size > aIdx) analysis.subjectMask[aIdx] else 0f
                    (tex * subj).coerceIn(0f, 1f)
                }
                "Shadows" -> analysis.shadowMask[aIdx]
                "Highlights" -> analysis.brightMask[aIdx]
                else -> 0f
            }
        }

        val hsl = FloatArray(3)
        val hslOut = FloatArray(3)
        val noiseConf = analysis.signalQuality.noiseConfidence

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val c = pixels[idx]
                
                // Use the original channel integers. int -> float -> int truncation
                // otherwise loses one code value for some source colors.
                ColorUtils.RGBToHSL(Color.red(c), Color.green(c), Color.blue(c), hsl)
                val h = hsl[0]; var s = hsl[1]; var l = hsl[2]
                
                val ax = (x * scaleX).toInt().coerceIn(0, aw - 1)
                val ay = (y * scaleY).toInt().coerceIn(0, ah - 1)
                val aIdx = ay * aw + ax
                
                if (hasDetailEdits) {
                    var midL = midFreq[idx]
                    var highL = highFreq[idx]
                    // Denoise the measured high-frequency band BEFORE applying texture.
                    // Keep highFreq itself unchanged as a reference, so this suppression
                    // actually reaches the final pixel instead of canceling itself out.
                    if (noiseConf > 8f) {
                        val suppress = ProcessingSafetyMath.noiseSuppression(noiseConf, analysis.noiseMask[aIdx])
                        highL *= 1f - suppress
                    }
                    val skinProtect = (1f - analysis.skinMask[aIdx]).coerceIn(0f, 1f)
                    // SHARPENING INTELLIGENCE (pro rule: not everything blurry gets sharpened):
                    // - SKY: a smooth gradient is not detail; sharpening it manufactures banding.
                    // - BOKEH (blurMask): out-of-focus background is intentionally soft.
                    // - BRIGHT (brightMask): local contrast against highlights creates halos.
                    // Each attenuates the detail lift; combined they gate it to genuine
                    // subject texture in a safe tonal range.
                    val skyProtect = (1f - analysis.skyMask[aIdx]).coerceIn(0f, 1f)
                    val bokehProtect = (1f - analysis.blurMask[aIdx]).coerceIn(0f, 1f)
                    val brightProtect = (1f - analysis.brightMask[aIdx]).coerceIn(0f, 1f)
                    val detailGuard = skyProtect * bokehProtect * brightProtect

                    // Clarity = mid-frequency contrast on detail-rich, NON-skin areas (its defined
                    // behavior: it lifts local structure without over-texturizing faces).
                    for (dec in clarityDecisions) midL += midL * dec.magnitude * getMaskWeight(dec.regionMask, x, y) * skinProtect * detailGuard
                    for (dec in textureDecisions) highL += highL * dec.magnitude * getMaskWeight(dec.regionMask, x, y) * skinProtect * detailGuard

                    // Frequency separation is computed in RGB luma, not HSL lightness.
                    // Only transfer the measured DETAIL DELTA into the HSL tone channel.
                    // Assigning RGB luma itself as HSL lightness bleaches saturated colors,
                    // even when no clarity/texture adjustment is requested.
                    l = ProcessingSafetyMath.detailLightness(
                        l, midFreq[idx], midL, highFreq[idx], highL
                    )
                }

                for (dec in exposureDecisions) l = curveMap(l, dec.magnitude * getMaskWeight(dec.regionMask, x, y))
                for (dec in shadowDecisions) l = shadowCurve(l, dec.magnitude * getMaskWeight(dec.regionMask, x, y))
                for (dec in highlightDecisions) l = highlightCurve(l, dec.magnitude * getMaskWeight(dec.regionMask, x, y))
                // Level-stretch endpoints: Blacks pulls the true black point down toward 0,
                // Whites pulls the true white point up toward 1, both monotonic.
                for (dec in blackDecisions) {
                    val w = getMaskWeight(dec.regionMask, x, y)
                    if (w > 0f) {
                        val mask = (1f - l / 0.15f).coerceIn(0f, 1f)
                        val sm = mask * mask * (3f - 2f * mask)
                        l *= (1f - dec.magnitude * w * sm)
                    }
                }
                for (dec in whiteDecisions) {
                    val w = getMaskWeight(dec.regionMask, x, y)
                    if (w > 0f) {
                        val mask = ((l - 0.85f) / 0.15f).coerceIn(0f, 1f)
                        val sm = mask * mask * (3f - 2f * mask)
                        l += dec.magnitude * w * sm * (1f - l)
                    }
                }
                
                for (dec in dehazeDecisions) {
                    val w = getMaskWeight(dec.regionMask, x, y)
                    if (w > 0f) {
                        l = max(0f, l - dec.magnitude * w * (1f - l))
                        s = min(1f, s + dec.magnitude * w)
                    }
                }
                
                for (dec in contrastDecisions) l = contrastCurve(l, dec.magnitude * getMaskWeight(dec.regionMask, x, y))

                // Vignette correction — DEFICIT RESTORE, not blind gain. The measured center
                // luma (from the analyzer) is the restoration TARGET: a darkened corner is
                // lifted toward what the lens should have delivered, never beyond it, and
                // never past the soft highlight ceiling. Bright pixels (sky, white walls,
                // highlights) receive a fading correction so this pass can never create new
                // clipping — potential highlight clipping.
                if (vignetteGainX != null && vignetteGainY != null) {
                    val d2 = vignetteGainX[x] + vignetteGainY[y]
                    // Radial deficit weight: 0 at center → 1 at corners.
                    val radial = (d2 / 2f).coerceIn(0f, 1f)
                    // Soft highlight protection: pixels above the measured center luma get a
                    // correction that fades to ZERO by ~1.25x center — already-bright pixels
                    // were not victim to the vignette deficit.
                    val pLuma = l * 255f
                    val center = analysis.lensQuality.centerLuma
                    val brightProtect = if (center > 1f) {
                        (1f - ((pLuma - center * 0.85f) / (center * 0.4f)).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                    } else 1f
                    if (brightProtect > 0f) {
                        // Restore toward center: lift by the deficit fraction, damped by the
                        // decision magnitude. Bounded so the result cannot exceed center luma.
                        val deficit = ((center / 255f) - l).coerceIn(0f, 1f)
                        l = (l + deficit * radial * vignetteMag * brightProtect).coerceIn(0f, 1f)
                    }
                }

                // Chromatic-aberration estimates are uncertain; only gently desaturate
                // high-confidence, fringe-like edge pixels. No full-frame buffer.
                val caStrength = if (caMag > 0f && analysis.detailMask[aIdx] > 0.25f)
                    caMag * analysis.detailMask[aIdx] * 0.5f else 0f

                l = l.coerceIn(0f, 1f)
                
                // Saturation and Vibrance are NOT applied here: color is graded in a separate
                // OKLab post-pass (applyOklabColor) after tonal work, so vivid hues (gold, brass,
                // foliage) keep their hue exactly instead of shifting in HSL space.
                s = s.coerceIn(0f, 1f)
                hslOut[0] = h; hslOut[1] = s; hslOut[2] = l
                // A zero change MUST be pixel-identical; HSL round-tripping every
                // untouched pixel introduces quantization and degrades repeated edits.
                var outPixel = if (abs(l - hsl[2]) < 1e-7f && abs(s - hsl[1]) < 1e-7f)
                    c else ColorUtils.HSLToColor(hslOut)

                // CA fringe removal (RGB space): pull R and B toward the pixel's luma at
                // strong-edge pixels. This desaturates the fringe the lens added without
                // touching genuine color elsewhere.
                if (caStrength > 0f) {
                    val pull = caStrength
                    val or = Color.red(outPixel); val og = Color.green(outPixel); val ob = Color.blue(outPixel)
                    val oluma = (or * 0.299f + og * 0.587f + ob * 0.114f)
                    val nr2 = (or + (oluma - or) * pull).toInt().coerceIn(0, 255)
                    val nb2 = (ob + (oluma - ob) * pull).toInt().coerceIn(0, 255)
                    outPixel = Color.rgb(nr2, og, nb2)
                }
                pixels[idx] = if (Color.alpha(c) == 255) outPixel else
                    Color.argb(Color.alpha(c), Color.red(outPixel), Color.green(outPixel), Color.blue(outPixel))
            }
        }
    }

    private fun evaluateQualityGate(
        originalPixels: IntArray, candidatePixels: IntArray, width: Int, height: Int
    ): Float {
        // Measure harm: clipping AND near-clipping (>=240: the shoulder a highlight-recovery
        // pass must never manufacture) introduced, plus crushed shadows introduced.
        // Returns a "harm penalty" from 0.0 (perfect) to 1.0 (reject).
        var newlyClipped = 0; var newlyCrushed = 0

        for (i in 0 until width * height) {
            val oc = originalPixels[i]
            val cc = candidatePixels[i]
            val ol = (Color.red(oc) * 0.299f + Color.green(oc) * 0.587f + Color.blue(oc) * 0.114f)
            val cl = (Color.red(cc) * 0.299f + Color.green(cc) * 0.587f + Color.blue(cc) * 0.114f)

            // Count transitions, not net global counts: newly destroyed highlights
            // must not be hidden by recovering a different highlight elsewhere.
            if (ol < 240f && cl >= 240f) newlyClipped++
            if (ol >= 5f && cl < 5f) newlyCrushed++
        }

        val total = width * height.toFloat()
        val clipIncrease = newlyClipped / total
        val crushIncrease = newlyCrushed / total

        // If more than ~1.4% of the image becomes newly bright-crushed, we have high harm.
        val harm = (clipIncrease * 70f) + (crushIncrease * 50f)
        return harm.coerceIn(0f, 1f)
    }

    // Color-disturbance gate: an edit can avoid clipping and still be a bad edit (sky turned
    // cyan, trees turned neon, gold turned orange). Penalize over-boosting pixels that were ALREADY
    // vivid, and large hue rotations on saturated pixels, even when there is zero clipping.
    private fun evaluateColorGate(
        originalPixels: IntArray, candidatePixels: IntArray, width: Int, height: Int
    ): Float {
        val hsA = FloatArray(3); val hsB = FloatArray(3)
        var vividBoosted = 0; var n = 0
        var hueDrift = 0f; var driftCount = 0
        for (i in 0 until width * height) {
            val oc = originalPixels[i]; val cc = candidatePixels[i]
            ColorUtils.RGBToHSL(Color.red(oc), Color.green(oc), Color.blue(oc), hsA)
            ColorUtils.RGBToHSL(Color.red(cc), Color.green(cc), Color.blue(cc), hsB)
            // Ignore tiny HSL round-trip/quantization changes; only actual
            // oversaturation (>5 percentage points) is photographic harm.
            if (hsA[1] > 0.45f && hsB[1] > hsA[1] + 0.05f) vividBoosted++
            if (hsA[1] > 0.15f) {
                hueDrift += ProcessingSafetyMath.hueDrift(hsA[0], hsB[0]); driftCount++
            }
            n++
        }
        val overboost = vividBoosted.toFloat() / n
        val hue = if (driftCount > 0) hueDrift / driftCount else 0f
        return (overboost * 6f + hue * 4f).coerceIn(0f, 1f)
    }

    // STAGE C — Chroma noise reduction. Suppresses color fringing in flat, noisy regions
    // (high noiseMask AND low detailMask) by gently pulling saturation toward neutral.
    private fun applyChromaNoiseReduction(
        pixels: IntArray, width: Int, height: Int, analysis: AnalysisResult, stagesApplied: MutableSet<String>
    ) {
        val noiseConf = analysis.signalQuality.noiseConfidence
        if (noiseConf < 6f) return
        // Luma-noise drives the base strength; the MEASURED chroma-noise (local saturation
        // scatter in flat regions) scales it up or down: a frame can have low luma noise but
        // visible color speckle (and vice versa). Both bounded so the pass stays gentle.
        val chromaMeasured = analysis.colorAnalysis.chromaNoise
        val strength = (((noiseConf - 6f) / 30f) * (0.5f + (chromaMeasured * 20f).coerceIn(0f, 1.5f)))
            .coerceIn(0.05f, 0.6f)
        val aW = analysis.analysisWidth
        val aH = analysis.analysisHeight
        val sX = aW.toFloat() / width
        val sY = aH.toFloat() / height
        val hsl = FloatArray(3)
        val out = FloatArray(3)
        var touched = 0
        for (i in 0 until width * height) {
            val ax = ((i % width) * sX).toInt().coerceIn(0, aW - 1)
            val ay = ((i / width) * sY).toInt().coerceIn(0, aH - 1)
            val aIdx = ay * aW + ax
            val localNoise = analysis.noiseMask[aIdx]
            val localDetail = analysis.detailMask[aIdx]
            val g = (localNoise * (1f - localDetail)).coerceIn(0f, 1f)
            if (g > 0.1f) {
                val c = pixels[i]
                ColorUtils.RGBToHSL(Color.red(c), Color.green(c), Color.blue(c), hsl)
                val reducedS = hsl[1] * (1f - strength * g)
                out[0] = hsl[0]; out[1] = reducedS.coerceIn(0f, 1f); out[2] = hsl[2]
                pixels[i] = ColorUtils.HSLToColor(out)
                touched++
            }
        }
        if (touched > 0) stagesApplied.add("Smart NR")
    }

    // STAGE F — White balance post-pass. WARMTH: shifts R and B toward G by the decision's
    // magnitude (negative cools, positive warms). P1 TINT: shifts G toward the R/B midpoint
    // (negative pulls G down, neutralizing a green cast; positive lifts it for magenta).
    // Both capped so colors never go posterized; skin pixels are skipped (skin carries its
    // own color truth). The CDE emits bounded magnitudes, honored directly.
    internal fun applyWhiteBalance(pixels: IntArray, width: Int, height: Int, plan: EditPlan, analysis: AnalysisResult?) {
        var mag = 0f
        var tintMag = 0f
        for (dec in plan.decisions) {
            if (dec.parameter == "WhiteBalance") mag += dec.magnitude
            if (dec.parameter == "WhiteBalanceTint") tintMag += dec.magnitude
        }
        if (mag == 0f && tintMag == 0f) return
        mag = mag.coerceIn(-0.18f, 0.18f)
        tintMag = tintMag.coerceIn(-0.18f, 0.18f)
        val rGain = 1f + mag
        val bGain = 1f - mag
        val aw = analysis?.analysisWidth ?: 0
        val ah = analysis?.analysisHeight ?: 0
        val scaleX = if (aw > 0 && width > 0) aw.toFloat() / width else 0f
        val scaleY = if (ah > 0 && height > 0) ah.toFloat() / height else 0f
        for (i in pixels.indices) {
            val c = pixels[i]
            var r = Color.red(c)
            var g = Color.green(c)
            var b = Color.blue(c)
            if (analysis != null && aw > 0 && ah > 0 && width > 0) {
                val px = i % width
                val py = i / width
                val ax = (px * scaleX).toInt().coerceIn(0, aw - 1)
                val ay = (py * scaleY).toInt().coerceIn(0, ah - 1)
                val aIdx = ay * aw + ax
                if (analysis.skinMask[aIdx] > 0.3f) continue
            }
            r = (r * rGain).toInt().coerceIn(0, 255)
            b = (b * bGain).toInt().coerceIn(0, 255)
            // Correct ONLY in the direction supported by the anchor evidence.
            // Avoid shifting saturated colors (e.g. material colors) as if neutral.
            if (tintMag != 0f) g = ProcessingSafetyMath.correctedGreen(r, g, b, tintMag)
            pixels[i] = Color.rgb(r, g, b)
        }
    }

    // ---- OKLab color post-pass (senior-photographer color grading) ----
    // Saturation and Vibrance are graded in OKLab, the perceptually-uniform space, so boosting
    // chroma scales the (a,b) magnitude WITHOUT rotating the hue angle. This is what keeps vivid
    // gold/brass/foliage hue-true — the thing an HSL multiply gets wrong (blues -> purple,
    // yellows -> green). Tonal (lightness) work stays in the existing curves; only chroma moves here.
    private fun srgbToLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)

    private fun linearToSrgb(v: Float): Float {
        val c = if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return c.coerceIn(0f, 1f)
    }

    private fun okLabFromLinear(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b
        val m = 0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b
        val s = 0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b
        val ll = cbrt(l); val mm = cbrt(m); val ss = cbrt(s)
        out[0] = 0.2104542553f * ll + 0.7936177850f * mm - 0.0040720468f * ss
        out[1] = 1.9779984951f * ll - 2.4285922050f * mm + 0.4505937099f * ss
        out[2] = 0.0259040371f * ll + 0.7827717662f * mm - 0.8086757660f * ss
    }

    private fun okLabToLinear(o: FloatArray): FloatArray {
        val l = o[0] + 0.3963377774f * o[1] + 0.2158037573f * o[2]
        val m = o[0] - 0.1055613458f * o[1] - 0.0638541728f * o[2]
        val s = o[0] - 0.0894841775f * o[1] - 1.2914855480f * o[2]
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        return floatArrayOf(r, g, b)
    }

    internal fun applyOklabColor(pixels: IntArray, width: Int, height: Int, plan: EditPlan, analysis: AnalysisResult?) {
        val satDecs = plan.decisions.filter { it.parameter == "Saturation" }
        val vibDecs = plan.decisions.filter { it.parameter == "Vibrance" }
        if (satDecs.isEmpty() && vibDecs.isEmpty()) return
        val aw = analysis?.analysisWidth ?: 0
        val ah = analysis?.analysisHeight ?: 0
        val scaleX = if (aw > 0 && width > 0) aw.toFloat() / width else 0f
        val scaleY = if (ah > 0 && height > 0) ah.toFloat() / height else 0f
        val ok = FloatArray(3)

        fun maskWeight(region: String, x: Int, y: Int): Float {
            if (region == "Global") return 1f
            if (analysis == null || aw == 0 || ah == 0) return 1f
            val ax = (x * scaleX).toInt().coerceIn(0, aw - 1)
            val ay = (y * scaleY).toInt().coerceIn(0, ah - 1)
            val ai = ay * aw + ax
            return when (region) {
                "Vegetation" -> analysis.vegMask[ai]
                "Sky" -> analysis.skyMask[ai]
                "Skin" -> analysis.skinMask[ai]
                // P2: the background mask drives de-emphasis (bgMask = 1 - subjectMask).
                "Background" -> if (analysis.bgMask.size > ai) analysis.bgMask[ai] else 1f
                else -> 1f
            }
        }

        // Max displayable OKLab chroma used to normalize the muted-weight (so "vivid" is
        // measured consistently in OKLab rather than HSL).
        val okChromaMax = 0.20f

        for (i in pixels.indices) {
            val c = pixels[i]
            val r8 = Color.red(c); val g8 = Color.green(c); val b8 = Color.blue(c)
            val rl = srgbToLinear(r8 / 255f)
            val gl = srgbToLinear(g8 / 255f)
            val bl = srgbToLinear(b8 / 255f)
            okLabFromLinear(rl, gl, bl, ok)
            val a0 = ok[1]; val b0 = ok[2]
            val chroma = sqrt(a0 * a0 + b0 * b0)
            val hue = atan2(b0, a0)

            // SATURATION IS VIBRANCE-LIKE (pro rule: prefer vibrance over global saturation).
            // Even the "Saturation" decision renders muted-first and vivid-capped: already-vivid
            // pixels (neon greens, saturated sky) receive almost nothing, muted colors receive
            // the lift. Flat multiplies are what turn foliage neon — never done here.
            // P2 NEGATIVE MAGNITUDES (background de-emphasis): the muted-first weight runs in
            // REVERSE — desaturating the already-muted toward gray looks like decay, so the
            // little color they have is protected; the vivid-background recession carries the
            // de-emphasis instead (which is what visually separates a busy bg from a subject).
            var satBoost = 1f
            for (dec in satDecs) {
                val w = maskWeight(dec.regionMask, i % width, i / width)
                if (w <= 0f) continue
                val mutedWeight = if (dec.magnitude >= 0f)
                    (1f - (chroma / okChromaMax).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                else
                    (chroma / okChromaMax).coerceIn(0f, 1f)
                satBoost *= (1f + dec.magnitude * w * mutedWeight)
            }
            var vibBoost = 1f
            for (dec in vibDecs) {
                val x = i % width; val y = i / width
                val w = maskWeight(dec.regionMask, x, y)
                val aIdx = if (aw > 0 && ah > 0) (y * scaleY).toInt().coerceIn(0, ah - 1) * aw + (x * scaleX).toInt().coerceIn(0, aw - 1) else -1
                val skinProtect = if (aIdx >= 0) (1f - analysis!!.skinMask[aIdx]).coerceIn(0f, 1f) else 1f
                val mutedWeight = if (dec.magnitude >= 0f)
                    (1f - (chroma / okChromaMax).coerceIn(0f, 1f)).coerceIn(0f, 1f)
                else
                    (chroma / okChromaMax).coerceIn(0f, 1f)
                vibBoost *= (1f + dec.magnitude * w * skinProtect * mutedWeight)
            }

            val boost = satBoost * vibBoost
            // Leave untouched regions pixel-identical: even a nominal OKLab
            // round-trip can shift 8-bit channel values through quantization.
            if (abs(boost - 1f) < 1e-7f) continue
            // P2: boost may now be < 1 (de-emphasis). Never let chroma go fully negative;
            // a bg recession that collapses color to gray would read as a defect.
            if (boost != 1f && chroma > 0f) {
                val newChroma = if (boost > 1f)
                    min(chroma * boost, okChromaMax * 1.4f)
                else
                    (chroma * boost).coerceIn(0f, chroma)
                ok[1] = newChroma * cos(hue)
                ok[2] = newChroma * sin(hue)
            }
            val rgb = okLabToLinear(ok)
            val nr = (linearToSrgb(rgb[0]) * 255f).toInt().coerceIn(0, 255)
            val ng = (linearToSrgb(rgb[1]) * 255f).toInt().coerceIn(0, 255)
            val nb = (linearToSrgb(rgb[2]) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(nr, ng, nb)
        }
    }

    // ---- P3 SPLIT-TONE (senior-photographer zone grading) ----
    // The classic print look: a whisper of cool in the shadows, a whisper of warm in the
    // highlights. Applied in OKLab where the b-axis is the blue-yellow opponent: shadows get
    // a small -b (toward blue), highlights a small +b (toward yellow warmth). The chroma
    // offset is added, not multiplied — the zone weight (shadowMask/brightMask) and the
    // decision magnitude bound it; skin pixels are excluded (skin never gets a grade).
    // Capped so the offset can never push chroma past displayable range.
    internal fun applySplitTone(pixels: IntArray, width: Int, height: Int, plan: EditPlan, analysis: AnalysisResult?) {
        var shadowMag = 0f
        var highlightMag = 0f
        for (dec in plan.decisions) {
            if (dec.parameter == "SplitToneShadows") shadowMag += dec.magnitude
            if (dec.parameter == "SplitToneHighlights") highlightMag += dec.magnitude
        }
        if (shadowMag == 0f && highlightMag == 0f) return
        shadowMag = shadowMag.coerceIn(0f, 0.06f)
        highlightMag = highlightMag.coerceIn(0f, 0.06f)

        val aw = analysis?.analysisWidth ?: 0
        val ah = analysis?.analysisHeight ?: 0
        val scaleX = if (aw > 0 && width > 0) aw.toFloat() / width else 0f
        val scaleY = if (ah > 0 && height > 0) ah.toFloat() / height else 0f
        val ok = FloatArray(3)
        // Max displayable OKLab chroma for the offset scale (kept consistent with applyOklabColor).
        val okChromaMax = 0.20f

        for (i in pixels.indices) {
            var zoneWeight = 0f
            var mag = 0f
            if (aw > 0 && ah > 0 && width > 0) {
                val px = i % width
                val py = i / width
                val ax = (px * scaleX).toInt().coerceIn(0, aw - 1)
                val ay = (py * scaleY).toInt().coerceIn(0, ah - 1)
                val aIdx = ay * aw + ax
                // Skin never gets a grade — the zone masks are vetoed by skin presence.
                if (analysis!!.skinMask[aIdx] > 0.3f) continue
                if (shadowMag > 0f) {
                    val w = analysis.shadowMask[aIdx]
                    // Shadows are COOL: negative offset on the b (blue-yellow) axis.
                    if (w > zoneWeight) { zoneWeight = w; mag = -shadowMag }
                }
                if (highlightMag > 0f) {
                    val w = analysis.brightMask[aIdx]
                    // Highlights are WARM: positive offset on the b axis. A pixel in both
                    // zones (rare, transitional) takes whichever zone weight is stronger.
                    if (w > zoneWeight) { zoneWeight = w; mag = highlightMag }
                }
                if (zoneWeight <= 0f) continue
            } else continue

            val c = pixels[i]
            val rl = srgbToLinear(Color.red(c) / 255f)
            val gl = srgbToLinear(Color.green(c) / 255f)
            val bl = srgbToLinear(Color.blue(c) / 255f)
            okLabFromLinear(rl, gl, bl, ok)
            // Offset on the b (blue-yellow) axis, scaled by zone weight and magnitude.
            val offset = mag * zoneWeight * okChromaMax
            ok[2] = (ok[2] + offset).coerceIn(-okChromaMax * 1.4f, okChromaMax * 1.4f)
            val rgb = okLabToLinear(ok)
            val nr = (linearToSrgb(rgb[0]) * 255f).toInt().coerceIn(0, 255)
            val ng = (linearToSrgb(rgb[1]) * 255f).toInt().coerceIn(0, 255)
            val nb = (linearToSrgb(rgb[2]) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(nr, ng, nb)
        }
    }

    // ---- P4 HUE-BAND REFINEMENT (sky / foliage measured hue repair) ----
    // A measured, coherent hue band that drifted from its natural anchor (cyan-contaminated
    // phone sky, sodium-yellowed canopy) is rotated TOWARD the anchor in OKLab — a nudge of
    // at most ±6° scaled by the region mask. Never fires on unmeasured/scattered bands (the
    // CDE gates that) and skin is excluded. Rotation preserves chroma magnitude exactly.
    internal fun applyHueRefine(pixels: IntArray, width: Int, height: Int, plan: EditPlan, analysis: AnalysisResult?) {
        var skyDelta = 0f
        var vegDelta = 0f
        for (dec in plan.decisions) {
            if (dec.parameter == "SkyHue") skyDelta += dec.magnitude
            if (dec.parameter == "VegHue") vegDelta += dec.magnitude
        }
        if (skyDelta == 0f && vegDelta == 0f) return

        val aw = analysis?.analysisWidth ?: 0
        val ah = analysis?.analysisHeight ?: 0
        if (aw <= 0 || ah <= 0 || width <= 0) return
        val scaleX = aw.toFloat() / width
        val scaleY = ah.toFloat() / height
        val ok = FloatArray(3)

        for (i in pixels.indices) {
            val px = i % width
            val py = i / width
            val ax = (px * scaleX).toInt().coerceIn(0, aw - 1)
            val ay = (py * scaleY).toInt().coerceIn(0, ah - 1)
            val aIdx = ay * aw + ax
            if (analysis!!.skinMask[aIdx] > 0.3f) continue

            val skyW = analysis.skyMask[aIdx]
            val vegW = analysis.vegMask[aIdx]
            val rotation = skyDelta * skyW + vegDelta * vegW
            if (abs(rotation) < 0.05f) continue

            val c = pixels[i]
            val rl = srgbToLinear(Color.red(c) / 255f)
            val gl = srgbToLinear(Color.green(c) / 255f)
            val bl = srgbToLinear(Color.blue(c) / 255f)
            okLabFromLinear(rl, gl, bl, ok)
            val chroma = sqrt(ok[1] * ok[1] + ok[2] * ok[2])
            if (chroma < 0.01f) continue   // achromatic pixels have no hue to rotate
            val hue = atan2(ok[2], ok[1])
            val newHue = hue + Math.toRadians(rotation.toDouble()).toFloat()
            ok[1] = chroma * cos(newHue)
            ok[2] = chroma * sin(newHue)
            val rgb = okLabToLinear(ok)
            val nr = (linearToSrgb(rgb[0]) * 255f).toInt().coerceIn(0, 255)
            val ng = (linearToSrgb(rgb[1]) * 255f).toInt().coerceIn(0, 255)
            val nb = (linearToSrgb(rgb[2]) * 255f).toInt().coerceIn(0, 255)
            pixels[i] = Color.rgb(nr, ng, nb)
        }
    }

    // Constant-memory representative sample used as a last-line output safeguard.
    private fun samplePixels(pixels: IntArray, width: Int, height: Int, sampleW: Int, sampleH: Int): IntArray {
        val sampled = IntArray(sampleW * sampleH)
        for (y in 0 until sampleH) for (x in 0 until sampleW) {
            val sx = (x.toLong() * width / sampleW).toInt().coerceIn(0, width - 1)
            val sy = (y.toLong() * height / sampleH).toInt().coerceIn(0, height - 1)
            sampled[y * sampleW + x] = pixels[sy * width + sx]
        }
        return sampled
    }

    private fun applyColorPipeline(
        pixels: IntArray, width: Int, height: Int, plan: EditPlan, analysis: AnalysisResult
    ) {
        applyWhiteBalance(pixels, width, height, plan, analysis)
        applyOklabColor(pixels, width, height, plan, analysis)
        applySplitTone(pixels, width, height, plan, analysis)
        applyHueRefine(pixels, width, height, plan, analysis)
    }

    fun process(input: Bitmap, analysis: AnalysisResult): RenderResult {
        val startTime = System.currentTimeMillis()
        var plan = ContextualDecisionEngine.evaluate(analysis)
        // Re-analysis (iterate-to-converge) may produce a fresher picture; effectiveAnalysis is
        // what all downstream mask lookups and the final render use.
        var effectiveAnalysis = analysis

        // A balanced, clean photograph should be bit-for-bit unchanged.
        if (plan.decisions.isEmpty() && analysis.signalQuality.noiseConfidence < 6f) {
            return RenderResult(input.copy(Bitmap.Config.ARGB_8888, false),
                ProcessingStats(computeTimeMs = System.currentTimeMillis() - startTime), plan, analysis)
        }

        val width = input.width
        val height = input.height
        val pixels = IntArray(width * height)
        input.getPixels(pixels, 0, width, 0, 0, width, height)
        val verifyW = min(96, width)
        val verifyH = min(96, height)
        val sourceSample = samplePixels(pixels, width, height, verifyW, verifyH)

        val stagesApplied = mutableSetOf<String>()
        if (plan.decisions.isNotEmpty()) stagesApplied.add("Adaptive Adjustments")

        // 2. Frequency separation setup
        val clarityDecisions = plan.decisions.filter { it.parameter == "Clarity" }
        val textureDecisions = plan.decisions.filter { it.parameter == "Texture" }
        var hasDetailEdits = clarityDecisions.isNotEmpty() || textureDecisions.isNotEmpty()
        
        var midFreq = FloatArray(0)
        var highFreq = FloatArray(0)
        
        if (hasDetailEdits) {
            var grayImg = GrayF32(width, height)
            for (i in 0 until width * height) {
                val c = pixels[i]
                val luma = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f) / 255f
                grayImg.data[i] = luma
            }
            
            var blurredMid = GrayF32(width, height)
            val midRadius = max(3, width / 100)
            BlurImageOps.mean(grayImg, blurredMid, midRadius, null, null)
            var blurredLow = GrayF32(width, height)
            val lowRadius = midRadius * 3
            BlurImageOps.mean(blurredMid, blurredLow, lowRadius, null, null)
            
            midFreq = FloatArray(width * height)
            highFreq = FloatArray(width * height)
            for (i in 0 until width * height) {
                val bMid = blurredMid.data[i]
                val bLow = blurredLow.data[i]
                midFreq[i] = bMid - bLow
                highFreq[i] = grayImg.data[i] - bMid
            }
        }

        // STAGE C — Luma noise reduction BEFORE detail, applied to EVERY noisy frame (even when
        // there are no detail edits). When detail edits exist we attenuate the high-frequency
        // band so the later Texture/Clarity pass never amplifies sensor noise; otherwise we
        // smooth the luma channel directly.
        val noiseConf = analysis.signalQuality.noiseConfidence
        if (noiseConf > 8f) {
            val aW = analysis.analysisWidth
            val aH = analysis.analysisHeight
            val sX = aW.toFloat() / width
            val sY = aH.toFloat() / height
            if (hasDetailEdits) {
                // Render-time high-frequency attenuation in applyPlanToPixels.
                // Do not mutate the reference band or noise reduction will cancel out.
                stagesApplied.add("Smart NR")
            } else {
                // Direct spatial average of luma for noisy flat frames (no detail pipeline).
                val n = FloatArray(width * height)
                for (i in 0 until width * height) {
                    val c = pixels[i]
                    n[i] = (Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f)
                }
                val out = FloatArray(width * height)
                for (i in 0 until width * height) {
                    val x = i % width; val y = i / width
                    val ax = (x * sX).toInt().coerceIn(0, aW - 1)
                    val ay = (y * sY).toInt().coerceIn(0, aH - 1)
                    val localNoise = analysis.noiseMask[ay * aW + ax]
                    var acc = 0f; var cnt = 0
                    for (ny in -1..1) for (nx in -1..1) {
                        val xx = (x + nx).coerceIn(0, width - 1)
                        val yy = (y + ny).coerceIn(0, height - 1)
                        acc += n[yy * width + xx]; cnt++
                    }
                    val flat = acc / cnt
                    val suppress = ProcessingSafetyMath.noiseSuppression(noiseConf, localNoise)
                    out[i] = n[i] * (1f - suppress) + flat * suppress
                }
                val hsl = FloatArray(3); val hslOut = FloatArray(3)
                for (i in 0 until width * height) {
                    val c = pixels[i]
                    val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                    ColorUtils.RGBToHSL(r, g, b, hsl)
                    // Preserve existing saturation and HSL lightness; transfer only
                    // the RGB-luma denoising DELTA to avoid desaturating vivid colors.
                    hslOut[0] = hsl[0]; hslOut[1] = hsl[1]
                    hslOut[2] = ProcessingSafetyMath.denoisedLightness(hsl[2], n[i], out[i])
                    // A zero luma delta is not permission to requantize RGB channels.
                    if (kotlin.math.abs(hslOut[2] - hsl[2]) > 1e-7f) {
                        pixels[i] = ColorUtils.HSLToColor(hslOut)
                    }
                }
                stagesApplied.add("Smart NR")
            }
        }

        // Chroma noise reduction (before detail): cut saturation noise in smooth, noisy regions.
        applyChromaNoiseReduction(pixels, width, height, analysis, stagesApplied)

        // Check every edited frame: white balance, vibrance and regional grades can
        // damage color even when no global tonal decision is present.
        if (plan.decisions.isNotEmpty()) {
            val testScale = max(1, width / 64)
            val tw = width / testScale
            val th = height / testScale
            
            val testPixels = IntArray(tw * th)
            var midFreqTest = FloatArray(0)
            var highFreqTest = FloatArray(0)
            
            for (ty in 0 until th) {
                for (tx in 0 until tw) {
                    val origIdx = (ty * testScale) * width + (tx * testScale)
                    testPixels[ty * tw + tx] = pixels[origIdx]
                }
            }
            
            if (hasDetailEdits) {
                midFreqTest = FloatArray(tw * th)
                highFreqTest = FloatArray(tw * th)
                for (ty in 0 until th) {
                    for (tx in 0 until tw) {
                        val origIdx = (ty * testScale) * width + (tx * testScale)
                        val tIdx = ty * tw + tx
                        midFreqTest[tIdx] = midFreq[origIdx]
                        highFreqTest[tIdx] = highFreq[origIdx]
                    }
                }
            }
            
            // 3b. Proportional candidate rejection. Evaluate the candidate again after
            // EACH reduction. A previous attempt used a cumulative ledger that could
            // exhaust the allowed reduction while leaving a harmful edit intact.
            // This guard reduces the current strength, not an obsolete initial plan.
            var effectivePlan = plan
            val guardTonalParams = setOf("Exposure", "Shadows", "Highlights", "Contrast", "Whites",
                "Blacks", "Vignette", "Clarity", "Texture", "Dehaze")
            val guardColorParams = setOf("WhiteBalance", "WhiteBalanceTint", "Saturation", "Vibrance",
                "SplitToneShadows", "SplitToneHighlights", "SkyHue", "VegHue", "CA")
            fun scaledDown(current: EditPlan, harm: Float, colorHarm: Float): EditPlan {
                val reduced = current.decisions.mapNotNull { decision ->
                    val severity = maxOf(
                        if (decision.parameter in guardTonalParams) harm else 0f,
                        if (decision.parameter in guardColorParams) colorHarm else 0f
                    )
                    if (severity <= 0.1f) return@mapNotNull decision
                    val next = ProcessingSafetyMath.attenuate(decision.magnitude, severity)
                    if (abs(next) <= 0.01f) null else decision.copy(magnitude = next)
                }
                return current.copy(decisions = reduced)
            }
            fun previewCandidate(): IntArray {
                val candidate = testPixels.copyOf()
                applyPlanToPixels(candidate, tw, th, effectiveAnalysis, effectivePlan,
                    hasDetailEdits, midFreqTest, highFreqTest)
                // Evaluate exactly the same post-tonal stages as the final renderer.
                // Without these, the color gate approves an incomplete image.
                applyColorPipeline(candidate, tw, th, effectivePlan, effectiveAnalysis)
                return candidate
            }
            for (attempt in 0 until 6) {
                val candidate = previewCandidate()
                val harm = evaluateQualityGate(testPixels, candidate, tw, th)
                val colorHarm = evaluateColorGate(testPixels, candidate, tw, th)
                if (harm <= 0.1f && colorHarm <= 0.1f) break
                val reduced = scaledDown(effectivePlan, harm, colorHarm)
                if (reduced == effectivePlan) break
                effectivePlan = reduced
            }

            // 3c. ITERATE TO CONVERGENCE (Stage F): render a representative preview, RE-ANALYZE it,
            // and derive a residual plan from the corrected image. Residual magnitudes COMPOUND
            // into the effective plan (render always starts from the ORIGINAL pixels), damped per
            // iteration so successive substitution cannot oscillate. Stop when the engine's own
            // defect judgment no longer asks for a tonal/photographic correction — there is NO
            // fixed mid-gray / histogram target to push toward.
            // The preview PRESERVES ASPECT RATIO so its re-analysis (vignette symmetry, gradients,
            // masks) measures the same geometry as the full frame.
            val convScale = 128f / max(width, height)
            val convW = (width * convScale).toInt().coerceIn(1, width)
            val convH = (height * convScale).toInt().coerceIn(1, height)
            val convPixels = IntArray(convW * convH)
            val scx = width.toFloat() / convW
            val scy = height.toFloat() / convH
            for (cy in 0 until convH) for (cx in 0 until convW) {
                val px = (cx * scx).toInt().coerceIn(0, width - 1)
                val py = (cy * scy).toInt().coerceIn(0, height - 1)
                convPixels[cy * convW + cx] = pixels[py * width + px]
            }
            val tonalParams = listOf("Exposure", "Shadows", "Highlights", "Contrast", "Blacks", "Whites")
            // Renderer caps per tonal parameter — compounded magnitudes may never exceed them.
            val tonalCap = mapOf(
                "Exposure" to 0.25f, "Contrast" to 0.35f, "Shadows" to 0.25f,
                "Highlights" to 0.25f, "Blacks" to 0.12f, "Whites" to 0.08f
            )
            fun compoundDecisions(
                base: List<EditDecision>, residual: List<EditDecision>, damp: Float,
                stillFlat: Boolean
            ): List<EditDecision> {
                // A fresh plan contains DEFECT corrections (under-correction residuals, which
                // must compound) plus the standing ENHANCEMENT BASELINE (contrast/vibrance/
                // clarity computed from current state, re-emitted on every evaluation).
                // Compounding the baseline multiplies the profile's committed enhancement by
                // the iteration count — LOW_LIGHT's "minimal" contrast 0.04 grew to the 0.12
                // cap, amplifying noise, exactly what the LOW_LIGHT philosophy forbids. So:
                // compound defect residuals only; the baseline keeps its first-plan committed
                // magnitudes. Contrast is dual-purpose (corrective when still flat, baseline
                // otherwise) — its residual compounds only while the corrected frame is STILL
                // genuinely flat (the CDE's flatLowContrast gate).
                val merged = base.associateBy { it.parameter to it.regionMask }
                    .toMutableMap()
                for (dec in residual) {
                    if (dec.parameter == "Contrast" && !stillFlat) continue  // baseline, not residual
                    // P2: background de-emphasis is hierarchy BASELINE (a design commitment,
                    // not a defect residual) — its magnitude never compounds across iterations.
                    if (dec.regionMask == "Background") continue
                    val key = dec.parameter to dec.regionMask
                    val cap = tonalCap[dec.parameter] ?: continue
                    val existing = merged[key]
                    val add = dec.magnitude * damp
                    val compounded = when {
                        existing == null -> add.coerceIn(-cap, cap)
                        else -> (existing.magnitude + add).coerceIn(-cap, cap)
                    }
                    merged[key] = (existing ?: dec).copy(
                        magnitude = compounded,
                        confidence = maxOf(existing?.confidence ?: 0f, dec.confidence)
                    )
                }
                return merged.values.filter { abs(it.magnitude) > 0.01f }
            }
            repeat(3) { iter ->
                val rendered = convPixels.copyOf()
                applyPlanToPixels(
                    rendered, convW, convH, effectiveAnalysis, effectivePlan,
                    false, FloatArray(0), FloatArray(0)
                )
                // Residual decisions must inspect the same color pipeline as the output.
                applyColorPipeline(rendered, convW, convH, effectivePlan, effectiveAnalysis)
                val renderedBitmap = Bitmap.createBitmap(convW, convH, Bitmap.Config.ARGB_8888)
                renderedBitmap.setPixels(rendered, 0, convW, 0, 0, convW, convH)
                val freshAnalysis = ImageAnalyzer.analyze(renderedBitmap)
                renderedBitmap.recycle()
                val freshPlan = ContextualDecisionEngine.evaluate(freshAnalysis)
                val freshTonal = freshPlan.decisions.filter { it.parameter in tonalParams }
                // Stop when the corrected image no longer presents a photographic defect the
                // engine would correct (maximum-restraint convergence), or nothing new emerges.
                if (freshTonal.isEmpty()) return@repeat
                // Corrective-contrast gate (mirrors CDE flatLowContrast): iqr < 0.16 with real occupancy.
                val stillFlat = freshAnalysis.tonalStructure.iqr < 0.16f &&
                    freshAnalysis.tonalStructure.occupancy > 0.25f
                val damp = 0.7f.pow(iter)   // iterations 0,1,2 -> 1.0, 0.7, 0.49
                val compounded = compoundDecisions(effectivePlan.decisions, freshTonal, damp, stillFlat)
                if (compounded == effectivePlan.decisions) return@repeat
                effectiveAnalysis = freshAnalysis
                effectivePlan = EditPlan(compounded, effectivePlan.profileName, effectivePlan.profileReason)
            }

            // 3d. The converged plan changed since the initial guard. Re-evaluate it
            // against the same original pre-grade pixels, including post-color passes.
            for (attempt in 0 until 6) {
                val candidate = previewCandidate()
                val harm = evaluateQualityGate(testPixels, candidate, tw, th)
                val colorHarm = evaluateColorGate(testPixels, candidate, tw, th)
                if (harm <= 0.1f && colorHarm <= 0.1f) break
                val reduced = scaledDown(effectivePlan, harm, colorHarm)
                if (reduced == effectivePlan) break
                effectivePlan = reduced
            }
            plan = effectivePlan
        }

        // 4. Final Processing — in-place: pixels is overwritten with processed output.
        // Uses the converged plan AND the re-analyzed masks from effectiveAnalysis.
        applyPlanToPixels(
            pixels, width, height, effectiveAnalysis, plan,
            hasDetailEdits, midFreq, highFreq
        )

        // Full preview safety gate and final image use the same post-tonal stages.
        applyColorPipeline(pixels, width, height, plan, effectiveAnalysis)

        // Preview masks/nearest-neighbour samples are approximations. If the *actual*
        // full-resolution output is catastrophically different, preserve the original
        // rather than silently saving damaged colors or blown highlights.
        val finalSample = samplePixels(pixels, width, height, verifyW, verifyH)
        val outputHarm = evaluateQualityGate(sourceSample, finalSample, verifyW, verifyH)
        val outputColorHarm = evaluateColorGate(sourceSample, finalSample, verifyW, verifyH)
        if (outputHarm >= 0.65f || outputColorHarm >= 0.65f) {
            return RenderResult(
                input.copy(Bitmap.Config.ARGB_8888, false),
                ProcessingStats(computeTimeMs = System.currentTimeMillis() - startTime,
                    stagesApplied = setOf("Fidelity fallback")),
                EditPlan(emptyList(), plan.profileName, plan.profileReason), analysis
            )
        }

        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        resultBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val time = System.currentTimeMillis() - startTime
        return RenderResult(
            resultBitmap,
            ProcessingStats(computeTimeMs = time, stagesApplied = stagesApplied),
            appliedPlan = plan,
            appliedAnalysis = effectiveAnalysis
        )
    }
}
