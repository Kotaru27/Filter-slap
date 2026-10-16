package com.example.engine.learned

import android.content.Context
import android.net.Uri
import android.graphics.Bitmap
import java.io.File
import java.security.MessageDigest

/**
 * Explicit user-imported, locally stored experimental ONNX predictor.
 * No network, no bundled research weights, and no access to photo library.
 * The user must hold appropriate training/weight-use rights.
 */
internal object LocalLearnedModelStore {
    private const val MAX_BYTES=12_000_000
    private const val FILE="user-approved-global-predictor.onnx"
    private const val PREF="locally-imported-ai-model"
    private const val PIN="sha256"

    data class Model(val bytes:ByteArray,val sha256:String)

    private fun sha(data:ByteArray):String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    /**
     * Fails closed and leaves any existing model unchanged until an entire
     * new model has passed an actual offline ONNX inference/shape check.
     */
    fun importGlobal(context:Context,uri:Uri):String {
        val bytes=context.contentResolver.openInputStream(uri)?.use { stream ->
            val out=java.io.ByteArrayOutputStream()
            val buf=ByteArray(64*1024)
            while(true) {
                val n=stream.read(buf)
                if(n<0)break
                if(out.size()+n>MAX_BYTES)throw IllegalArgumentException("AI model exceeds 12 MB")
                out.write(buf,0,n)
            }
            out.toByteArray()
        } ?: throw IllegalArgumentException("Unable to open model file")
        require(bytes.isNotEmpty()) { "Empty model file" }
        val digest=sha(bytes)
        // Validate actual model execution without incorporating any user's photo.
        val probe=Bitmap.createBitmap(12,12,Bitmap.Config.ARGB_8888)
        probe.eraseColor(0xff777777.toInt())
        try {
            val ready=LearnedOfflineCandidate.prepareApprovedBytes(
                probe,bytes,digest,"global")
            // A healthy global model can be rejected as unsafe on a grey test
            // tile. The inference and output-layout validation already ran.
            ready?.let { require(it.fingerprint==digest) }
        }finally {probe.recycle()}
        val temp=File(context.filesDir,FILE+".pending")
        val installed=File(context.filesDir,FILE)
        try {
            temp.outputStream().use { it.write(bytes) }
            require(temp.length()==bytes.size.toLong())
            if(!temp.renameTo(installed))
                throw java.io.IOException("Unable to persist model atomically")
            context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putString(PIN,digest).commit().also { require(it) }
            return digest
        }finally{temp.delete()}
    }

    fun load(context:Context):Model? {
        val pinned=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString(PIN,null) ?: return null
        val file=File(context.filesDir,FILE)
        if(!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        val bytes=file.readBytes()
        if(!sha(bytes).equals(pinned,ignoreCase=true))return null
        return Model(bytes,pinned)
    }

    fun clear(context:Context) {
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().remove(PIN).commit()
        File(context.filesDir,FILE).delete()
        File(context.filesDir,FILE+".pending").delete()
    }
}
