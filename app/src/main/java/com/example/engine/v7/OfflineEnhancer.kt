package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import com.example.engine.AnalysisResult
import com.example.engine.EditDecision
import com.example.engine.EditPlan
import com.example.engine.ProcessingStats
import com.example.engine.RenderResult
import com.example.engine.v6.V6AdaptiveEditor
import com.example.engine.v8.CandidatePlanner
import com.example.engine.v8.CandidateSettings
import com.example.engine.v8.CorrectionEvidence
import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import com.example.engine.v8.CandidateAssessment
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
            val notes=mutableListOf<String>()
            val lut=try {
                checkCancelled()
                V6AdaptiveEditor.fusedLut(context,canonical).also {
                    require(it.all { value -> value.isFinite() && value in -1f..2f }) { "Invalid learned transform" }
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { notes += modelIssue("Color",error);null }
            catch(error: LinkageError) { notes += modelIssue("Color",error);null }
            checkCancelled()
            val mask=try {
                OfflineForeground.predict(context,canonical,checkCancelled).also {
                    if(it==null) notes += "Foreground proposal uncertain; using global tone only"
                }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { notes += modelIssue("Foreground",error);null }
            catch(error: LinkageError) { notes += modelIssue("Foreground",error);null }
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
            val fingerprint=try { context.assets.open("v6_model_sha256.txt").bufferedReader().use { it.readText().trim() }
                .replace('\n',';') + ";foreground="+OfflineForeground.SHA256 }
                catch(error: Exception) { "unavailable;foreground="+OfflineForeground.SHA256 }
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
                val na=analysis.neutralAnchors
                val evidence=CorrectionEvidence(before,intentionalDark,na.coverage,
                    na.halfDisagreement,floatArrayOf(na.avgR,na.avgG,na.avgB))
                val proposals=CandidatePlanner.propose(evidence,lut!=null)
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
                        strategy.redGain,strategy.greenGain,strategy.blueGain,strategy.name)
                    candidates+=trial
                    val evaluation=try {
                        val rendered=render(sample,trial,analysis,checkCancelled)
                        try {
                            val after=measureTones(rendered.bitmap,mask,checkCancelled)
                            val d=rendered.outcome!!.damage
                            CandidatePlanner.assess(evidence,strategy,after,d.meanChange,
                                d.newlyClipped,d.newlyCrushed,d.hueShifted,d.vividBoosted)
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
                    "Candidate ${item.candidate.name}: score=${"%.2f".format(item.score)}, ${item.reason}"
                }
                val chosen=candidates[index]
                return EditRecipe(lut,mask,chosen.modelStrength,chosen.globalLift,chosen.subjectLift,
                    chosen.detailStrength,chosen.sourceAspect,chosen.id,fingerprint,
                    notes+diagnostics+"Selected strategy: ${selected.candidate.name}",
                    chosen.noiseStrength,chosen.exposureEv,chosen.subjectEv,chosen.backgroundEv,
                    chosen.contrast,chosen.subjectContrast,chosen.redGain,chosen.greenGain,chosen.blueGain,
                    chosen.strategy)
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
                    dest[x]=EnhancementMath.edit(source[x],foreground,recipe,neighbourhood,rgb)
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
            if(abs(recipe.redGain-1f)+abs(recipe.greenGain-1f)+abs(recipe.blueGain-1f)>.002f)
                decision("WhiteBalance","Global",.10f)
            decision("LocalShadows","Subject",recipe.subjectLift)
            decision("Shadows","Global",recipe.globalLift)
            decision("SubjectDetail",if(recipe.mask!=null) "Subject" else "Detail",recipe.detailStrength)
            decision("NoiseReduction","Smooth regions",recipe.noiseStrength)
            val modelUnavailable=recipe.diagnostics.any { it.startsWith("Color model:") || it.startsWith("Foreground model:") }
            val status=when {
                recipe.isIdentity || report.meanChange < .25f -> "Original kept"
                modelUnavailable -> "Limited adjustment"
                else -> "Enhanced"
            }
            if(status == "Original kept") decisions.clear()
            val outcome=EnhancementOutcome(status,"V8_CANDIDATE",recipe.id,recipe.modelFingerprint,
                recipe.modelStrength,recipe.mask!=null,report,recipe.diagnostics)
            // verifyOnly returns a borrowed input; it never transfers/recycles this bitmap.
            return RenderResult(output ?: input,ProcessingStats(System.currentTimeMillis()-started,
                stagesApplied=decisions.map { it.parameter }.toSet()),
                EditPlan(decisions,"V8_CANDIDATE",outcome.summary()+" · "+recipe.strategy),analysis,outcome,recipe)
        } catch(error: Throwable) {
            output?.recycle()
            throw error
        }
    }

    private fun modelIssue(name: String, error: Throwable): String {
        val problem=when {
            error is java.io.FileNotFoundException -> "bundled asset missing"
            error is LinkageError -> "native runtime incompatible"
            error.message?.contains("integrity",ignoreCase=true)==true -> "asset integrity failure"
            error is IllegalArgumentException -> "invalid prediction or model contract"
            else -> "inference failed (${error.javaClass.simpleName})"
        }
        return "$name model: $problem"
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
