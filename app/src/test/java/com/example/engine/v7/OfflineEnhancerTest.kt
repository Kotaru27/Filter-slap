package com.example.engine.v7

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class OfflineEnhancerTest {
    @Test fun `analyzer does not recycle caller input when scaling is identity`() {
        for(size in intArrayOf(1,16,256,512)) {
            val source=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888)
            source.eraseColor(0xff777777.toInt())
            ImageAnalyzer.analyze(source)
            assertFalse(source.isRecycled)
            assertEquals(0xff777777.toInt(),source.getPixel(0,0))
            source.recycle()
        }
    }
    @Test fun `cancelled preparation must not fall back or load models`() {
        val source=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff777777.toInt())
        val analysis=ImageAnalyzer.analyze(source)
        val context=ApplicationProvider.getApplicationContext<Context>()
        try {
            OfflineEnhancer.process(context,source,analysis) { throw CancellationException("test") }
            fail("Cancellation was swallowed")
        } catch(expected: CancellationException) { assertFalse(source.isRecycled) }
        source.recycle()
    }
    @Test fun `cancelled row rendering does not consume the input bitmap`() {
        val source=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff666666.toInt())
        val analysis=ImageAnalyzer.analyze(source)
        val recipe=EditRecipe(null,null,0f,.10f,0f,0f,1f,"test","none",emptyList())
        var checks=0
        try {
            OfflineEnhancer.render(source,recipe,analysis,{ if(++checks==4) throw CancellationException("test") })
            fail("Cancelled renderer continued")
        } catch(expected: CancellationException) { assertFalse(source.isRecycled) }
        assertEquals(4,checks)
        source.recycle()
    }
    @Test fun `recipe remains unchanged across preview and export`() {
        val preview=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        val export=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888)
        preview.eraseColor(0xff665544.toInt());export.eraseColor(0xff665544.toInt())
        val analysis=ImageAnalyzer.analyze(preview)
        val recipe=EditRecipe(null,null,0f,.1f,0f,.12f,1f,"same-recipe","none",emptyList())
        val a=OfflineEnhancer.render(preview,recipe,analysis)
        val b=OfflineEnhancer.render(export,recipe,analysis)
        assertSame(recipe,a.recipe);assertSame(recipe,b.recipe)
        assertEquals(a.bitmap.getPixel(16,16),b.bitmap.getPixel(64,64))
        assertEquals(a.outcome!!.recipeId,b.outcome!!.recipeId)
        a.bitmap.recycle();b.bitmap.recycle();preview.recycle();export.recycle()
    }
}
