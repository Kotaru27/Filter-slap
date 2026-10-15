package com.example.engine.learned

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * Research-only use of REAL FiveK-trained predictor on unchanged held-out
 * photos. Source, model and output stay in the ephemeral CI runner.
 * No licensed files/checkpoints are committed or uploaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActualTrainedPhotoParityTest {
    @Test fun offlineTrainedModelProducesNativeResolutionOutputsOrSafeRejection() {
        // Ordinary Android unit suites do not include restricted research
        // data. Explicit research CI provides all four variables, and the
        // subsequent metrics step REQUIRES this test's decision CSV.
        val input=System.getenv("TRAINED_TEST_SOURCE")
        org.junit.Assume.assumeTrue("Research data must be supplied separately",input!=null)
        val photoDir=File(requireNotNull(input))
        val outputDir=File(requireNotNull(System.getenv("TRAINED_ANDROID_OUTPUT")))
        val modelFile=File(requireNotNull(System.getenv("TRAINED_TEST_ONNX")))
        val mode=requireNotNull(System.getenv("TRAINED_TEST_MODE"))
        assertTrue(photoDir.isDirectory)
        assertTrue(modelFile.isFile)
        assertTrue(outputDir.mkdirs() || outputDir.isDirectory)
        val bytes=modelFile.readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){"%02x".format(it)}
        val photos=photoDir.listFiles()!!.filter{it.name.endsWith("_source.png")}
            .sortedBy{it.name}
        assertEquals(16,photos.size)
        var accepted=0
        val rows=mutableListOf("id,decision,mean_change,new_clipping,new_crushing,hue_shift,vivid_boost")
        for(photo in photos) {
            val bitmap=BitmapFactory.decodeFile(photo.absolutePath)
                ?: throw IllegalStateException("Invalid source")
            val ident="fivek_"+photo.name.removeSuffix("_source.png")
            try {
                var diagnostics:com.example.engine.v7.DamageReport?=null
                val output=LearnedOfflineCandidate.previewApprovedBytes(
                    bitmap,bytes,hash,mode,observeDamage={ diagnostics=it })
                val damage=checkNotNull(diagnostics) { "No final pixel evidence collected" }
                val properties="${damage.meanChange},${damage.newlyClipped}," +
                    "${damage.newlyCrushed},${damage.hueShifted},${damage.vividBoosted}"
                if(output==null) {
                    assertFalse(damage.safe)
                    rows += "$ident,rejected,$properties"
                    continue
                }
                try {
                    assertEquals(bitmap.width,output.bitmap.width)
                    assertEquals(bitmap.height,output.bitmap.height)
                    assertEquals(hash,output.fingerprint)
                    assertTrue(output.damage.safe)
                    File(outputDir,"$ident.png").outputStream().use {
                        assertTrue(output.bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                    assertTrue(damage.safe)
                    rows += "$ident,accepted,$properties"
                    accepted++
                }finally {output.bitmap.recycle()}
            }finally {bitmap.recycle()}
        }
        File(outputDir,"decisions.csv").writeText(rows.joinToString("\n")+"\n")
        assertEquals(16,rows.size-1)
        assertTrue("Trained model rejected every held-out photograph",accepted>0)
    }
}
