package com.example.engine.v9

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Read-only, source-relative assessment of candidate color retention in OKLab.
 * V9's rendering math is left unchanged. This prevents candidate selection from
 * treating widespread removal of source chroma as harmless merely because
 * luminance and clipping checks pass.
 *
 * NOT an aesthetic model: real photograph / human-review qualification is needed.
 * The guard intentionally avoids object/scene classes, image IDs and locations.
 */
internal object ChromaticIntegrity {
    private const val SIZE = 4096
    private val decode = FloatArray(SIZE+1) { i ->
        val x=i.toFloat()/SIZE
        if(x<=.04045f) x/12.92f else ((x+.055f)/1.055f).pow(2.4f)
    }
    private fun linear(v:Float):Float {
        val x=v.coerceIn(0f,1f)*SIZE
        val index=x.toInt().coerceAtMost(SIZE-1)
        return decode[index]+(decode[index+1]-decode[index])*(x-index)
    }

    private fun okLab(color:Int, out:FloatArray) {
        val r=linear((color ushr 16 and 255)/255f)
        val g=linear((color ushr 8 and 255)/255f)
        val b=linear((color and 255)/255f)
        val l=Math.cbrt((.4122214708*r+.5363325363*g+.0514459929*b).toDouble())
        val m=Math.cbrt((.2119034982*r+.6806995451*g+.1073969566*b).toDouble())
        val s=Math.cbrt((.0883024619*r+.2817188376*g+.6299787005*b).toDouble())
        out[0]=(.2104542553*l+.7936177850*m-.0040720468*s).toFloat()
        out[1]=(1.9779984951*l-2.4285922050*m+.4505937099*s).toFloat()
        out[2]=(.0259040371*l+.7827717662*m-.8086757660*s).toFloat()
    }

    fun assess(source:IntArray, edited:IntArray, stride:Int=4):ChromaticIntegrityReport {
        require(source.size==edited.size && stride>0)
        val before=FloatArray(3);val after=FloatArray(3)
        var inspected=0;var eligible=0;var severe=0
        var relativeLost=0.0
        for(i in source.indices step stride) {
            okLab(source[i],before);okLab(edited[i],after)
            inspected++
            val c0=sqrt(before[1]*before[1]+before[2]*before[2])
            // Protect visible, colorful source material; do not treat neutral
            // pixels or near-gamut highlights as evidence of chroma loss.
            if(c0<=.045f || before[0] !in .15f.. .90f ||
                after[0] !in .15f.. .94f || abs(after[0]-before[0])>.30f) continue
            val c1=sqrt(after[1]*after[1]+after[2]*after[2])
            eligible++
            val lost=max(0f,1f-c1/c0)
            relativeLost+=lost
            if(c1<c0*.65f-.004f) severe++
        }
        return ChromaticIntegrityReport(
            inspected,eligible,
            if(eligible>0)severe.toFloat()/eligible else 0f,
            if(eligible>0)(relativeLost/eligible).toFloat() else 0f
        )
    }
}

data class ChromaticIntegrityReport(
    val sampledPixels:Int,
    val eligiblePixels:Int,
    val severeLossFraction:Float,
    val meanRelativeLoss:Float
) {
    // Large, widespread color loss must not pass simply because no channels
    // clipped. This is a conservative failure gate, not a style recommendation.
    val widespreadDamage:Boolean get() =
        eligiblePixels>=max(192,sampledPixels/20) &&
        severeLossFraction>.35f && meanRelativeLoss>.24f
}
