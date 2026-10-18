package com.example.engine.v9

import kotlin.math.max
import kotlin.math.min

/**
 * Small, native-resolution-independent, edge-aware illumination proposal.
 * Uses a self-guided (gray-level) guided filter on a bounded preview. It has
 * no category/scene/object/location branch. The field is rebuilt from the
 * source for export; no edited pixels or sample image are stored in a recipe.
 */
class SpatialIllumination private constructor(
    private val width: Int,
    private val height: Int,
    private val guide: FloatArray,
    private val base: FloatArray,
    private val confidence: FloatArray,
    val recoverableFraction: Float
) {
    fun liftAt(u: Float, v: Float, sourceY: Float, strength: Float): Float {
        if (strength <= 0f || sourceY <= .008f || sourceY >= .70f) return 0f
        val px=(u*width-.5f).coerceIn(0f,(width-1).toFloat())
        val py=(v*height-.5f).coerceIn(0f,(height-1).toFloat())
        val x=px.toInt(); val y=py.toInt()
        val xx=min(x+1,width-1); val yy=min(y+1,height-1)
        val fx=px-x; val fy=py-y
        val i0=y*width+x; val i1=y*width+xx
        val i2=yy*width+x; val i3=yy*width+xx
        // Hot pixel path: NO per-pixel arrays/collections/closures. The full
        // resolution renderer invokes this millions of times per photograph.
        val d0=sourceY-guide[i0]; val d1=sourceY-guide[i1]
        val d2=sourceY-guide[i2]; val d3=sourceY-guide[i3]
        val w0=(1f-fx)*(1f-fy)/(1f+90f*d0*d0)
        val w1=fx*(1f-fy)/(1f+90f*d1*d1)
        val w2=(1f-fx)*fy/(1f+90f*d2*d2)
        val w3=fx*fy/(1f+90f*d3*d3)
        val mass=w0+w1+w2+w3
        val estimate=w0*base[i0]+w1*base[i1]+w2*base[i2]+w3*base[i3]
        val reliability=w0*confidence[i0]+w1*confidence[i1]+w2*confidence[i2]+w3*confidence[i3]
        if(mass < 1e-7f) return 0f
        val illum=(estimate/mass).coerceIn(0f,1f)
        val confidenceValue=(reliability/mass).coerceIn(0f,1f)
        val darkness=((.42f-illum)/.37f).coerceIn(0f,1f)
        val signal=smooth(.015f,.060f,sourceY)
        val brightProtection=1f-smooth(.44f,.70f,sourceY)
        val midShadowLift=.19f*strength.coerceIn(0f,1f)*darkness*signal*brightProtection*confidenceValue
        return midShadowLift.coerceIn(0f,.19f)
    }

    /**
     * Signed, edge-aware dodge/burn based on measured neighbourhood illumination.
     * The output is a bounded luminance delta, not a semantic object mask.
     * Pure black/white and dark artistic scenes remain protected by caller policy.
     */
    fun depthAt(u: Float, v: Float, sourceY: Float, strength: Float): Float {
        if (strength <= 0f || sourceY <= .025f || sourceY >= .965f) return 0f
        val px=(u*width-.5f).coerceIn(0f,(width-1).toFloat())
        val py=(v*height-.5f).coerceIn(0f,(height-1).toFloat())
        val x=px.toInt(); val y=py.toInt()
        val xx=min(x+1,width-1); val yy=min(y+1,height-1)
        val fx=px-x; val fy=py-y
        val i0=y*width+x;val i1=y*width+xx
        val i2=yy*width+x;val i3=yy*width+xx
        val d0=sourceY-guide[i0];val d1=sourceY-guide[i1]
        val d2=sourceY-guide[i2];val d3=sourceY-guide[i3]
        val w0=(1f-fx)*(1f-fy)/(1f+90f*d0*d0)
        val w1=fx*(1f-fy)/(1f+90f*d1*d1)
        val w2=(1f-fx)*fy/(1f+90f*d2*d2)
        val w3=fx*fy/(1f+90f*d3*d3)
        val mass=w0+w1+w2+w3
        if(mass<1e-7f)return 0f
        val illum=((w0*base[i0]+w1*base[i1]+w2*base[i2]+w3*base[i3])/mass)
            .coerceIn(0f,1f)
        val reliable=((w0*confidence[i0]+w1*confidence[i1]+
            w2*confidence[i2]+w3*confidence[i3])/mass).coerceIn(0f,1f)
        val shadow=smooth(.04f,.16f,sourceY)*(1f-smooth(.37f,.65f,sourceY)) *
            (1f-smooth(.14f,.40f,illum))
        val bright=smooth(.48f,.70f,sourceY)*(1f-smooth(.88f,.98f,sourceY))*
            smooth(.54f,.79f,illum)
        // Lift underexposed neighbourhoods and gently burn already-strong bright
        // areas, instead of lifting every pixel uniformly.
        return ((.087f*shadow-.052f*bright)*strength.coerceIn(0f,1f)*reliable)
            .coerceIn(-.052f,.087f)
    }

    companion object {
        fun build(pixels: IntArray,width: Int,height: Int,noiseLevels: Float=1f): SpatialIllumination {
            require(width > 0 && height > 0 && pixels.size==width*height)
            val n=pixels.size
            val input=FloatArray(n) { i -> luma(pixels[i]) }
            val radius=(min(width,height)/26).coerceIn(2,12)
            val mean=boxMean(input,width,height,radius)
            val square=FloatArray(n){ i->input[i]*input[i] }
            val meanSquare=boxMean(square,width,height,radius)
            // A guided filter follows strong illumination edges, rather than
            // using a blurred mask that makes bright halos around dark objects.
            val eps=.0028f
            val a=FloatArray(n)
            val b=FloatArray(n)
            for(i in 0 until n){
                val variance=max(0f,meanSquare[i]-mean[i]*mean[i])
                a[i]=variance/(variance+eps)
                b[i]=mean[i]-a[i]*mean[i]
            }
            val meanA=boxMean(a,width,height,radius)
            val meanB=boxMean(b,width,height,radius)
            val smoothBase=FloatArray(n)
            val confidence=FloatArray(n)
            val noiseFloor=.00045f+noiseLevels.coerceIn(0f,9f)*.00009f
            var recoverable=0
            for(i in 0 until n){
                smoothBase[i]=(meanA[i]*input[i]+meanB[i]).coerceIn(0f,1f)
                val variance=max(0f,meanSquare[i]-mean[i]*mean[i])
                confidence[i]=(variance/(variance+noiseFloor)).coerceIn(0f,1f)
                if(smoothBase[i]<.36f && input[i]>.035f && variance>.001f && confidence[i]>.18f) recoverable++
            }
            return SpatialIllumination(width,height,input,smoothBase,confidence,recoverable.toFloat()/n)
        }
        private fun smooth(a:Float,b:Float,x:Float):Float {
            val t=((x-a)/(b-a)).coerceIn(0f,1f)
            return t*t*(3f-2f*t)
        }
        private fun luma(pixel:Int):Float =
            ((pixel ushr 16 and 255)*.2126f+(pixel ushr 8 and 255)*.7152f+(pixel and 255)*.0722f)/255f
        /** Integral-image box filter in O(width*height), even for large radii. */
        private fun boxMean(values:FloatArray,width:Int,height:Int,radius:Int):FloatArray {
            val stride=width+1
            val sums=DoubleArray(stride*(height+1))
            for(y in 0 until height){
                var rowSum=0.0
                for(x in 0 until width){
                    rowSum+=values[y*width+x]
                    sums[(y+1)*stride+x+1]=sums[y*stride+x+1]+rowSum
                }
            }
            val output=FloatArray(values.size)
            for(y in 0 until height){
                val y0=max(0,y-radius);val y1=min(height-1,y+radius)
                for(x in 0 until width){
                    val x0=max(0,x-radius);val x1=min(width-1,x+radius)
                    val sum=sums[(y1+1)*stride+x1+1]-sums[y0*stride+x1+1]-
                        sums[(y1+1)*stride+x0]+sums[y0*stride+x0]
                    output[y*width+x]=(sum/((x1-x0+1)*(y1-y0+1))).toFloat()
                }
            }
            return output
        }
    }
}