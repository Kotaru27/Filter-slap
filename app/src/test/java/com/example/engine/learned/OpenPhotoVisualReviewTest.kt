package com.example.engine.learned

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.engine.ImageAnalyzer
import com.example.engine.v7.OfflineEnhancer
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * Offline, side-by-side evaluation using ONLY explicit CC0/public-domain fixture
 * photos; no FiveK training images may enter the public artifact.
 * Both candidates run through the actual Android native editors on identical
 * input pixels, and the learned path keeps the exact existing damage gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenPhotoVisualReviewTest {
    @Test fun renderCc0OpenPhotosThroughBothRealAndroidEngines() {
        org.junit.Assume.assumeTrue("Needs explicitly supplied private research-trained model",
            System.getenv("TRAINED_TEST_ONNX")!=null)
        val input=File(requireNotNull(System.getenv("OPEN_PHOTO_INPUT")))
        val destination=File(requireNotNull(System.getenv("OPEN_PHOTO_OUTPUT")))
        val model=File(requireNotNull(System.getenv("TRAINED_TEST_ONNX")))
        val mode=requireNotNull(System.getenv("TRAINED_TEST_MODE"))
        require(input.isDirectory && model.isFile)
        check(destination.mkdirs() || destination.isDirectory)
        val samples=input.listFiles()!!.filter { it.extension.lowercase()=="png" }.sortedBy{it.name}
        assertEquals("Expected exactly six approved fixtures",6,samples.size)
        val bytes=model.readBytes()
        val pin=MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val report=mutableListOf("id,mode,learned_accepted,strength,mean_rgb_change")
        for(file in samples) {
            val id=file.nameWithoutExtension
            require(id in setOf("astronaut","coffee","chelsea","rocket","camera","clock"))
            val source=BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalArgumentException("Failed to decode approved fixture")
            var baseline:Bitmap?=null
            var challenger:Bitmap?=null
            try {
                val analysis=ImageAnalyzer.analyze(source)
                val render=OfflineEnhancer.process(context,source,analysis)
                val baselineImage=render.bitmap
                baseline=baselineImage
                require(render.outcome?.damage?.safe==true) { "V9 baseline failed" }
                val candidate=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(
                    source,bytes,pin,mode)
                if(candidate!=null) challenger=candidate.preview.bitmap
                val accepted=candidate!=null
                val chosen=challenger ?: baselineImage
                assertEquals(source.width,chosen.width)
                assertEquals(source.height,chosen.height)
                assertEquals(source.width,baselineImage.width)
                assertEquals(source.height,baselineImage.height)
                fun save(bitmap:Bitmap,tag:String) {
                    File(destination,"${id}_${tag}.png").outputStream().use { stream ->
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))
                    }
                }
                save(source,"source")
                save(baselineImage,"v9")
                save(chosen,"challenger")
                report += "$id,$mode,$accepted,${candidate?.strength ?: 0f},"+
                    "${candidate?.preview?.damage?.meanChange ?: 0f}"
            } finally {
                if(challenger!=null && challenger!==source && !challenger.isRecycled)challenger.recycle()
                if(baseline!=null && baseline!==source && !baseline.isRecycled)baseline.recycle()
                if(!source.isRecycled)source.recycle()
            }
        }
        File(destination,"decisions.csv").writeText(report.joinToString("\n")+"\n")
        assertEquals(7,report.size)
    }
}
