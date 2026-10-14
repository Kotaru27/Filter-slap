package com.example.engine.v10

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Photographic tone-only gamut mapping for sRGB photographs. A brightness edit
 * modifies linear-light luminance, without independently amplifying colored
 * channels. Preserve source hue direction and compress chroma only as required
 * to remain inside sRGB. No material, object, or image-specific special cases.
 *
 * The small transfer tables avoid repeated pow() in the full-resolution renderer.
 * Candidate evaluation uses the more expensive OKLab space on the preview only.
 */
object ColorFidelity {
    private const val SIZE = 4096
    private const val YR = .2126f
    private const val YG = .7152f
    private const val YB = .0722f
    private val decode = FloatArray(SIZE + 1) { i ->
        val x = i.toFloat() / SIZE
        if (x <= .04045f) x / 12.92f else ((x + .055f) / 1.055f).pow(2.4f)
    }
    private val encode = FloatArray(SIZE + 1) { i ->
        val x = i.toFloat() / SIZE
        if (x <= .0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - .055f
    }
    private fun table(value: Float, values: FloatArray): Float {
        val x = value.coerceIn(0f, 1f) * SIZE
        val low = x.toInt().coerceAtMost(SIZE-1)
        return values[low] + (values[low+1] - values[low]) * (x-low)
    }
    fun linear(channel: Float): Float = table(channel, decode)
    fun encoded(channel: Float): Float = table(channel, encode)
    private fun luma(r: Float, g: Float, b: Float) = YR*r+YG*g+YB*b

    /**
     * Keeps candidate linear-light luminance while taking hue and chroma from
     * the source. Uniform chroma scaling preserves the source color direction;
     * mapping around neutral prevents independent-channel clipping and halos.
     * `dest` is reusable scratch owned by the render loop, never reallocated.
     */
    fun stabilizeToneOnly(originalR: Float, originalG: Float, originalB: Float, dest: FloatArray) {
        val sr = linear(originalR); val sg = linear(originalG); val sb = linear(originalB)
        val before = luma(sr,sg,sb)
        val after = luma(linear(dest[0]), linear(dest[1]), linear(dest[2])).coerceIn(0f,1f)
        if (abs(after-before) < .000005f) {
            dest[0]=originalR; dest[1]=originalG; dest[2]=originalB
            return
        }
        val dr=sr-before; val dg=sg-before; val db=sb-before
        var saturationScale=1f
        if(dr>0f) saturationScale=min(saturationScale,(1f-after)/dr)
        else if(dr<0f) saturationScale=min(saturationScale,after/-dr)
        if(dg>0f) saturationScale=min(saturationScale,(1f-after)/dg)
        else if(dg<0f) saturationScale=min(saturationScale,after/-dg)
        if(db>0f) saturationScale=min(saturationScale,(1f-after)/db)
        else if(db<0f) saturationScale=min(saturationScale,after/-db)
        // Colored source pixels may lose some chroma near the gamut surface,
        // but their hue direction is not rotated to an arbitrary channel.
        val a=saturationScale.coerceIn(0f,1f)
        dest[0]=encoded(after+a*dr)
        dest[1]=encoded(after+a*dg)
        dest[2]=encoded(after+a*db)
    }

    /** sRGB encoded channels to perceptual OKLab, returning into caller scratch. */
    internal fun okLab(r: Float, g: Float, b: Float, out: FloatArray) {
        val lr=linear(r);val lg=linear(g);val lb=linear(b)
        val l=Math.cbrt((.4122214708*lr+.5363325363*lg+.0514459929*lb).toDouble())
        val m=Math.cbrt((.2119034982*lr+.6806995451*lg+.1073969566*lb).toDouble())
        val s=Math.cbrt((.0883024619*lr+.2817188376*lg+.6299787005*lb).toDouble())
        out[0]=(.2104542553*l+.7936177850*m-.0040720468*s).toFloat()
        out[1]=(1.9779984951*l-2.4285922050*m+.4505937099*s).toFloat()
        out[2]=(.0259040371*l+.7827717662*m-.8086757660*s).toFloat()
    }

    /** Evaluates corresponding pixels without tuning for any specific photograph. */
    fun evaluate(original:IntArray, edited:IntArray, stride:Int=4):ColorAssessment {
        require(original.size==edited.size && stride>0)
        val a=FloatArray(3);val b=FloatArray(3)
        var count=0;var chromaDrift=0.0;var overshoot=0.0;var hueError=0.0
        var neutrals=0;var casts=0.0;var severeChromaLoss=0.0
        for(i in original.indices step stride){
            val s=original[i]; val t=edited[i]
            okLab((s ushr 16 and 255)/255f,(s ushr 8 and 255)/255f,(s and 255)/255f,a)
            okLab((t ushr 16 and 255)/255f,(t ushr 8 and 255)/255f,(t and 255)/255f,b)
            val ca=sqrt(a[1]*a[1]+a[2]*a[2]); val cb=sqrt(b[1]*b[1]+b[2]*b[2])
            chromaDrift+=abs(ca-cb)
            // Large, unintended desaturation can wash out source material even
            // with unchanged hue and no new clipped pixels. Do not penalize
            // small desaturation or highlight gamut compression near white.
            if(ca>.05f && a[0] in .15f.. .90f && b[0] in .15f.. .94f)
                severeChromaLoss+=max(0f,ca*.60f-cb-.004f)
            if(ca>.028f){
                overshoot+=max(0f,cb-ca*1.10f-.006f)
                if(cb>.028f){
                    val dot=(a[1]*b[1]+a[2]*b[2])/(ca*cb)
                    // Penalize only substantial, uncontrolled rotations.
                    hueError+=max(0f,.975f-dot.coerceIn(-1f,1f))
                }
            } else if(ca<.010f && a[0]>.17f && a[0]<.88f){
                neutrals++; casts+=max(0f,cb-.012f)
            }
            count++
        }
        val n=max(1,count)
        return ColorAssessment((chromaDrift/n).toFloat(),(overshoot/n).toFloat(),
            (hueError/n).toFloat(),if(neutrals>0)(casts/neutrals).toFloat()else 0f,n,
            (severeChromaLoss/n).toFloat())
    }
}

data class ColorAssessment(
    val meanChromaDelta: Float,
    val excessChroma: Float,
    val hueInstability: Float,
    val neutralCast: Float,
    val evaluatedPixels: Int,
    val severeChromaLoss: Float = 0f
) {
    /** Cost in the same general units as the engineering correction score. */
    val penalty: Float get() = 500f*excessChroma + 90f*hueInstability + 180f*neutralCast +
        220f*severeChromaLoss
}