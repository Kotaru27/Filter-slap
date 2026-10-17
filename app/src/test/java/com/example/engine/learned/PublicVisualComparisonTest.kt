package com.example.engine.learned

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import com.example.engine.v7.OfflineEnhancer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/** Unmodified public-domain scene comparison. Never feeds back into model.
 * Outputs are the REAL rendered pixels from V9 and native guarded ONNX.
 * This test requires a separately trained, ephemeral research checkpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicVisualComparisonTest {
    @Test fun publicScenesMustExportOriginalV9AndGuardedLearnedOrFallback() {
        // This audit is only meaningful with the separately trained,
        // restricted research checkpoint. Ordinary public build CI NEVER
        // bundles that model; skip rather than synthesize fake weights.
        org.junit.Assume.assumeTrue(
            "Research-only trained checkpoint not present",
            System.getenv("TRAINED_TEST_ONNX")!=null
        )
        val input=File(requireNotNull(System.getenv("PUBLIC_VISUAL_INPUT")))
        val output=File(requireNotNull(System.getenv("PUBLIC_VISUAL_OUTPUT")))
        val model=File(requireNotNull(System.getenv("TRAINED_TEST_ONNX")))
        val mode=requireNotNull(System.getenv("TRAINED_TEST_MODE"))
        assertTrue(input.isDirectory && model.isFile)
        check(output.mkdirs() || output.isDirectory)
        val weights=model.readBytes()
        val sha=MessageDigest.getInstance("SHA-256").digest(weights)
            .joinToString(""){"%02x".format(it)}
        val photos=input.listFiles()!!.filter{it.extension=="png"}.sortedBy{it.name}
        assertEquals(6,photos.size)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manifest=mutableListOf("photo,decision,strength,original_width,original_height")
        for(photo in photos) {
            val source=BitmapFactory.decodeFile(photo.absolutePath)
                ?: throw IllegalStateException("Missing public-domain photo")
            var old:Bitmap?=null
            var learned:Bitmap?=null
            try {
                val analysis=ImageAnalyzer.analyze(source)
                val baseline=OfflineEnhancer.process(context,source,analysis)
                old=baseline.bitmap
                assertTrue(requireNotNull(baseline.outcome).damage.safe)
                assertEquals(source.width,old.width)
                assertEquals(source.height,old.height)
                val selection=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(
                    source,weights,sha,mode)
                val proposed=selection?.preview?.bitmap
                learned=proposed
                val fallback=selection==null
                if(!fallback){
                    assertTrue(selection!!.preview.damage.safe)
                    assertEquals(source.width,proposed!!.width)
                    assertEquals(source.height,proposed.height)
                }
                fun write(bitmap:Bitmap,name:String){
                    File(output,photo.nameWithoutExtension+"-"+name+".png")
                        .outputStream().use {
                            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                        }
                }
                write(source,"original")
                write(old,"v9")
                write(if(fallback)old else proposed!!,"guarded")
                manifest+=listOf(photo.nameWithoutExtension,
                    if(fallback)"v9_fallback" else "learned",
                    selection?.strength?.toString() ?: "0",
                    source.width,source.height).joinToString(",")
            }finally{
                if(learned!=null&&learned!==source&&!learned.isRecycled)learned.recycle()
                if(old!=null&&old!==source&&!old.isRecycled)old.recycle()
                if(!source.isRecycled)source.recycle()
            }
        }
        File(output,"manifest.csv").writeText(manifest.joinToString("\n")+"\n")
        assertEquals(7,manifest.size)
    }
}
