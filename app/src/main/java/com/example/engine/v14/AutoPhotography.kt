package com.example.engine.v14

import android.graphics.Bitmap
import com.example.engine.AnalysisResult
import com.example.engine.v7.EditRecipe
import com.example.engine.v7.EnhancementMath
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Classical camera-photo development. The user has no sliders or style presets.
 * Every parameter is inferred from THIS input's histograms and chroma distribution.
 * No pretrained models, AI, internet, scene IDs, subject classes or image signatures.
 */
data class PhotoTreatment(
    val gamma: Float = 1f,
    val contrast: Float = 0f,
    val shadow: Float = 0f,
    val shoulder: Float = 0f,
    val chroma: Float = 1f,
    val clarity: Float = 0f,
    val redGain: Float = 1f, val greenGain: Float = 1f, val blueGain: Float = 1f,
    val lowKey: Boolean = false
) {
    val isNeutral get() = gamma==1f && contrast==0f && shadow==0f &&
        shoulder==0f && chroma==1f && clarity==0f &&
        redGain==1f && greenGain==1f && blueGain==1f
    fun scaled(factor:Float)=PhotoTreatment(
        1f+(gamma-1f)*factor, contrast*factor, shadow*factor,
        shoulder*factor,1f+(chroma-1f)*factor,clarity*factor,
        1f+(redGain-1f)*factor,1f+(greenGain-1f)*factor,
        1f+(blueGain-1f)*factor,lowKey)
}

data class PhotoEvidence(
    val p10:Float,val median:Float,val p90:Float,
    val darkFraction:Float,val meanChroma:Float,val vividFraction:Float,
    val lowKey:Boolean
)

object AutoPhotography {
    private fun smooth(a:Float,b:Float,x:Float):Float {
        val t=((x-a)/(b-a)).coerceIn(0f,1f)
        return t*t*(3f-2f*t)
    }

    fun evidence(pixels:IntArray):PhotoEvidence {
        require(pixels.isNotEmpty())
        val hist=IntArray(256)
        var dark=0;var vivid=0;var chroma=0.0
        for(p in pixels) {
            val r=((p ushr 16) and 255)/255f
            val g=((p ushr 8) and 255)/255f
            val b=(p and 255)/255f
            val y=EnhancementMath.luma(r,g,b)
            hist[(y*255f).toInt().coerceIn(0,255)]++
            if(y<.25f)dark++
            val c=maxOf(r,g,b)-minOf(r,g,b)
            chroma+=c
            if(c>.30f)vivid++
        }
        fun percentile(part:Float):Float {
            var count=0
            val threshold=max(1,(pixels.size*part).toInt())
            for(i in 0..255) {
                count+=hist[i]
                if(count>=threshold)return i/255f
            }
            return 1f
        }
        val lo=percentile(.10f);val mid=percentile(.5f);val hi=percentile(.90f)
        val darkFraction=dark.toFloat()/pixels.size
        // Low-key scenes such as night skylines should not be made daytime-bright.
        // The criteria are continuous image measurements, not scene recognition.
        val lowKey=mid<.42f && darkFraction>.27f && hi<.60f &&
            (hi-lo)<.32f
        return PhotoEvidence(lo,mid,hi,darkFraction,(chroma/pixels.size).toFloat(),
            vivid.toFloat()/pixels.size,lowKey)
    }

    fun choose(e:PhotoEvidence):PhotoTreatment {
        val range=e.p90-e.p10
        val compressed=((.67f-range)/.34f).coerceIn(0f,1f)
        val bright=((e.p90-.74f)/.22f).coerceIn(0f,1f)
        val dim=if(e.lowKey)0f else ((.51f-e.median)/.27f).coerceIn(0f,1f)
        val target=when {
            e.lowKey->(e.median+.015f).coerceIn(.07f,.34f)
            e.median>.65f->(e.median-.035f).coerceIn(.54f,.76f)
            else->(e.median+(.52f-e.median)*.58f).coerceIn(.33f,.61f)
        }
        // sRGB gamma tone recovery; preserve black and white.
        val gamma=(ln(target.coerceIn(.02f,.98f))/ln(e.median.coerceIn(.025f,.975f)))
            .coerceIn(.70f,1.34f)
        val monochrome=e.meanChroma<.018f
        val muted=((.23f-e.meanChroma)/.19f).coerceIn(0f,1f)
        var chroma=if(monochrome)1f else 1.065f+.16f*muted
        if(e.vividFraction>.32f)chroma-=.065f
        val darkFactor=if(e.lowKey).45f else 1f
        return PhotoTreatment(
            gamma=gamma,
            contrast=(.105f+.205f*compressed)*darkFactor,
            shadow=(.045f+.13f*dim)*darkFactor,
            shoulder=.05f+.13f*bright,
            chroma=chroma.coerceIn(.98f,1.23f),
            clarity=if(e.lowKey).025f else .065f+.045f*compressed,
            lowKey=e.lowKey
        )
    }

