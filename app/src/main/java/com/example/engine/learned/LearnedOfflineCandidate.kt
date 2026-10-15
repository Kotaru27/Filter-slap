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

    private fun predict(context:Context,source:Bitmap,assetName:String,
                        pin:String,checkCancelled:()->Unit):Pair<LearnedCoefficientGrid,String> {
        require(assetName in setOf("learned_global_predictor.onnx",
            "learned_spatial_predictor.onnx")) { "Unknown model asset path" }
        checkCancelled()
        // This asset does NOT ship today. The approved-weight build pipeline
        // must provide a SHA pin before any candidate can be requested.
        val bytes=context.assets.open(assetName).use { it.readBytes() }
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

    /** Safely exports candidate at native resolution using bounded row buffers. */
    fun renderCandidate(source:Bitmap,grid:LearnedCoefficientGrid,
                        hash:String="research-probe",checkCancelled:()->Unit={}):Preview? {
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
                    val candidate=grid.pixel(src[x],x*invW,y*invH)
                    dest[x]=candidate
                    damages.add(src[x],candidate)
                }
                output.setPixels(dest,0,source.width,0,y,source.width,1)
            }
            val report=damages.report()
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

    /** Return null if no strictly approved bundled model is available. */
    fun previewIfApproved(context:Context,source:Bitmap,
                          approvedSha256:String?,mode:String="global",
                          checkCancelled:()->Unit={}):Preview? {
        if(approvedSha256.isNullOrBlank()) return null
        require(mode=="global"||mode=="spatial")
        val name="learned_${mode}_predictor.onnx"
        val (grid,hash)=predict(context,source,name,approvedSha256,checkCancelled)
        return renderCandidate(source,grid,hash,checkCancelled)
    }
}
