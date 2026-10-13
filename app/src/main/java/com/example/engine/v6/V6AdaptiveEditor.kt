package com.example.engine.v6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.example.engine.AnalysisResult
import com.example.engine.ContextualDecisionEngine
import com.example.engine.EditDecision
import com.example.engine.EditPlan
import com.example.engine.ImageProcessor
import com.example.engine.ProcessingStats
import com.example.engine.RenderResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Offline pretrained paired-sRGB adaptive 3D LUT.
 * V6 is a candidate: reject untrustworthy renders and delegate to V5. Never call a network API.
 * The sRGB basis is [basis, channel, blue, green, red], dimensions [3,3,33,33,33].
 */
object V6AdaptiveEditor {
    private const val MODEL_FILE = "v6_classifier.onnx"
    private const val LUT_FILE = "v6_basis_luts.bin"
    private const val DIM = 33
    private const val BASIS_SIZE = 3 * DIM * DIM * DIM
    private const val PREVIEW = 256
    private const val TAG = "FilterSlap-V6"
    private data class Model(val env: OrtEnvironment, val session: OrtSession, val bases: FloatArray)
    @Volatile private var instance: Model? = null

    private fun load(context: Context): Model {
        instance?.let { return it }
        return synchronized(this) {
            instance?.let { return@synchronized it }
            // Fail closed: neither model nor weights are ever silently replaced with random assets.
            val bytes = context.assets.open(MODEL_FILE).use { it.readBytes() }
            val weights = context.assets.open(LUT_FILE).use { it.readBytes() }
            require(weights.size == 4 * 3 * BASIS_SIZE) { "Unexpected 3D LUT asset size" }
            val meta = context.assets.open("v6_model_sha256.txt").bufferedReader().use { it.readLines() }
                .associate { val kv = it.split(" ", limit=2); require(kv.size==2); kv[0] to kv[1] }
            fun checksum(data: ByteArray) = MessageDigest.getInstance("SHA-256")
                .digest(data).joinToString("") { "%02x".format(it) }
            require(checksum(bytes) == meta[MODEL_FILE] && checksum(weights) == meta[LUT_FILE]) {
                "Model asset integrity failure"
            }
            val floats = weights.size/4
            val bb = ByteBuffer.wrap(weights).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val bases = FloatArray(floats)
            bb.get(bases)
            require(bases.all { it.isFinite() }) { "Invalid LUT basis" }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            val session = try { env.createSession(bytes, options) } finally { options.close() }
            val model = Model(env, session, bases)
            instance = model
            model
        }
    }

    // Bitmap scaling matches the pretrained classifier's 256x256 bilinear image resize.
    internal fun predict(context: Context, original: Bitmap): FloatArray {
        val model = load(context)
        val scaled = Bitmap.createScaledBitmap(original, PREVIEW, PREVIEW, true)
        val pixels = IntArray(PREVIEW * PREVIEW)
        try { scaled.getPixels(pixels, 0, PREVIEW, 0, 0, PREVIEW, PREVIEW) }
        finally { if (scaled !== original) scaled.recycle() }
        val input = FloatArray(3 * pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            input[i] = (p ushr 16 and 255)/255f
            input[pixels.size + i] = (p ushr 8 and 255)/255f
            input[2*pixels.size + i] = (p and 255)/255f
        }
        OnnxTensor.createTensor(model.env, java.nio.FloatBuffer.wrap(input), longArrayOf(1,3,PREVIEW.toLong(),PREVIEW.toLong())).use { tensor ->
            model.session.run(mapOf(model.session.inputNames.first() to tensor)).use { result ->
                val coefficients = (result[0] as OnnxTensor).floatBuffer
                val values = FloatArray(3)
                coefficients.get(values)
                require(values.all { it.isFinite() && abs(it) < 50f }) { "Untrustworthy model prediction" }
                return values
            }
        }
    }

    internal fun fusedLut(context: Context, original: Bitmap): FloatArray {
        val model = load(context)
        val coefficients = predict(context, original)
        return FloatArray(BASIS_SIZE) { i -> coefficients[0] * model.bases[i] +
            coefficients[1] * model.bases[BASIS_SIZE + i] +
            coefficients[2] * model.bases[2 * BASIS_SIZE + i] }
    }

    /** Identity and boundary behavior tested separately in host unit tests. */
    internal fun sample(lut: FloatArray, r: Float, g: Float, b: Float, dest: FloatArray = FloatArray(3)): FloatArray {
        require(lut.size == BASIS_SIZE)
        val xr = r.coerceIn(0f,1f)*(DIM-1); val yg = g.coerceIn(0f,1f)*(DIM-1)
        val zb = b.coerceIn(0f,1f)*(DIM-1)
        val ri = floor(xr).toInt().coerceIn(0,DIM-2)
        val gi = floor(yg).toInt().coerceIn(0,DIM-2)
        val bi = floor(zb).toInt().coerceIn(0,DIM-2)
        val rf = xr-ri; val gf = yg-gi; val bf = zb-bi
        dest.fill(0f)
        for (bz in 0..1) for (gy in 0..1) for (rx in 0..1) {
            val weight = (if (bz==0) 1f-bf else bf) * (if (gy==0) 1f-gf else gf) *
                (if (rx==0) 1f-rf else rf)
            val pos = ((bi+bz)*DIM + gi+gy)*DIM + ri+rx
            for (c in 0..2) dest[c] += lut[c*DIM*DIM*DIM + pos] * weight
        }
        return dest
    }

