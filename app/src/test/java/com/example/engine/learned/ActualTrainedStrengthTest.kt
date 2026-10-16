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

/** Exploratory research: strongest fixed candidate with ORIGINAL safety gate.
 * Research-only licensed photos and model weights never published or bundled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ActualTrainedStrengthTest {
    @Test fun trainedModelRunsOnIndependentHeldOutSourcesAndPreservesFallback() {
        val sourceDir=File(requireNotNull(System.getenv("TRAINED_TEST_SOURCE")))
        val outputs=File(requireNotNull(System.getenv("TRAINED_STRENGTH_OUTPUT")))
        val modelFile=File(requireNotNull(System.getenv("TRAINED_TEST_ONNX")))
        val mode=requireNotNull(System.getenv("TRAINED_TEST_MODE"))
        require(sourceDir.isDirectory && modelFile.isFile)
        check(outputs.mkdirs()||outputs.isDirectory)
        val bytes=modelFile.readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(""){"%02x".format(it)}
        val files=sourceDir.listFiles()!!.filter{it.name.endsWith("_source.png")}
            .sortedBy{it.name}
        assertEquals(16,files.size)
        val rows=mutableListOf("id,decision,strength,mean_change,new_clipping,new_crushing,hue_shift,vivid_boost")
        for(file in files) {
            val photo=BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalArgumentException("Failed source decode")
            val id="fivek_"+file.name.removeSuffix("_source.png")
            try {
                // Chosen strength depends ONLY on source and unchanged safety
                // limits; no hidden expert image or V9 score influences it.
                val decision=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(
                    photo,bytes,hash,mode)
                if(decision==null) {
                    rows+="$id,rejected,0,0,0,0,0,0"
                    continue
                }
                try {
                    val result=decision.preview
                    assertTrue(result.damage.safe)
                    assertEquals(photo.width,result.bitmap.width)
                    assertEquals(photo.height,result.bitmap.height)
                    val d=result.damage
                    File(outputs,"$id.png").outputStream().use {
                        assertTrue(result.bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                    rows+="$id,accepted,${decision.strength},${d.meanChange},${d.newlyClipped},${d.newlyCrushed},${d.hueShifted},${d.vividBoosted}"
                }finally{decision.preview.bitmap.recycle()}
            }finally{photo.recycle()}
        }
        File(outputs,"decisions.csv").writeText(rows.joinToString("\n")+"\n")
        assertEquals(17,rows.size)
    }
}
