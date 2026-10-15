package com.example.engine.learned

import kotlin.math.floor
import kotlin.math.tanh

/**
 * Full-resolution guided affine-grid renderer for research/learned_photo/model.py.
 * ONNX output shape [1,12,8,H,W]. Channel = RGB x (R,G,B,bias). Native
 * PyTorch grid_sample uses (x,y,luminance), align_corners=true and border pad.
 *
 * No arrays/closures/Pair objects are allocated per pixel, and no crop- or
 * scene-specific correction is encoded in the renderer.
 */
internal class LearnedCoefficientGrid(
    private val coefficients: FloatArray,
    val bins: Int,
    val gridHeight: Int,
    val gridWidth: Int
) {
    init {
        require(bins == 8 && gridHeight in listOf(1,16) && gridWidth == gridHeight)
        require(coefficients.size == 12 * bins * gridHeight * gridWidth)
        require(coefficients.all { it.isFinite() && it in -16f..16f })
    }

    /** Pure pixel operation; exact zero grid preserves all original ARGB bytes. */
    fun pixel(argb: Int, u: Float, v: Float): Int {
        require(u.isFinite() && v.isFinite())
        val r=(argb ushr 16 and 255)/255f
        val g=(argb ushr 8 and 255)/255f
        val b=(argb and 255)/255f
        val lum=(.2126f*r+.7152f*g+.0722f*b).coerceIn(0f,1f)

        val px=u.coerceIn(0f,1f)*(gridWidth-1)
        val py=v.coerceIn(0f,1f)*(gridHeight-1)
        val pz=lum*(bins-1)
        val x0=floor(px).toInt().coerceIn(0,gridWidth-1)
        val y0=floor(py).toInt().coerceIn(0,gridHeight-1)
        val z0=floor(pz).toInt().coerceIn(0,bins-1)
        val x1=(x0+1).coerceAtMost(gridWidth-1)
        val y1=(y0+1).coerceAtMost(gridHeight-1)
        val z1=(z0+1).coerceAtMost(bins-1)
        val dx=px-x0; val dy=py-y0; val dz=pz-z0
        var result=argb and -0x1000000
        val plane=gridHeight*gridWidth
        for(channel in 0..2) {
            val base=channel*4*bins*plane
            var delta=0f
            for(cornerZ in 0..1)for(cornerY in 0..1)for(cornerX in 0..1) {
                val zi=if(cornerZ==0)z0 else z1
                val yi=if(cornerY==0)y0 else y1
                val xi=if(cornerX==0)x0 else x1
                val weight=(if(cornerZ==0)1f-dz else dz) *
                    (if(cornerY==0)1f-dy else dy) *
                    (if(cornerX==0)1f-dx else dx)
                val i=(zi*gridHeight+yi)*gridWidth+xi
                delta+=weight*(coefficients[base+i]*r +
                    coefficients[base+bins*plane+i]*g +
                    coefficients[base+2*bins*plane+i]*b +
                    coefficients[base+3*bins*plane+i])
            }
            val source=when(channel){0->r;1->g;else->b}
            val out=(source+.35f*tanh(delta.toDouble()).toFloat()).coerceIn(0f,1f)
            val encoded=(out*255f+.5f).toInt().coerceIn(0,255)
            result=result or (encoded shl ((2-channel)*8))
        }
        return result
    }
}