    private fun render(context: Context, original: Bitmap): Pair<Bitmap,Float> {
        val model = load(context)
        val coeff = predict(context,original)
        val fused = FloatArray(BASIS_SIZE)
        for (i in 0 until BASIS_SIZE) fused[i] =
            coeff[0]*model.bases[i] + coeff[1]*model.bases[BASIS_SIZE+i] + coeff[2]*model.bases[2*BASIS_SIZE+i]
        val output = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888)
        val source = IntArray(original.width)
        val dest = IntArray(original.width)
        val interpolated = FloatArray(3)
        var outside = 0L
        val total = original.width.toLong()*original.height*3
        try {
            for (y in 0 until original.height) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Photo render interrupted")
                original.getPixels(source,0,original.width,0,y,original.width,1)
                for (x in source.indices) {
                    val p=source[x]
                    val rr=(p ushr 16 and 255)/255f; val gg=(p ushr 8 and 255)/255f; val bb=(p and 255)/255f
                    val out=sample(fused,rr,gg,bb,interpolated)
                    for (c in out) if (c < 0f || c > 1f || !c.isFinite()) outside++
                    // Limit the model's output to a bounded natural-color edit and blend with the original.
                    // This safeguard is image-independent, not a photo-specific preset.
                    val a=0.72f
                    val r=(255f*(rr*(1f-a)+out[0].coerceIn(0f,1f)*a)).roundToInt().coerceIn(0,255)
                    val g=(255f*(gg*(1f-a)+out[1].coerceIn(0f,1f)*a)).roundToInt().coerceIn(0,255)
                    val b=(255f*(bb*(1f-a)+out[2].coerceIn(0f,1f)*a)).roundToInt().coerceIn(0,255)
                    dest[x]=Color.rgb(r,g,b)
                }
                output.setPixels(dest,0,original.width,0,y,original.width,1)
            }
            return output to (outside.toFloat()/total)
        } catch (error:Throwable) {
            output.recycle()
            throw error
        }
    }

    fun process(context: Context, original: Bitmap, analysis: AnalysisResult): RenderResult {
        val started = System.currentTimeMillis()
        val intent = ContextualDecisionEngine.evaluate(analysis)
        // No-edit stays a first-class decision. Model scores alone cannot authorize editing.
        val enoughEvidence = intent.decisions.any { it.parameter in setOf(
            "Exposure","Shadows","Highlights","Blacks","Whites","Contrast","WhiteBalance",
            "WhiteBalanceTint","Vibrance","Saturation") }
        if (!enoughEvidence) return ImageProcessor.process(original,analysis)
        var candidate:Bitmap? = null
        try {
            val (rendered, gamut)=render(context,original)
            candidate=rendered
            val pw=min(64,original.width); val ph=min(64,original.height)
            val base=Bitmap.createScaledBitmap(original,pw,ph,true)
            val edit=Bitmap.createScaledBitmap(rendered,pw,ph,true)
            val a=IntArray(pw*ph);val b=IntArray(pw*ph)
            try { base.getPixels(a,0,pw,0,0,pw,ph);edit.getPixels(b,0,pw,0,0,pw,ph) }
            finally {if (base !== original)base.recycle();if(edit !== rendered)edit.recycle()}
            val gate=V6DecisionPolicy.decide(a,b,gamut,enoughEvidence)
            if (!gate.accepted) {
                Log.i(TAG,"Falling back to V5: ${gate.reason}")
                rendered.recycle();candidate=null
                return ImageProcessor.process(original,analysis)
            }
            candidate=null // transfer ownership of bitmap to RenderResult
            return RenderResult(rendered,ProcessingStats(computeTimeMs=System.currentTimeMillis()-started,
                    stagesApplied=setOf("V6 pretrained adaptive color/tone")),
                EditPlan(listOf(EditDecision("AdaptiveColorTone","Global","ColorTone",0.72f,
                    0.6f,0.5f,0.5f)),"V6_LEARNED", "Pretrained image-adaptive color/tone; passed objective output checks, aesthetic quality unverified"),
                analysis)
        } catch (t: Exception) {
            Log.w(TAG,"V6 not applied; preserving V5 correction path",t)
            candidate?.recycle()
            return ImageProcessor.process(original,analysis)
        } catch (t:LinkageError) {
            Log.w(TAG,"V6 inference library unavailable; retaining V5",t)
            candidate?.recycle()
            return ImageProcessor.process(original,analysis)
        }
    }
}
