package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import com.example.engine.AnalysisResult
import com.example.engine.EditDecision
import com.example.engine.EditPlan
import com.example.engine.ProcessingStats
import com.example.engine.RenderResult
import com.example.engine.v8.CandidatePlanner
import com.example.engine.v8.CandidateSettings
import com.example.engine.v8.CorrectionEvidence
import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import com.example.engine.v8.CandidateAssessment
import com.example.engine.v9.SpatialIllumination
import com.example.engine.v9.SpatialQuality
import com.example.engine.v9.ChromaticIntegrity
import com.example.engine.creative.CreativeGrading
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Prepare once, render the SAME recipe for screen and export. */
object OfflineEnhancer {
    fun process(context: Context, input: Bitmap, analysis: AnalysisResult,
                checkCancelled: () -> Unit = {}): RenderResult {
        var recipe=prepare(context,input,analysis,checkCancelled)
        repeat(4) {
            try { return render(input,recipe,analysis,checkCancelled) }
            catch (unsafe: UnsafeRenderException) {
                recipe=recipe.attenuated(if(it==2) 0f else .5f,
                    "Strength reduced after final-pixel damage check")
            }
        }
        return render(input,recipe.attenuated(0f,"Original retained after safety review"),analysis,checkCancelled)
    }

    fun prepare(context: Context, input: Bitmap, analysis: AnalysisResult,
                checkCancelled: () -> Unit = {}): EditRecipe {
        checkCancelled()
        require(!input.isRecycled && input.width > 0 && input.height > 0)
        val scale=min(1f,768f/max(input.width,input.height))
        val width=max(1,(input.width*scale+.5f).toInt())
        val height=max(1,(input.height*scale+.5f).toInt())
        val canonical=Bitmap.createScaledBitmap(input,width,height,true)
        try {
            // NO AI: all proposals come from mathematical measurements of
            // this photograph. No pretrained LUT, segmentation network,
            // inference runtime, downloads or external photo content.
            val notes=mutableListOf("Classical offline processing: whole-frame statistics, guided light recovery")
            val lut:FloatArray?=null
            val mask:MaskField?=null
            checkCancelled()
            val pixels=IntArray(width*height)
            canonical.getPixels(pixels,0,width,0,0,width,height)
            val histogram=IntArray(256);var subjects=0
            if(mask!=null) for(y in 0 until height) {
                checkCancelled()
                for(x in 0 until width) if(mask.at((x+.5f)/width,(y+.5f)/height) > .8f) {
                    histogram[(EnhancementMath.luma(pixels[y*width+x])*255f).toInt().coerceIn(0,255)]++
                    subjects++
                }
            }
            var acc=0;var subjectMedian=1f
            for(i in histogram.indices) { acc+=histogram[i];if(subjects>0 && acc>=subjects*.5f) { subjectMedian=i/255f;break } }
            val intentionalDark=analysis.isDarkImage && analysis.tonalStructure.p95 < .60f
            val nativeNoise=estimateNativeNoise(input,checkCancelled)
            val noiseAttenuation=(1f-nativeNoise/8f).coerceIn(.3f,1f)
            val localLift=if(mask!=null && !intentionalDark)
                ((.44f-subjectMedian)*1.45f).coerceIn(0f,.28f)*noiseAttenuation else 0f
            val globalLift=if(mask==null && !intentionalDark)
                ((.34f-analysis.tonalStructure.p50)*1.1f).coerceIn(0f,.12f)*noiseAttenuation else 0f
            val detail=.16f*(1f-nativeNoise/3.5f).coerceIn(0f,1f)
            val denoise=((nativeNoise-.8f)/6f).coerceIn(0f,.35f)
            notes += "Native noise estimate %.2f levels; detail and denoise bounded".format(nativeNoise)
            val fingerprint="classical-auto-photo-grading-v13-no-ai"
            val digest=MessageDigest.getInstance("SHA-256")
            for(p in pixels) {
                digest.update((p ushr 24).toByte());digest.update((p ushr 16).toByte())
                digest.update((p ushr 8).toByte());digest.update(p.toByte())
            }
            digest.update(fingerprint.toByteArray())
            val id=digest.digest().take(8).joinToString("") { "%02x".format(it) }
            // Evidence and candidate generation operate on a small common preview.
            // The same selected immutable recipe will be used at export resolution.
            val sampleScale=min(1f,384f/max(width,height))
            val sampleW=max(1,(width*sampleScale+.5f).toInt())
            val sampleH=max(1,(height*sampleScale+.5f).toInt())
            val sample=Bitmap.createScaledBitmap(canonical,sampleW,sampleH,true)
            try {
                val before=measureTones(sample,mask,checkCancelled)
                val sourcePixels=IntArray(sampleW*sampleH)
                sample.getPixels(sourcePixels,0,sampleW,0,0,sampleW,sampleH)
                val localEvidence=SpatialIllumination.build(sourcePixels,sampleW,sampleH,nativeNoise)
                val beforeNeutral=measureNeutralError(sample,sample,checkCancelled)
                val na=analysis.neutralAnchors
                val evidence=CorrectionEvidence(before,intentionalDark,na.coverage,
                    na.halfDisagreement,floatArrayOf(na.avgR,na.avgG,na.avgB),
                    localEvidence.recoverableFraction)
                val creative = CreativeGrading.propose(before,sourcePixels,intentionalDark)
                val hybrid = if(!intentionalDark && localEvidence.recoverableFraction > .025f)
                    listOf(CandidateSettings("Automatic dimensional local grade",
                        creativeGrade=creative[1].creativeGrade,
                        spatialStrength=(.52f+.40f*localEvidence.recoverableFraction)
                            .coerceIn(.52f,.88f)))
                else emptyList()
                // Whole-frame color and tone plus signed local dodge/burn candidate.
                // The signed local term uses edge-aware source illumination, not AI.
                val layered = if(!intentionalDark) listOf(
                    CandidateSettings("Automatic layered light editing",
                        creativeGrade=creative[1].creativeGrade,spatialStrength=.68f))
                    else emptyList()
                val proposals=CandidatePlanner.propose(evidence,false) + creative + hybrid + layered
                val assessments=mutableListOf<CandidateAssessment>()
                val candidates=mutableListOf<EditRecipe>()
                for(strategy in proposals) {
                    checkCancelled()
                    // Detail and denoise are proposed only alongside a measurable
                    // correction; they cannot turn a neutral no-edit into an edit.
                    val active=!strategy.isNeutral
                    val trial=EditRecipe(lut,mask,
                        strategy.lutStrength*noiseAttenuation,0f,0f,
                        if(active) detail else 0f,
                        input.width.toFloat()/input.height,id,fingerprint,notes,
                        if(active) denoise else 0f,
                        strategy.exposureEv,strategy.subjectEv,strategy.backgroundEv,
                        strategy.contrast,strategy.subjectContrast,
                        strategy.redGain,strategy.greenGain,strategy.blueGain,strategy.name,
                        strategy.backgroundContrast,strategy.spatialStrength,nativeNoise,
                        strategy.coherentShadows,strategy.coherentHighlights,strategy.creativeGrade)
                    candidates+=trial
                    val evaluation=try {
                        val rendered=render(sample,trial,analysis,checkCancelled)
                        try {
                            val after=measureTones(rendered.bitmap,mask,checkCancelled)
                            val d=rendered.outcome!!.damage
                            val afterNeutral=measureNeutralError(sample,rendered.bitmap,checkCancelled)
                            val editedPixels=IntArray(sampleW*sampleH)
                            rendered.bitmap.getPixels(editedPixels,0,sampleW,0,0,sampleW,sampleH)
                            val spatialQuality=SpatialQuality.compare(sourcePixels,editedPixels,sampleW,sampleH)
                            val colorIntegrity=ChromaticIntegrity.assess(sourcePixels,editedPixels)
                            val conventional = CandidatePlanner.assess(evidence,strategy,after,d.meanChange,
                                d.newlyClipped,d.newlyCrushed,d.hueShifted,d.vividBoosted,
                                beforeNeutral,afterNeutral,spatialQuality,colorIntegrity)
                            if(conventional.safe && !strategy.creativeGrade.isNeutral) {
                                val tonal=CreativeGrading.benefit(before,after,intentionalDark)
                                val color=CreativeGrading.colorBenefit(sourcePixels,editedPixels)
                                conventional.copy(score=conventional.score+tonal+color,
                                    reason=conventional.reason+
                                        "; creative tonal %.2f, color %.2f".format(tonal,color))
                            } else conventional
                        } finally { rendered.bitmap.recycle() }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(unsafe: UnsafeRenderException) {
                        CandidateAssessment(strategy,Float.NEGATIVE_INFINITY,
                            "Failed output safety review",0f,false)
                    }
                    assessments+=evaluation
                }
                val selected=CandidatePlanner.select(assessments)
                val index=assessments.indexOf(selected)
                val diagnostics=assessments.map { item ->
                    "Editing option ${item.candidate.name}: score=${"%.2f".format(item.score)}, ${item.reason}"
                }
                val chosen=candidates[index]
                return EditRecipe(lut,mask,chosen.modelStrength,chosen.globalLift,chosen.subjectLift,
                    chosen.detailStrength,chosen.sourceAspect,chosen.id,fingerprint,
                    notes+diagnostics+"Selected editing strategy: ${selected.candidate.name}",
                    chosen.noiseStrength,chosen.exposureEv,chosen.subjectEv,chosen.backgroundEv,
                    chosen.contrast,chosen.subjectContrast,chosen.redGain,chosen.greenGain,chosen.blueGain,
                    chosen.strategy,chosen.backgroundContrast,chosen.spatialStrength,chosen.noiseLevels,
                    chosen.coherentShadows,chosen.coherentHighlights,chosen.creativeGrade)
            } finally { if(sample !== canonical) sample.recycle() }
        } finally { if(canonical !== input) canonical.recycle() }
    }

    /** Robust region percentiles measured on the SAME preview for every candidate. */
    private fun measureTones(input: Bitmap,mask: com.example.engine.v7.MaskField?,
                             cancelled: () -> Unit): FrameTones {
        val w=input.width;val h=input.height
        val line=IntArray(w)
        val all=IntArray(256);val subject=IntArray(256);val background=IntArray(256)
        var total=0;var nSubject=0;var nBackground=0
        for(y in 0 until h) {
            cancelled()
            input.getPixels(line,0,w,0,y,w,1)
            for(x in 0 until w) {
                val value=line[x]
                val level=(EnhancementMath.luma(value)*255f).toInt().coerceIn(0,255)
                all[level]++;total++
                if(mask!=null) {
                    val p=mask.at((x+.5f)/w,(y+.5f)/h,value)
                    if(p>.7f){subject[level]++;nSubject++}
                    if(p<.3f){background[level]++;nBackground++}
                }
            }
        }
        fun summary(hist:IntArray,count:Int):RegionTones {
            var acc=0;var low=0;var med=0;var high=255
            var lo=false;var mid=false
            for(i in hist.indices) {
                acc+=hist[i]
                if(!lo && acc>=count*.1f){low=i;lo=true}
                if(!mid && acc>=count*.5f){med=i;mid=true}
                if(acc>=count*.9f){high=i;break}
            }
            return RegionTones(low/255f,med/255f,high/255f,count.toFloat()/total.coerceAtLeast(1))
        }
        return FrameTones(summary(all,total),
            if(nSubject>total*.04f)summary(subject,nSubject)else null,
            if(nBackground>total*.04f)summary(background,nBackground)else null)
    }

    /**
     * Measures channel imbalance on the *same selected source pixels* in original
     * and candidate. This measures actual color changes, not estimated WB gains.
     * It only scores unsaturated, midtone source pixels and is further gated by
     * independent neutral-anchor coverage and consistent illumination evidence.
     */
    private fun measureNeutralError(source: Bitmap, output: Bitmap,
                                    cancelled: () -> Unit): Float {
        require(source.width==output.width && source.height==output.height)
        val original=IntArray(source.width);val revised=IntArray(output.width)
        var red=0.0;var green=0.0;var blue=0.0;var selected=0
        for(y in 0 until source.height){
            cancelled()
            source.getPixels(original,0,source.width,0,y,source.width,1)
            output.getPixels(revised,0,output.width,0,y,output.width,1)
            for(x in original.indices){
                val p=original[x]
                val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                val hi=maxOf(r,g,b);val lo=minOf(r,g,b)
                if(hi in 40..230 && (hi-lo).toFloat() <= .18f*hi){
                    val q=revised[x]
                    red+=(q ushr 16 and 255);green+=(q ushr 8 and 255);blue+=(q and 255)
                    selected++
                }
            }
        }
        if(selected < source.width*source.height*.005f) return 0f
        val mean=(red+green+blue)/(3.0*selected)
        if(mean < 1.0)return 0f
        return ((maxOf(red,green,blue)-minOf(red,green,blue))/selected/mean).toFloat()
    }

    /** Full output validation counts every rendered pixel, not an averaged thumbnail. */
    fun render(input: Bitmap, recipe: EditRecipe, analysis: AnalysisResult,
               checkCancelled: () -> Unit = {}, verifyOnly: Boolean = false): RenderResult {
        val started=System.currentTimeMillis()
        checkCancelled()
        require(abs((input.width.toFloat()/input.height)/recipe.sourceAspect-1f) < .02f) { "Recipe crop mismatch" }
        val width=input.width;val height=input.height
        val output=if(verifyOnly) null else Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val source=IntArray(width);val top=IntArray(width);val bottom=IntArray(width);val dest=IntArray(width)
        val rgb=FloatArray(3)
        val damage=DamageAccumulator()
        val illumination=if(recipe.spatialStrength>0f) {
            val sc=min(1f,320f/max(width,height))
            val sw=max(1,(width*sc+.5f).toInt())
            val sh=max(1,(height*sc+.5f).toInt())
            val small=Bitmap.createScaledBitmap(input,sw,sh,true)
            try {
                val pix=IntArray(sw*sh)
                small.getPixels(pix,0,sw,0,0,sw,sh)
                SpatialIllumination.build(pix,sw,sh,recipe.noiseLevels)
            } finally { if(small!==input)small.recycle() }
        } else null
        val radius=(max(width,height)/1600f+.5f).toInt().coerceIn(1,3)
        try {
            for(y in 0 until height) {
                checkCancelled()
                input.getPixels(source,0,width,0,y,width,1)
                if(recipe.detailStrength!=0f || recipe.noiseStrength!=0f) {
                    input.getPixels(top,0,width,0,(y-radius).coerceAtLeast(0),width,1)
                    input.getPixels(bottom,0,width,0,(y+radius).coerceAtMost(height-1),width,1)
                }
                for(x in 0 until width) {
                    val foreground=recipe.mask?.at((x+.5f)/width,(y+.5f)/height,source[x]) ?: 0f
                    var neighbourhood=EnhancementMath.luma(source[x])
                    if(recipe.detailStrength!=0f || recipe.noiseStrength!=0f) {
                        val left=(x-radius).coerceAtLeast(0);val right=(x+radius).coerceAtMost(width-1)
                        neighbourhood=(EnhancementMath.luma(top[left])+EnhancementMath.luma(top[x])+EnhancementMath.luma(top[right])+
                            EnhancementMath.luma(source[left])+EnhancementMath.luma(source[x])+EnhancementMath.luma(source[right])+
                            EnhancementMath.luma(bottom[left])+EnhancementMath.luma(bottom[x])+EnhancementMath.luma(bottom[right]))/9f
                    }
                    val u=(x+.5f)/width;val v=(y+.5f)/height
                    val sourceY=EnhancementMath.luma(source[x])
                    val spatialLift=illumination?.liftAt(u,v,sourceY,recipe.spatialStrength) ?: 0f
                    val spatialDepth=if(recipe.creativeGrade.isNeutral) 0f else
                        illumination?.depthAt(u,v,sourceY,recipe.spatialStrength) ?: 0f
                    dest[x]=EnhancementMath.edit(source[x],foreground,recipe,neighbourhood,rgb,
                        spatialLift+spatialDepth)
                    damage.add(source[x],dest[x])
                }
                output?.setPixels(dest,0,width,0,y,width,1)
            }
            val report=damage.report()
            if(!verifyOnly && !report.safe) throw UnsafeRenderException("Final render did not pass safety checks")
            val decisions=mutableListOf<EditDecision>()
            fun decision(name: String,region: String,amount: Float) {
                if(amount > .0001f) decisions += EditDecision(name,region,"ColorTone",amount,.6f,.5f,.2f)
            }
            decision("AdaptiveColorTone","Global",recipe.modelStrength)
            decision("Exposure","Global",abs(recipe.exposureEv))
            decision("Exposure","Subject",abs(recipe.subjectEv))
            decision("Exposure","Background",abs(recipe.backgroundEv))
            decision("Contrast","Global",abs(recipe.contrast))
            decision("Contrast","Subject",abs(recipe.subjectContrast))
            decision("Contrast","Background",abs(recipe.backgroundContrast))
            decision("LocalToneRecovery","Spatial",recipe.spatialStrength)
            if(!recipe.creativeGrade.isNeutral)
                decision("LocalDodgeBurn","Spatial",recipe.spatialStrength)
            decision("Shadows","Global",recipe.coherentShadows)
            decision("Highlights","Global",recipe.coherentHighlights)
            decision("CreativeTone","Global",recipe.creativeGrade.contrast +
                recipe.creativeGrade.shadowDepth + recipe.creativeGrade.highlightShoulder +
                abs(recipe.creativeGrade.midtoneLift))
            decision("CreativeColor","Global",recipe.creativeGrade.vibrance +
                abs(recipe.creativeGrade.shadowWarmth) + abs(recipe.creativeGrade.highlightWarmth))
            if(abs(recipe.redGain-1f)+abs(recipe.greenGain-1f)+abs(recipe.blueGain-1f)>.002f)
                decision("WhiteBalance","Global",.10f)
            decision("LocalShadows","Subject",recipe.subjectLift)
            decision("Shadows","Global",recipe.globalLift)
            decision("SubjectDetail",if(recipe.mask!=null) "Subject" else "Detail",recipe.detailStrength)
            decision("NoiseReduction","Smooth regions",recipe.noiseStrength)
            val status=when {
                recipe.isIdentity || report.meanChange < .25f -> "Original kept"
                !recipe.creativeGrade.isNeutral -> "Graded"
                else -> "Enhanced"
            }
            if(status == "Original kept") decisions.clear()
            val scope=when {
                recipe.isIdentity -> "Unedited original preserved"
                !recipe.creativeGrade.isNeutral -> "Classical full-frame photographic color and tone grading; no AI model used"
                else -> "Classical full-frame and edge-aware tonal corrections; no AI model used"
            }
            val engine=if(recipe.creativeGrade.isNeutral) "V9_6_CLASSICAL_NO_AI" else "V13_CLASSICAL_AUTOMATIC_NO_AI"
            val outcome=EnhancementOutcome(status,engine,recipe.id,recipe.modelFingerprint,
                recipe.modelStrength,recipe.mask!=null,report,recipe.diagnostics+scope)
            // verifyOnly returns a borrowed input; it never transfers/recycles this bitmap.
            return RenderResult(output ?: input,ProcessingStats(System.currentTimeMillis()-started,
                stagesApplied=decisions.map { it.parameter }.toSet()),
                EditPlan(decisions,engine,outcome.summary()+" · "+recipe.strategy),analysis,outcome,recipe)
        } catch(error: Throwable) {
            output?.recycle()
            throw error
        }
    }

    /** Robust diagonal high-pass residual on low-gradient native-resolution samples.
     * A thumbnail cannot establish sensor-noise strength. This is an estimate, not a label. */
    internal fun estimateNativeNoise(input: Bitmap, checkCancelled: () -> Unit): Float {
        if(input.width<2 || input.height<2) return 0f
        val top=IntArray(input.width);val bottom=IntArray(input.width)
        val residuals=mutableListOf<Float>()
        val yStep=max(1,input.height/24);val xStep=max(1,input.width/64)
        for(y in 0 until input.height-1 step yStep) {
            checkCancelled()
            input.getPixels(top,0,input.width,0,y,input.width,1)
            input.getPixels(bottom,0,input.width,0,y+1,input.width,1)
            for(x in 0 until input.width-1 step xStep) {
                val a=EnhancementMath.luma(top[x]);val b=EnhancementMath.luma(top[x+1])
                val c=EnhancementMath.luma(bottom[x]);val d=EnhancementMath.luma(bottom[x+1])
                if(abs((a+b)-(c+d))<.07f && abs((a+c)-(b+d))<.07f)
                    residuals += abs(a-b-c+d)*255f
            }
        }
        if(residuals.size<32) return 0f
        residuals.sort()
        return (residuals[residuals.size/2]/1.349f).coerceIn(0f,20f)
    }
}
class UnsafeRenderException(message: String): Exception(message)
