package com.example.engine.manual

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlin.math.pow

/**
 * Deterministic, fully offline and reversible photographic adjustments.
 * Every operation affects the image data, not merely report metadata.
 * No learned models, inferred presets, face templates or network access.
 */
data class ManualAdjustments(
    val exposure: Float = 0f,   // -2..+2 EV
    val contrast: Float = 0f,   // -100..+100
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val vibrance: Float = 0f,
    val saturation: Float = 0f,
    val vignette: Float = 0f   // 0..100
) {
    val isNeutral: Boolean get() = this == ManualAdjustments()

    fun validated(): ManualAdjustments {
        require(exposure.isFinite() && exposure in -2f..2f)
        for (v in listOf(contrast, highlights, shadows, whites, blacks,
            temperature, tint, vibrance, saturation)) {
            require(v.isFinite() && v in -100f..100f)
        }
        require(vignette.isFinite() && vignette in 0f..100f)
        return this
    }
}

object ManualPhotoEditor {
    // C1 continuous smoothstep, used to preserve neighboring midtones.
    private fun smooth(t: Float): Float {
        val v=t.coerceIn(0f,1f)
        return v*v*(3f-2f*v)
    }
    private fun byte(v: Float): Int =
        (v.coerceIn(0f,1f)*255f+0.5f).toInt().coerceIn(0,255)

    /**
     * One immutable source + one immutable adjustment recipe produces exactly
     * the same result at preview and export resolution (modulo resampling).
     * Processes at original dimensions with two bounded row buffers.
     */
    fun render(input: Bitmap, adjustments: ManualAdjustments,
               checkCancelled: () -> Unit = {}): Bitmap {
        adjustments.validated()
        require(!input.isRecycled && input.width>0 && input.height>0)
        val output=Bitmap.createBitmap(input.width,input.height,Bitmap.Config.ARGB_8888)
        val source=IntArray(input.width)
        val dest=IntArray(input.width)
        val neutralSettings = adjustments.isNeutral
        val evGain=2.0.pow(adjustments.exposure.toDouble()).toFloat()
        val contrastGain=1f+adjustments.contrast*.0075f
        val shadowAmt=adjustments.shadows*.0022f
        val highlightAmt=adjustments.highlights*.0022f
        val whiteAmt=adjustments.whites*.0015f
        val blackAmt=adjustments.blacks*.0015f
        val warmth=adjustments.temperature*.00115f
        val tint=adjustments.tint*.0009f
        val saturation=1f+adjustments.saturation*.01f
        val vibrance=adjustments.vibrance*.006f
        val vignette=adjustments.vignette*.01f
        val cx=(input.width-1).coerceAtLeast(1).toFloat()
        val cy=(input.height-1).coerceAtLeast(1).toFloat()
        try {
            for(y in 0 until input.height) {
                checkCancelled()
                input.getPixels(source,0,input.width,0,y,input.width,1)
                val ny=(2f*y/cy-1f)
                for(x in source.indices) {
                    val pixel=source[x]
                    if(neutralSettings) {
                        dest[x]=pixel
                        continue
                    }
                    var r=(pixel ushr 16 and 255)/255f*evGain
                    var g=(pixel ushr 8 and 255)/255f*evGain
                    var b=(pixel and 255)/255f*evGain
                    // No early clamp: avoid erasing recoverable channel variation.
                    val lum=(.2126f*r+.7152f*g+.0722f*b).coerceIn(0f,1f)
                    val shadowsWeight=1f-smooth((lum-.08f)/.48f)
                    val highlightWeight=smooth((lum-.46f)/.46f)
                    val blackWeight=1f-smooth(lum/.24f)
                    val whiteWeight=smooth((lum-.74f)/.24f)
                    val lift=shadowAmt*shadowsWeight+highlightAmt*highlightWeight+
                        blackAmt*blackWeight+whiteAmt*whiteWeight
                    r=(r+lift-.5f)*contrastGain+.5f
                    g=(g+lift-.5f)*contrastGain+.5f
                    b=(b+lift-.5f)*contrastGain+.5f
                    r*=1f+warmth+tint
                    g*=1f-tint
                    b*=1f-warmth+tint
                    // Grayscale is unchanged under color-only adjustments.
                    val neutral=.2126f*r+.7152f*g+.0722f*b
                    val spread=(maxOf(r,g,b)-minOf(r,g,b)).coerceIn(0f,1f)
                    val colorGain=(saturation+vibrance*(1f-spread)).coerceAtLeast(0f)
                    r=neutral+(r-neutral)*colorGain
                    g=neutral+(g-neutral)*colorGain
                    b=neutral+(b-neutral)*colorGain
                    if(vignette>0f) {
                        val nx=2f*x/cx-1f
                        val radius=((nx*nx+ny*ny)*.5f).coerceIn(0f,1f)
                        val multiplier=1f-vignette*.58f*smooth((radius-.10f)/.90f)
                        r*=multiplier;g*=multiplier;b*=multiplier
                    }
                    dest[x]=(pixel and -0x1000000) or
                        (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
                }
                output.setPixels(dest,0,input.width,0,y,input.width,1)
            }
            return output
        } catch (cancelled: Throwable) {
            output.recycle()
            throw cancelled
        }
    }
}
