package com.example.engine.v7

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow

/** Pure pixel operations shared by Android rendering and host regression tests. */
object EnhancementMath {
    fun luma(r: Float, g: Float, b: Float) = .2126f * r + .7152f * g + .0722f * b
    fun luma(pixel: Int) = luma((pixel ushr 16 and 255) / 255f,
        (pixel ushr 8 and 255) / 255f, (pixel and 255) / 255f)

    fun smooth(low: Float, high: Float, value: Float): Float {
        val t = ((value - low) / (high - low)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** [channel, blue, green, red], identical to the pinned paired-sRGB LUT. */
    fun lookup(lut: FloatArray, r: Float, g: Float, b: Float, dest: FloatArray) {
        val rr = r.coerceIn(0f, 1f) * 32f
        val gg = g.coerceIn(0f, 1f) * 32f
        val bb = b.coerceIn(0f, 1f) * 32f
        val ri = floor(rr).toInt().coerceAtMost(31)
        val gi = floor(gg).toInt().coerceAtMost(31)
        val bi = floor(bb).toInt().coerceAtMost(31)
        val rf = rr - ri; val gf = gg - gi; val bf = bb - bi
        val p = (bi * 33 + gi) * 33 + ri
        for (c in 0..2) {
            val j = c * 35937 + p
            val z0 = (lut[j] * (1f-rf) + lut[j+1] * rf) * (1f-gf) +
                (lut[j+33] * (1f-rf) + lut[j+34] * rf) * gf
            val z1 = (lut[j+1089] * (1f-rf) + lut[j+1090] * rf) * (1f-gf) +
                (lut[j+1122] * (1f-rf) + lut[j+1123] * rf) * gf
            dest[c] = z0 * (1f-bf) + z1 * bf
        }
    }

    /** Limit an RGB delta with ONE scale, preserving its direction and channel relationships.
     * Existing extremes can stay extreme; previously unclipped channels keep headroom.
     * This is a bounded gamut mapping, not three independent hard channel clamps. */
    fun boundedDelta(r: Float, g: Float, b: Float, target: FloatArray) {
        var scale = 1f
        for (c in 0..2) {
            val source = when (c) { 0 -> r; 1 -> g; else -> b }
            val delta = target[c] - source
            val upper = if (source > 250f/255f) 1f else 250f/255f
            val lower = if (source < 4f/255f) 0f else 4f/255f
            if (delta > 0f) scale = min(scale, ((upper-source) / delta).coerceAtLeast(0f))
            if (delta < 0f) scale = min(scale, ((lower-source) / delta).coerceAtLeast(0f))
        }
        target[0] = (r + (target[0]-r) * scale).coerceIn(0f, 1f)
        target[1] = (g + (target[1]-g) * scale).coerceIn(0f, 1f)
        target[2] = (b + (target[2]-b) * scale).coerceIn(0f, 1f)
    }

    fun edit(pixel: Int, foreground: Float, recipe: EditRecipe,
             neighbourhoodLuma: Float, dest: FloatArray): Int {
        val r = (pixel ushr 16 and 255) / 255f
        val g = (pixel ushr 8 and 255) / 255f
        val b = (pixel and 255) / 255f
        val y = luma(r, g, b)
        if (recipe.isIdentity) return pixel
        if (y <= .004f || y >= .998f) return pixel
        val fg = foreground.coerceIn(0f, 1f)
        val highlightProtection = 1f - smooth(.68f, .98f, y)
        val shadowProtection = smooth(.008f, .10f, y)
        // The learned LUT is a GLOBAL transform: the salient-object mask must not
        // weaken it elsewhere in the photograph. Only explicit regional tools
        // (exposure/contrast/detail) depend on foreground probabilities.
        recipe.lookup(r, g, b, dest)
        val modelY = luma(dest[0], dest[1], dest[2])
        val ratio = if (y > .001f) modelY / y else 1f
        val chroma = max(r, max(g,b)) - min(r, min(g,b))
        val colorStrength = .60f - .30f * smooth(.15f, .55f, chroma)
        val strength = recipe.modelStrength * highlightProtection *
            (if (modelY < y) shadowProtection else 1f)
        dest[0] = r + strength * ((r*ratio-r) + (dest[0]-r*ratio) * colorStrength)
        dest[1] = g + strength * ((g*ratio-g) + (dest[1]-g*ratio) * colorStrength)
        dest[2] = b + strength * ((b*ratio-b) + (dest[2]-b*ratio) * colorStrength)

        // Independent photographic corrections. Exposure is expressed in EV and
        // converted approximately into display-encoded sRGB; all channels share
        // the same tone multiplier to avoid inventing colored edges. The learned
        // global look is never permission for an unrelated local correction.
        dest[0] *= recipe.redGain
        dest[1] *= recipe.greenGain
        dest[2] *= recipe.blueGain
        val regionEv = recipe.exposureEv + recipe.subjectEv * fg +
            recipe.backgroundEv * (if(recipe.mask != null) 1f-fg else 1f)
        if (regionEv != 0f) {
            val gainEv = 2f.pow(regionEv / 2.2f)
            val exposureProtection = if(regionEv > 0f) highlightProtection else
                .65f + .35f * shadowProtection
            val scale = 1f + (gainEv-1f)*exposureProtection
            dest[0] *= scale; dest[1] *= scale; dest[2] *= scale
        }
        val backgroundWeight = if(recipe.mask!=null) 1f-fg else 0f
        val localContrast = (recipe.contrast + recipe.subjectContrast * fg +
            recipe.backgroundContrast * backgroundWeight).coerceIn(-.35f,.35f)
        if (localContrast != 0f) {
            val mid = luma(dest[0],dest[1],dest[2]).coerceIn(0f,1f)
            val u = mid-.5f
            val curve = localContrast * 4f * u * (.25f-u*u)
            val scale = (mid+curve)/mid.coerceAtLeast(.02f)
            dest[0] *= scale; dest[1] *= scale; dest[2] *= scale
        }

        // A continuous mid-shadow lift: black remains black, highlights receive almost none.
        val lift = (recipe.globalLift + recipe.subjectLift * fg) * sqrt(y) *
            (1f-y) * (1f-y) * shadowProtection
        val detailDifference = y - neighbourhoodLuma
        val detailWeight = (1f - smooth(.025f, .075f, abs(detailDifference))) *
            // Maintain measured fine-detail processing throughout the frame;
            // the mask can add emphasis but never switch background detail off.
            (if (recipe.mask != null) .40f+.60f*fg else .65f) * highlightProtection
        val detail = recipe.detailStrength * detailDifference * detailWeight
        val denoise = -detailDifference * recipe.noiseStrength *
            (1f-EnhancementMath.smooth(.012f,.045f,abs(detailDifference)))
        val toneDelta = lift + detail + denoise
        val gain = if (y > .001f) toneDelta / y else 0f
        dest[0] += r * gain; dest[1] += g * gain; dest[2] += b * gain
        boundedDelta(r, g, b, dest)
        val rr = (dest[0]*255f + .5f).toInt().coerceIn(0,255)
        val gg = (dest[1]*255f + .5f).toInt().coerceIn(0,255)
        val bb = (dest[2]*255f + .5f).toInt().coerceIn(0,255)
        return (pixel and -0x1000000) or (rr shl 16) or (gg shl 8) or bb
    }
}

/** Retains raw probability contrast; never stretches an uncertain flat map to a fake mask. */
class MaskField private constructor(private val values: FloatArray, private val guide: IntArray?, val width: Int,
    val height: Int, val coverage: Float, val certainty: Float) {
    fun at(x: Float, y: Float, sourceColor: Int? = null): Float {
        val px = (x * width - .5f).coerceIn(0f, (width-1).toFloat())
        val py = (y * height - .5f).coerceIn(0f, (height-1).toFloat())
        val ix = px.toInt(); val iy = py.toInt()
        val nx = min(ix+1,width-1); val ny = min(iy+1,height-1)
        val fx = px-ix; val fy = py-iy
        val i0=iy*width+ix;val i1=iy*width+nx;val i2=ny*width+ix;val i3=ny*width+nx
        if(guide!=null && sourceColor!=null &&
            maxOf(values[i0],values[i1],values[i2],values[i3])-minOf(values[i0],values[i1],values[i2],values[i3]) > .08f) {
            // Joint range-weighted upsampling only at mask boundaries. Avoid lighting halos
            // where an interpolated coarse mask crosses a strong source-color edge.
            fun rangeWeight(index: Int): Float {
                val p=guide[index]
                val r=(sourceColor ushr 16 and 255)-(p ushr 16 and 255)
                val g=(sourceColor ushr 8 and 255)-(p ushr 8 and 255)
                val b=(sourceColor and 255)-(p and 255)
                return 1f/(1f+(r*r+g*g+b*b)/1200f)
            }
            val w0=(1f-fx)*(1f-fy)*rangeWeight(i0);val w1=fx*(1f-fy)*rangeWeight(i1)
            val w2=(1f-fx)*fy*rangeWeight(i2);val w3=fx*fy*rangeWeight(i3)
            val total=w0+w1+w2+w3
            if(total > .000001f) return (values[i0]*w0+values[i1]*w1+values[i2]*w2+values[i3]*w3)/total
        }
        return (values[iy*width+ix]*(1f-fx)+values[iy*width+nx]*fx)*(1f-fy) +
            (values[ny*width+ix]*(1f-fx)+values[ny*width+nx]*fx)*fy
    }
    companion object {
        fun fromProbabilities(values: FloatArray, width: Int, height: Int, guide: IntArray? = null): MaskField? {
            require(width > 0 && height > 0 && values.size == width*height)
            require(guide==null || guide.size==values.size)
            if (values.any { !it.isFinite() || it < -.001f || it > 1.001f }) return null
            val coverage = values.count { it > .5f }.toFloat() / values.size
            val certainty = values.count { it < .1f || it > .9f }.toFloat() / values.size
            // These are conservative geometric validity checks, not calibrated correctness.
            if (coverage !in .025f.. .85f || certainty < .70f ||
                values.maxOrNull()!! < .85f || values.minOrNull()!! > .2f) return null
            return MaskField(values.copyOf(),guide?.copyOf(),width,height,coverage,certainty)
        }
    }
}

class EditRecipe internal constructor(lut: FloatArray?, val mask: MaskField?,
    val modelStrength: Float, val globalLift: Float, val subjectLift: Float,
    val detailStrength: Float, val sourceAspect: Float, val id: String,
    val modelFingerprint: String, val diagnostics: List<String>, val noiseStrength: Float = 0f,
    val exposureEv: Float = 0f, val subjectEv: Float = 0f, val backgroundEv: Float = 0f,
    val contrast: Float = 0f, val subjectContrast: Float = 0f,
    val redGain: Float = 1f, val greenGain: Float = 1f, val blueGain: Float = 1f,
    val strategy: String = "Legacy", val backgroundContrast: Float = 0f) {
    private val table = lut?.copyOf()
    val isIdentity get() = modelStrength == 0f && globalLift == 0f &&
        subjectLift == 0f && detailStrength == 0f && noiseStrength == 0f &&
        exposureEv == 0f && subjectEv == 0f && backgroundEv == 0f &&
        contrast == 0f && subjectContrast == 0f && backgroundContrast == 0f &&
        redGain == 1f && greenGain == 1f && blueGain == 1f
    internal fun lookup(r: Float, g: Float, b: Float, dest: FloatArray) {
        if (table == null || modelStrength == 0f) {
            dest[0]=r;dest[1]=g;dest[2]=b
        } else EnhancementMath.lookup(table,r,g,b,dest)
    }
    internal fun attenuated(factor: Float, reason: String) = EditRecipe(table,mask,
        modelStrength*factor,globalLift*factor,subjectLift*factor,detailStrength*factor,
        sourceAspect,id+"-${(factor*100).toInt()}",modelFingerprint,diagnostics+reason,noiseStrength*factor,
        exposureEv*factor,subjectEv*factor,backgroundEv*factor,contrast*factor,subjectContrast*factor,
        1f+(redGain-1f)*factor,1f+(greenGain-1f)*factor,1f+(blueGain-1f)*factor,
        strategy,backgroundContrast*factor)
}

/** Counts newly damaged samples; recovered pixels elsewhere cannot cancel these counts. */
class DamageAccumulator {
    private var count = 0L; private var clipped = 0L; private var crushed = 0L
    private var hueShifted = 0L; private var vividBoosted = 0L
    private var difference = 0.0
    fun add(original: Int, output: Int) {
        val r=original ushr 16 and 255;val g=original ushr 8 and 255;val b=original and 255
        val rr=output ushr 16 and 255;val gg=output ushr 8 and 255;val bb=output and 255
        count++
        difference += (abs(r-rr)+abs(g-gg)+abs(b-bb))/3.0
        if (maxOf(r,g,b) < 252 && maxOf(rr,gg,bb) >= 252) clipped++
        if (EnhancementMath.luma(original) >= 5f/255f && EnhancementMath.luma(output) < 5f/255f) crushed++
        val oldRange=maxOf(r,g,b)-minOf(r,g,b);val newRange=maxOf(rr,gg,bb)-minOf(rr,gg,bb)
        if (oldRange > 114 && newRange > oldRange+26) vividBoosted++
        if (oldRange > 40 && newRange > 40) {
            val a=(r-g).toFloat();val c=(r+g-2*b)/1.7320508f
            val aa=(rr-gg).toFloat();val cc=(rr+gg-2*bb)/1.7320508f
            val dot=a*aa+c*cc
            if (dot < .94f * sqrt((a*a+c*c)*(aa*aa+cc*cc))) hueShifted++
        }
    }
    fun report(): DamageReport {
        val n=count.coerceAtLeast(1).toFloat()
        return DamageReport((difference/n).toFloat(),clipped/n,crushed/n,hueShifted/n,vividBoosted/n,count)
    }
}
data class DamageReport(val meanChange: Float, val newlyClipped: Float, val newlyCrushed: Float,
    val hueShifted: Float, val vividBoosted: Float, val samples: Long) {
    val safe get() = samples > 0 && meanChange.isFinite() && meanChange <= 35f &&
        newlyClipped <= .003f && newlyCrushed <= .003f && hueShifted <= .04f && vividBoosted <= .04f
}

data class EnhancementOutcome(val status: String, val engine: String, val recipeId: String,
    val modelFingerprint: String, val modelStrength: Float, val maskUsed: Boolean,
    val damage: DamageReport, val reasons: List<String>) {
    fun summary(): String {
        val scope=when {
            status=="Original kept" -> "original preserved"
            modelStrength>0f && maskUsed -> "whole-scene grade + local refinements"
            modelStrength>0f -> "whole-scene grade"
            maskUsed -> "independent regional corrections"
            else -> "whole-scene corrections"
        }
        return "$status · $engine · $scope"
    }
}
