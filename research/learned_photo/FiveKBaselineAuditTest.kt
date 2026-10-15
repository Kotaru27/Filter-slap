package com.example.engine.v7

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Research-only V9 baseline for the exact FiveK held-out photo set.
 * Copied into app's TEST source at CI runtime; no production app source or
 * editing algorithm is modified. Never posts licensed image bytes publicly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FiveKBaselineAuditTest {
    @Test fun identicalHeldOutPhotosThroughV9Renderer() {
        val input=System.getenv("FIVEK_V9_INPUT")
            ?: throw IllegalStateException("FIVEK_V9_INPUT is mandatory")
        val output=System.getenv("FIVEK_V9_OUTPUT")
            ?: throw IllegalStateException("FIVEK_V9_OUTPUT is mandatory")
        val dir=File(input)
        require(dir.isDirectory) { "Research test data missing" }
        val destination=File(output)
        check(destination.mkdirs() || destination.isDirectory)
        val files=dir.listFiles()!!.filter { it.name.endsWith("_source.png") }
            .sortedBy { it.name }
        assertTrue("Expected held-out research photographs",files.size>=16)
        val context=ApplicationProvider.getApplicationContext<Context>()
        var count=0
        for(file in files) {
            val original=BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalArgumentException("Failed source PNG decode")
            var edited:Bitmap?=null
            try {
                val analysis=ImageAnalyzer.analyze(original)
                val rendered=OfflineEnhancer.process(context,original,analysis)
                edited=rendered.bitmap
                assertTrue("V9 output failed built-in damage check",rendered.outcome!!.damage.safe)
                assertEquals(original.width,edited.width)
                assertEquals(original.height,edited.height)
                val ident="fivek_"+file.name.removeSuffix("_source.png")
                val target=File(destination,"$ident.png")
                target.outputStream().use {
                    assertTrue(edited.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                count++
            } finally {
                if(edited!=null && edited!==original && !edited.isRecycled) edited.recycle()
                if(!original.isRecycled) original.recycle()
            }
        }
        assertEquals(files.size,count)
    }
}
