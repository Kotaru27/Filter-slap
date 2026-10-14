package com.example.engine.v9

import kotlin.math.max

/**
 * Smooth display-referred tonal refinement intended to COMPLEMENT the existing
 * editing pipeline, not replace it. Two independent scene-derived controls:
 *   shadow: recover only low and medium signals, without raising absolute black
 *   highlight: gently roll off bright areas, without flattening absolute white
 *
 * Each pixel uses its source luminance to compute one shared RGB multiplier.
 * This keeps source channel ratios, avoids introducing a color cast, and retains
 * the legacy gamut limiter. No scene names, hues, labels, coordinates or images
 * enter this operator. It remains experimental until photographic review.
 */
internal object CoherentTone {
    private fun smooth(a:Float,b:Float,x:Float):Float {
        val t=((x-a)/(b-a)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }

    fun difference(sourceY:Float,shadow:Float,highlight:Float):Float {
        val y=sourceY.coerceIn(0f,1f)
        if(y<=.004f || y>=.998f)return 0f
        val energy=y*(1f-y)
        // Both fields vanish at black and white; shadow signal is noise-gated.
        val lift=shadow.coerceIn(0f,.5f)*energy*
            smooth(.025f,.12f,y)*(1f-smooth(.35f,.65f,y))
        val recover=highlight.coerceIn(0f,.5f)*energy*smooth(.50f,.83f,y)
        return lift-recover
    }
}
