package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import com.example.engine.AnalysisResult
import com.example.engine.EditDecision
import com.example.engine.EditPlan
import com.example.engine.ProcessingStats
import com.example.engine.RenderResult
import com.example.engine.v6.V6AdaptiveEditor
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
            val baseStrength=if(lut!=null) (if(intentionalDark) .18f else .40f)*noiseAttenuation else 0f
            val base=EditRecipe(lut,mask,baseStrength,globalLift,localLift,detail,
                input.width.toFloat()/input.height,id,fingerprint,notes,denoise)
            for(factor in floatArrayOf(1f,.70f,.40f,0f)) {
                checkCancelled()
                val trial=if(factor==1f) base else base.attenuated(factor,"Reduced proposal strength")
                val sample=render(canonical,trial,analysis,checkCancelled,verifyOnly=true)
                if(sample.outcome!!.damage.safe) return trial
            }
            return base.attenuated(0f,"Original retained after candidate review")
        } finally { if(canonical !== input) canonical.recycle() }
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
            val outcome=EnhancementOutcome(status,"V7_OFFLINE",recipe.id,recipe.modelFingerprint,
                recipe.modelStrength,recipe.mask!=null,report,recipe.diagnostics)
            // verifyOnly returns a borrowed input; it never transfers/recycles this bitmap.
            return RenderResult(output ?: input,ProcessingStats(System.currentTimeMillis()-started,
                stagesApplied=decisions.map { it.parameter }.toSet()),
                EditPlan(decisions,"V7_OFFLINE",outcome.summary()),analysis,outcome,recipe)
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
