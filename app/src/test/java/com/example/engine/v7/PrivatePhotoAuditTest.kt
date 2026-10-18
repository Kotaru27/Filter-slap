package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Opt-in local real-photo audit. Private image bytes are never committed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrivatePhotoAuditTest {
    @Test fun `public reference photos have safe repeatable recipes`() {
        val location=System.getenv("FILTER_SLAP_AUDIT_DIRECTORY")
        assumeTrue(location!=null && File(location).isDirectory)
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(System.getenv("FILTER_SLAP_AUDIT_OUTPUT") ?: System.getProperty("java.io.tmpdir"))
        directory.mkdirs()
        val notes=mutableListOf<String>()
        for(file in File(location!!).listFiles()!!.filter { it.extension.lowercase() in listOf("jpg","jpeg","png") }.sortedBy { it.name }) {
            val source=BitmapFactory.decodeFile(file.absolutePath)!!
            val analysis=ImageAnalyzer.analyze(source)
            val result=OfflineEnhancer.process(context,source,analysis)
            assertTrue("Unsafe result on ${file.name}",result.outcome!!.damage.safe)
            val exported=OfflineEnhancer.render(source,result.recipe!!,analysis)
            assertTrue("Recipe drift on ${file.name}",exported.bitmap.sameAs(result.bitmap))
            File(directory,"reference-${file.nameWithoutExtension}.jpg").outputStream().use {
                result.bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)
            }
            notes += "${file.name}: ${result.outcome}"
            exported.bitmap.recycle();result.bitmap.recycle();source.recycle()
        }
        assertTrue(notes.isNotEmpty())
        File(directory,"reference-audit.txt").writeText(notes.joinToString("\n"))
    }
    @Test fun `real photo uses classical processing and identical full resolution export`() {
        val input=System.getenv("FILTER_SLAP_AUDIT_INPUT")
        assumeTrue(input!=null && File(input).isFile)
        val source=BitmapFactory.decodeFile(input)!!
        val context=ApplicationProvider.getApplicationContext<Context>()
        try {
            val analysis=ImageAnalyzer.analyze(source)
            val result=OfflineEnhancer.process(context,source,analysis)
            try {
                assertTrue(result.outcome!!.damage.safe)
                assertEquals(0f,result.recipe!!.modelStrength,0f)
                assertNull("Model-based segmentation must never execute",result.recipe!!.mask)
                assertEquals("V14_CLASSICAL_AUTOEDIT_NO_AI",result.outcome!!.engine)
                val export=OfflineEnhancer.render(source,result.recipe!!,analysis)
                try {
                    assertTrue(export.bitmap.sameAs(result.bitmap))
                }finally { if(export.bitmap!==source)export.bitmap.recycle() }
            }finally { if(result.bitmap!==source)result.bitmap.recycle() }
        }finally{ source.recycle() }
    }
}
