package com.example.engine.learned

import kotlin.math.floor
import kotlin.math.tanh

/**
 * Full-resolution guided affine-grid renderer for research/learned_photo/model.py.
 *
 * ONNX output is flattened NCDHW: [1,12,8,H,W], where 12 = RGB x (3 source
 * coefficients + bias). PyTorch grid_sample uses (x,y,luminance) with
 * align_corners=true, bilinear/trilinear interpolation and border padding.
 * The model is only a candidate, not a replacement for the V9.3 pipeline.
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

    private fun at(channel: Int, z: Int, y: Int, x: Int): Float =
        coefficients[(((channel * bins + z) * gridHeight + y) * gridWidth + x)]

    private fun coordinate(v: Float, extent: Int): Pair<Int,Float> {
        val exact = v.coerceIn(0f,1f) * (extent-1)
        val base = floor(exact).toInt().coerceIn(0,extent-1)
        return base to (exact-base)
    }

    /** Pure operation: alpha is untouched; exact zeros give the original pixels. */
    fun pixel(argb: Int, u: Float, v: Float): Int {
        require(u.isFinite() && v.isFinite())
        val r=(argb ushr 16 and 255)/255f
        val g=(argb ushr 8 and 255)/255f
        val b=(argb and 255)/255f
        val luma=(.2126f*r+.7152f*g+.0722f*b).coerceIn(0f,1f)
        val (x,dx)=coordinate(u,gridWidth)
        val (y,dy)=coordinate(v,gridHeight)
        val (z,dz)=coordinate(luma,bins)
        val values=floatArrayOf(r,g,b)
        val out=IntArray(3)
        for(channel in 0..2) {
            val c=channel*4
            var delta=0f
            for(cornerZ in 0..1)for(cornerY in 0..1)for(cornerX in 0..1) {
                val zi=(z+cornerZ).coerceAtMost(bins-1)
                val yi=(y+cornerY).coerceAtMost(gridHeight-1)
                val xi=(x+cornerX).coerceAtMost(gridWidth-1)
                val weight=(if(cornerZ==0) 1f-dz else dz) *
                    (if(cornerY==0) 1f-dy else dy) *
                    (if(cornerX==0) 1f-dx else dx)
                val affine=at(c,zi,yi,xi)*r + at(c+1,zi,yi,xi)*g +
                    at(c+2,zi,yi,xi)*b + at(c+3,zi,yi,xi)
                delta+=weight*affine
            }
            val projected=(values[channel]+.35f*tanh(delta.toDouble()).toFloat())
                .coerceIn(0f,1f)
            out[channel]=(projected*255f+.5f).toInt().coerceIn(0,255)
        }
        return (argb and -0x1000000) or (out[0] shl 16) or (out[1] shl 8) or out[2]
    }

    fun copyCoefficientsForTest():FloatArray = coefficients.copyOf()
}
