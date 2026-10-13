package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.security.MessageDigest

/** Bundled 4.6 MB U2NETP salient-foreground model. No download or network code. */
internal object OfflineForeground {
    const val SHA256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
    @Volatile private var session: OrtSession? = null
    private val environment get() = OrtEnvironment.getEnvironment()
    private fun load(context: Context): OrtSession {
        session?.let { return it }
        return synchronized(this) {
            session?.let { return@synchronized it }
            val bytes=context.assets.open("v7_foreground.onnx").use { it.readBytes() }
            val actual=MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
            require(actual == SHA256) { "Foreground asset integrity failure" }
            val options=OrtSession.SessionOptions()
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            val loaded=try { environment.createSession(bytes,options) } finally { options.close() }
            session=loaded
            loaded
        }
    }
    fun predict(context: Context, input: Bitmap, checkCancelled: () -> Unit): MaskField? {
        checkCancelled()
        val model=load(context)
        val scaled=Bitmap.createScaledBitmap(input,320,320,true)
        val pixels=IntArray(320*320)
        try { scaled.getPixels(pixels,0,320,0,0,320,320) }
        finally { if(scaled !== input) scaled.recycle() }
        var maximum=1
        for(p in pixels) maximum=maxOf(maximum,p ushr 16 and 255,p ushr 8 and 255,p and 255)
        val means=floatArrayOf(.485f,.456f,.406f)
        val deviations=floatArrayOf(.229f,.224f,.225f)
        val data=FloatArray(pixels.size*3)
        for(c in 0..2) for(i in pixels.indices) {
            data[c*pixels.size+i]=((pixels[i] ushr (16-8*c) and 255)/maximum.toFloat()-means[c])/deviations[c]
        }
        checkCancelled()
        OnnxTensor.createTensor(environment,FloatBuffer.wrap(data),longArrayOf(1,3,320,320)).use { tensor ->
            model.run(mapOf(model.inputNames.first() to tensor)).use { outputs ->
                checkCancelled()
                val output=(outputs[0] as OnnxTensor).floatBuffer
                val values=FloatArray(320*320)
                output.get(values)
                return MaskField.fromProbabilities(values,320,320,pixels)
            }
        }
    }
}
