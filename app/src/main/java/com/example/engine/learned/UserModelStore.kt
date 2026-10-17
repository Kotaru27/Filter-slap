package com.example.engine.learned

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Private, fully offline user-supplied predictor store.
 *
 * NO pretrained checkpoints ship with the app. Import is an explicit,
 * debug-only research action for ONNX predictors that the user is legally
 * entitled to run. Never silently download, trust a user filename, or
 * substitute an unverified checkpoint.
 */
internal object UserModelStore {
    private const val MAX_BYTES=12_000_000
    private const val NAME="user-approved-global-predictor.onnx"
    private const val PREFERENCES="private_learned_research"
    private const val PREF_HASH="sha256"

    data class Installed(val bytes:ByteArray,val sha256:String)

    private fun digest(bytes:ByteArray):String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){ "%02x".format(it) }

    private fun validateOnnx(bytes:ByteArray) {
        require(bytes.isNotEmpty()&&bytes.size<=MAX_BYTES) {"Model size is not supported"}
        val env=OrtEnvironment.getEnvironment()
        val options=OrtSession.SessionOptions()
        options.setIntraOpNumThreads(2)
        val session=try {env.createSession(bytes,options)}
            finally{options.close()}
        session.use {
            require(it.inputNames==setOf("srgb_context")){"Unexpected model input"}
            require(it.outputNames==setOf("guided_affine_grid")){"Unexpected model output"}
            val input=it.inputInfo["srgb_context"]?.info as? ai.onnxruntime.TensorInfo
                ?: error("Model input must be a tensor")
            val output=it.outputInfo["guided_affine_grid"]?.info as? ai.onnxruntime.TensorInfo
                ?: error("Model output must be a tensor")
            require(input.shape.contentEquals(longArrayOf(1,3,256,256))) {
                "Predictor input must be [1,3,256,256]"
            }
            require(output.shape.contentEquals(longArrayOf(1,12,8,1,1))) {
                "Research lab accepts global learned-photo models only"
            }
        }
    }

    fun importFromDocument(context:Context,uri:Uri):Installed {
        val data=context.contentResolver.openInputStream(uri)?.use { stream ->
            val output=ByteArrayOutputStream()
            val chunk=ByteArray(32768)
            while(true) {
                val n=stream.read(chunk)
                if(n<0)break
                require(output.size()+n<=MAX_BYTES) {"Model exceeds the 12 MB limit"}
                output.write(chunk,0,n)
            }
            output.toByteArray()
        } ?: throw java.io.IOException("Unable to read selected ONNX model")
        validateOnnx(data)
        val hash=digest(data)
        val file=File(context.noBackupFilesDir,NAME)
        val tmp=File(context.noBackupFilesDir,NAME+".tmp")
        try {
            tmp.outputStream().use {
                it.write(data)
                it.fd.sync()
            }
            if(!tmp.renameTo(file)) {
                throw java.io.IOException("Unable to save verified predictor")
            }
            context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
                .edit().putString(PREF_HASH,hash).apply()
            return Installed(data,hash)
        } finally{tmp.delete()}
    }

    fun loadIfVerified(context:Context):Installed? {
        val expected=context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
            .getString(PREF_HASH,null) ?:return null
        if(!expected.matches(Regex("[0-9a-f]{64}")))return null
        val file=File(context.noBackupFilesDir,NAME)
        if(!file.isFile || file.length() !in 1..MAX_BYTES.toLong())return null
        val bytes=file.readBytes()
        if(!digest(bytes).equals(expected,ignoreCase=true))return null
        return Installed(bytes,expected)
    }

    fun clear(context:Context) {
        context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
            .edit().remove(PREF_HASH).apply()
        File(context.noBackupFilesDir,NAME).delete()
    }
}