    /**
     * Whole-frame tone + edge-guided regional dodge/burn + source-responsive color.
     * sourceBase is recomputed from the SAME photo at preview/export resolution.
     * The input and output channels are display-encoded sRGB floats.
     */
    fun apply(rgb:FloatArray, treatment:PhotoTreatment, sourceBase:Float,
              sourceY:Float) {
        if(treatment.isNeutral || sourceY<=.003f || sourceY>=.998f)return
        val r=rgb[0];val g=rgb[1];val b=rgb[2]
        val y=EnhancementMath.luma(r,g,b).coerceIn(.003f,.998f)
        var target=y.toDouble().pow(treatment.gamma.toDouble()).toFloat()
        val u=target-.5f
        target+=treatment.contrast*4f*u*(.25f-u*u)
        // Dodge and burn targets darker *illumination regions*, not arbitrary
        // RGB channels; the guide is edge-aware to reduce halos.
        val darkness=((.40f-sourceBase)/.35f).coerceIn(0f,1f)
        val localShadow=treatment.shadow * darkness *
            smooth(.05f,.15f,sourceY)*(1f-smooth(.48f,.76f,sourceY))
        target+=localShadow
        target-=treatment.shoulder *
            smooth(.65f,.95f,sourceY)*(1f-sourceY)
        // Small multi-scale texture separation; rejects high-contrast transitions.
        val residual=sourceY-sourceBase
        val detailWindow=1f-smooth(.035f,.11f,abs(residual))
        target+=treatment.clarity*residual*detailWindow*smooth(.07f,.21f,sourceY)
        // Retain photographic blacks already present in the source. The
        // safety gate must not mistake a shaped tone curve for permission to
        // crush recoverable near-black shades into solid black.
        val blackProtection=smooth(.018f,.115f,sourceY)
        if(target<sourceY)
            target=sourceY+(target-sourceY)*blackProtection
        // Bright source channels retain highlight headroom before gamut mapping.
        if(target>sourceY)
            target=sourceY+(target-sourceY)*(1f-smooth(.91f,.985f,sourceY))
        target=target.coerceIn(.003f,.997f)
        val scale=target/y
        var rr=r*scale;var gg=g*scale;var bb=b*scale
        val ownChroma=maxOf(rr,gg,bb)-minOf(rr,gg,bb)
        val colorBoost=1f+(treatment.chroma-1f)*(1f-ownChroma.coerceIn(0f,1f)*.66f)
        rr=target+(rr-target)*colorBoost
        gg=target+(gg-target)*colorBoost
        bb=target+(bb-target)*colorBoost
        rgb[0]=rr*treatment.redGain
        rgb[1]=gg*treatment.greenGain
        rgb[2]=bb*treatment.blueGain
    }

    fun prepare(input:Bitmap, analysis:AnalysisResult,
                checkCancelled:()->Unit={}):EditRecipe {
        checkCancelled()
        require(!input.isRecycled && input.width>0 && input.height>0)
        val scale=min(1f,640f/max(input.width,input.height))
        val sw=max(1,(input.width*scale+.5f).toInt())
        val sh=max(1,(input.height*scale+.5f).toInt())
        val sample=Bitmap.createScaledBitmap(input,sw,sh,true)
        try {
            val pixels=IntArray(sw*sh)
            sample.getPixels(pixels,0,sw,0,0,sw,sh)
            val e=evidence(pixels)
            val base=choose(e)
            val anchors=analysis.neutralAnchors
            // A white-balance correction is attempted only when multiple
            // smooth, weakly colored source regions agree. Dominant palette
            // is NOT evidence that the whole scene must be gray.
            val reliable=anchors.coverage>.025f &&
                anchors.halfDisagreement<.048f && anchors.spread<13f &&
                minOf(anchors.avgR,anchors.avgG,anchors.avgB)>35f &&
                maxOf(anchors.avgR,anchors.avgG,anchors.avgB)<230f
            val balance=(anchors.avgR+anchors.avgG+anchors.avgB)/3f
            val disagreement=if(balance>1f)
                (maxOf(anchors.avgR,anchors.avgG,anchors.avgB)-
                    minOf(anchors.avgR,anchors.avgG,anchors.avgB))/balance
                else 0f
            val treatment=if(reliable && disagreement>.07f) {
                fun gain(channel:Float)=
                    (balance/channel).coerceIn(.88f,1.12f)
                base.copy(redGain=gain(anchors.avgR),
                    greenGain=gain(anchors.avgG),blueGain=gain(anchors.avgB))
            } else base
            val fingerprint="v14-classical-adaptive-camera-development-no-ai"
            val md=MessageDigest.getInstance("SHA-256")
            val stride=max(1,pixels.size/6000)
            for(i in pixels.indices step stride){
                val p=pixels[i]
                md.update((p ushr 16).toByte())
                md.update((p ushr 8).toByte())
                md.update(p.toByte())
            }
            md.update(fingerprint.toByteArray())
            val id=md.digest().take(8).joinToString("") { "%02x".format(it) }
            val decisions=listOf(
                "Auto photo treatment: gamma %.3f contrast %.3f shadow %.3f shoulder %.3f chroma %.3f clarity %.3f WB %.3f/%.3f/%.3f"
                    .format(treatment.gamma,treatment.contrast,treatment.shadow,
                        treatment.shoulder,treatment.chroma,treatment.clarity,
                        treatment.redGain,treatment.greenGain,treatment.blueGain),
                "Source p10 %.3f p50 %.3f p90 %.3f; low-key preserved=%s"
                    .format(e.p10,e.median,e.p90,e.lowKey),
                "Classical processing; no network, inference or scene-specific presets"
            )
            return EditRecipe(null,null,0f,0f,0f,0f,
                input.width.toFloat()/input.height,id,fingerprint,decisions,
                strategy="Automatic photographic recovery",
                photoTreatment=treatment)
        } finally { if(sample!==input)sample.recycle() }
    }
}
