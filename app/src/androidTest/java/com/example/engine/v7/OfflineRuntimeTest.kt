package com.example.engine.v7

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.engine.ImageAnalyzer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Instrumented test of actual Android pixels, native ONNX and the app's save function. */
@RunWith(AndroidJUnit4::class)
class OfflineRuntimeTest {
    @Test fun bundled_models_render_and_save_with_the_same_recipe() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val testContext=InstrumentationRegistry.getInstrumentation().context
        val source=testContext.assets.open("private-audit.jpg").use { BitmapFactory.decodeStream(it) }!!
        val analysis=ImageAnalyzer.analyze(source)
        val result=OfflineEnhancer.process(context,source,analysis)
        assertTrue(result.outcome!!.damage.safe)
        assertTrue("Bundled color model did not run",result.recipe!!.modelStrength>0f)
        assertNotNull("Bundled foreground model did not run",result.recipe!!.mask)
        val exported=OfflineEnhancer.render(source,result.recipe!!,analysis)
        assertTrue(result.bitmap.sameAs(exported.bitmap))
        val uri=com.example.saveBitmapToGallery(context,exported.bitmap)
        assertNotNull("App gallery save failed",uri)
        val reopened=context.contentResolver.openInputStream(uri!!).use { BitmapFactory.decodeStream(it) }!!
        assertEquals(source.width,reopened.width);assertEquals(source.height,reopened.height)
        File(testContext.filesDir,"audit-output.jpg").outputStream().use {
            exported.bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)
        }
        File(testContext.filesDir,"audit-result.txt").writeText("Outcome: ${result.outcome}\nPlan: ${result.appliedPlan}\nSaved: $uri\n")
        reopened.recycle();exported.bitmap.recycle();result.bitmap.recycle();source.recycle()
    }
}
