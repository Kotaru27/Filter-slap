package com.example.engine.learned

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import com.example.engine.v7.DamageAccumulator
import com.example.engine.v7.DamageReport
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Explicitly opt-in offline candidate renderer, NOT installed in the app UI.
 *
 * A model is executable only when a future, rights-cleared ONNX model asset
 * is bundled with a caller-pinned SHA-256. No automatic downloads, no
 * unapproved research checkpoint, and no implicit change to V9.3 results.
 * Model output is a candidate until independently visually reviewed.
 */
internal object LearnedOfflineCandidate {
    private const val CONTEXT=256

    data class Preview(val bitmap:Bitmap,val fingerprint:String,val damage:DamageReport)

    private fun checkedSha256(bytes:ByteArray, expected:String):String {
        require(expected.matches(Regex("[a-fA-F0-9]{64}"))) {
            "No approved SHA-256 pin supplied; learned inference is disabled"
        }
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        require(hash.equals(expected,ignoreCase=true)) { "Unapproved model bytes" }
        return hash
    }

    private fun predictBytes(source:Bitmap,bytes:ByteArray,pin:String,
                             expectedMode:String,checkCancelled:()->Unit):Pair<LearnedCoefficientGrid,String> {
        require(bytes.size in 1..12_000_000) { "Unexpected offline model size" }
        require(expectedMode=="global" || expectedMode=="spatial")
        checkCancelled()
        val hash=checkedSha256(bytes,pin)
        val preview=Bitmap.createScaledBitmap(source,CONTEXT,CONTEXT,true)
        val sourcePixels=IntArray(CONTEXT*CONTEXT)
        try { preview.getPixels(sourcePixels,0,CONTEXT,0,0,CONTEXT,CONTEXT) }
        finally { if(preview!==source) preview.recycle() }
        val input=FloatArray(3*sourcePixels.size)
        for(i in sourcePixels.indices) {
            val pixel=sourcePixels[i]
            input[i]=(pixel ushr 16 and 255)/255f
            input[sourcePixels.size+i]=(pixel ushr 8 and 255)/255f
            input[2*sourcePixels.size+i]=(pixel and 255)/255f
        }
        val env=OrtEnvironment.getEnvironment()
        val options=ai.onnxruntime.OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        val session=try { env.createSession(bytes,options) }
            finally { options.close() }
        session.use {
            OnnxTensor.createTensor(env,FloatBuffer.wrap(input),
                longArrayOf(1,3,CONTEXT.toLong(),CONTEXT.toLong())).use { tensor ->
                checkCancelled()
                session.run(mapOf("srgb_context" to tensor)).use { result ->
                    val output=result[0] as? OnnxTensor
                        ?: throw IllegalArgumentException("Incorrect learned ONNX output")
                    val shape=output.info.shape
                    require(shape.size==5 && shape[0]==1L && shape[1]==12L &&
                        shape[2]==8L && shape[3] in setOf(1L,16L) &&
                        shape[4]==shape[3]) { "Incompatible learned ONNX output shape" }
                    val expectedGrid=if(expectedMode=="global")1L else 16L
                    require(shape[3]==expectedGrid) {
                        "Wrong learned model architecture for requested mode"
                    }
                    val expectedSize=shape.fold(1L) { a,b -> a*b }
                    require(expectedSize in 1..(12*8*16*16).toLong())
                    val values=FloatArray(expectedSize.toInt())
                    output.floatBuffer.get(values)
                    checkCancelled()
                    return LearnedCoefficientGrid(values,shape[2].toInt(),
                        shape[3].toInt(),shape[4].toInt()) to hash
                }
            }
        }
    }

    /**
     * Research-only strength attenuation preserves the original ARGB alpha,
     * zero strength is mathematically the unedited source, and unit strength
     * is bitwise identical to the existing learned renderer.
     */
    private fun attenuatePixel(original:Int,learned:Int,strength:Float):Int {
        if(strength>=1f) return learned
        if(strength<=0f) return original
        // Scalar byte operations only; no heap allocation per image pixel.
        fun ch(shift:Int):Int {
            val src=(original ushr shift) and 255
            val dst=(learned ushr shift) and 255
            return (src+(dst-src)*strength+.5f).toInt().coerceIn(0,255)
        }
        return (original and -0x1000000) or
            (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    data class StrengthSelection(val preview:Preview,val strength:Float)
    private val STRENGTHS=floatArrayOf(1f,.75f,.5f,.25f,.125f)

    /**
     * Single full-resolution scan obtains exact damage evidence for every
     * candidate strength. Only ONE expensive guided-grid interpolation per
     * pixel, instead of up to five full re-renders. Strictly preserves
     * candidate order and the unchanged V9 DamageReport.safe decision.
     */
    private fun strongestSafeStrength(source:Bitmap,grid:LearnedCoefficientGrid,
                                      checkCancelled:()->Unit):Float? {
        val evidence=Array(STRENGTHS.size) { DamageAccumulator() }
        val row=IntArray(source.width)
        val invW=1f/(source.width-1).coerceAtLeast(1)
        val invH=1f/(source.height-1).coerceAtLeast(1)
        for(y in 0 until source.height) {
            checkCancelled()
            source.getPixels(row,0,source.width,0,y,source.width,1)
            for(x in row.indices) {
                val original=row[x]
                val predicted=grid.pixel(original,x*invW,y*invH)
                for(i in STRENGTHS.indices)
                    evidence[i].add(original,
                        attenuatePixel(original,predicted,STRENGTHS[i]))
            }
        }
        return STRENGTHS.indices.firstOrNull{evidence[it].report().safe}
            ?.let{STRENGTHS[it]}
    }

    internal fun strongestSafeStrengthForTest(
        source:Bitmap,grid:LearnedCoefficientGrid,
        checkCancelled:()->Unit={}
    ):Float?=strongestSafeStrength(source,grid,checkCancelled)

    /**
     * Train/validation research path only: choose the strongest candidate
     * satisfying the exact original V9 damage policy; never use the held-out
     * expert retouch to choose per-photo strength.
     */
    internal fun previewApprovedStrengthSearchBytes(
        source:Bitmap,modelBytes:ByteArray,approvedSha256:String,
        mode:String="global",checkCancelled:()->Unit={}
    ):StrengthSelection? {
        val (grid,hash)=predictBytes(source,modelBytes,approvedSha256,mode,checkCancelled)
        val strength=strongestSafeStrength(source,grid,checkCancelled) ?: return null
        checkCancelled()
        val safe=renderAtStrength(source,grid,strength,hash,checkCancelled)
            ?: throw IllegalStateException("Single-scan damage gate disagreed with final pixels")
        return StrengthSelection(safe,strength)
    }

    private fun renderAtStrength(source:Bitmap,grid:LearnedCoefficientGrid,
        strength:Float,hash:String,checkCancelled:()->Unit={},
        observeDamage:((DamageReport)->Unit)?=null):Preview? {
        require(strength.isFinite() && strength in 0f..1f)
        require(!source.isRecycled && source.width>0 && source.height>0)
        val output=Bitmap.createBitmap(source.width,source.height,Bitmap.Config.ARGB_8888)
        val src=IntArray(source.width)
        val dest=IntArray(source.width)
        val damages=DamageAccumulator()
        try {
            val invW=1f/(source.width-1).coerceAtLeast(1)
            val invH=1f/(source.height-1).coerceAtLeast(1)
            for(y in 0 until source.height) {
                checkCancelled()
                source.getPixels(src,0,source.width,0,y,source.width,1)
                for(x in src.indices) {
                    val learned=grid.pixel(src[x],x*invW,y*invH)
                    val candidate=attenuatePixel(src[x],learned,strength)
                    dest[x]=candidate
                    damages.add(src[x],candidate)
                }
                output.setPixels(dest,0,source.width,0,y,source.width,1)
            }
            val report=damages.report()
            // Research-only observer: report factual harm metrics even if
            // the candidate is rejected. Does NOT alter acceptance thresholds.
            observeDamage?.invoke(report)
            if(!report.safe) {
                output.recycle()
                return null
            }
            return Preview(output,hash,report)
        } catch(t:Throwable) {
            output.recycle()
            throw t
        }
    }

    /** Test-only research hook; unchanged DamageReport.safe gate. */
    internal fun renderCandidateAtStrength(
        source:Bitmap,grid:LearnedCoefficientGrid,strength:Float,
        checkCancelled:()->Unit={}
    ):Preview?=renderAtStrength(source,grid,strength,"strength-study",checkCancelled)

    /** Existing public candidate path, exact legacy strength one. */
    fun renderCandidate(source:Bitmap,grid:LearnedCoefficientGrid,
                        hash:String="research-probe",checkCancelled:()->Unit={},
                        observeDamage:((DamageReport)->Unit)?=null):Preview? =
        renderAtStrength(source,grid,1f,hash,checkCancelled,observeDamage)

    /**
     * Explicit in-memory offline model pathway for a future user-owned or
     * distribution-cleared checkpoint. Every byte is SHA-pinned before ORT
     * executes. This is not wired to the main app editing flow.
     */
    internal fun previewApprovedBytes(source:Bitmap,modelBytes:ByteArray,
                                      approvedSha256:String,mode:String="global",
                                      observeDamage:((DamageReport)->Unit)?=null,
                                      checkCancelled:()->Unit={}):Preview? {
        // Cancellation MUST be the last argument, so a Kotlin trailing
        // lambda always cancels work rather than accidentally observing harm.
        val (grid,hash)=predictBytes(source,modelBytes,approvedSha256,mode,checkCancelled)
        return renderCandidate(source,grid,hash,checkCancelled,observeDamage)
    }

    /** Return null if no strictly approved bundled model is available. */
    fun previewIfApproved(context:Context,source:Bitmap,
                          approvedSha256:String?,mode:String="global",
                          checkCancelled:()->Unit={}):Preview? {
        if(approvedSha256.isNullOrBlank()) return null
        require(mode=="global"||mode=="spatial")
        val name="learned_${mode}_predictor.onnx"
        // Package model bytes are always explicitly pinned. No network.
        val bytes=context.assets.open(name).use { it.readBytes() }
        return previewApprovedBytes(source,bytes,approvedSha256,mode,
            checkCancelled=checkCancelled)
    }
}
