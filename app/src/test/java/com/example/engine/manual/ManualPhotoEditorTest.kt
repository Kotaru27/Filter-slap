package com.example.engine.manual

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualPhotoEditorTest {
    private fun src(w:Int=47,h:Int=31):Bitmap =
        Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also { bitmap ->
            for(y in 0 until h)for(x in 0 until w) {
                bitmap.setPixel(x,y,
                    (255 shl 24) or
                    (((x*3+45).coerceAtMost(255)) shl 16) or
                    (((y*5+25).coerceAtMost(255)) shl 8) or
                    ((x+y*2+30).coerceAtMost(255)))
            }
        }

    @Test fun zeroValuesReturnBitwiseOriginalWithoutModifyingSource() {
        val input=src();try {
            val result=ManualPhotoEditor.render(input,ManualAdjustments())
            try{assertTrue(input.sameAs(result))}finally{result.recycle()}
        }finally{input.recycle()}
    }

    @Test fun fullResolutionEditsAreDeterministicAndKeepAlpha() {
        val input=src(59,37)
        val recipe=ManualAdjustments(exposure=.35f,contrast=12f,
            shadows=24f,highlights=-30f,temperature=10f,saturation=7f,vignette=18f)
        try {
            val a=ManualPhotoEditor.render(input,recipe)
            val b=ManualPhotoEditor.render(input,recipe)
            try {
                assertEquals(input.width,a.width);assertEquals(input.height,a.height)
                assertTrue(a.sameAs(b))
                assertFalse(input.sameAs(a))
                assertEquals(255,a.getPixel(4,5) ushr 24)
            }finally {a.recycle();b.recycle()}
        }finally{input.recycle()}
    }

    @Test fun positiveExposureIncreasesBrightnessAcrossEntireFrame() {
        val input=Bitmap.createBitmap(17,13,Bitmap.Config.ARGB_8888)
        input.eraseColor(0xff404040.toInt())
        try {
            val edited=ManualPhotoEditor.render(input,ManualAdjustments(exposure=1f))
            try {
                for(y in 0 until 13)for(x in 0 until 17)
                    assertTrue((edited.getPixel(x,y) and 255)>(input.getPixel(x,y) and 255))
            }finally{edited.recycle()}
        }finally{input.recycle()}
    }

    @Test fun shadowAdjustmentTargetsDarkRegionsMoreThanBrightRegions() {
        val input=Bitmap.createBitmap(2,1,Bitmap.Config.ARGB_8888)
        input.setPixel(0,0,0xff303030.toInt())
        input.setPixel(1,0,0xffd8d8d8.toInt())
        try {
            val edited=ManualPhotoEditor.render(input,ManualAdjustments(shadows=60f))
            try {
                val dark=(edited.getPixel(0,0) and 255)-48
                val light=(edited.getPixel(1,0) and 255)-216
                assertTrue("Shadows should mainly target dark tones",dark>light+10)
            }finally{edited.recycle()}
        }finally{input.recycle()}
    }

    @Test fun saturationZeroesGrayscaleWithoutCorruptingTransparency() {
        val input=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
        input.setPixel(0,0,0x80d04020.toInt())
        try {
            val edited=ManualPhotoEditor.render(input,ManualAdjustments(saturation=-100f))
            try{
                val p=edited.getPixel(0,0)
                val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
                assertTrue(kotlin.math.abs(r-g)<=1)
                assertTrue(kotlin.math.abs(g-b)<=1)
                assertEquals(0x80,p ushr 24)
            }finally{edited.recycle()}
        }finally{input.recycle()}
    }

    @Test fun invalidSettingsFailAndCancellationIsHonored() {
        val input=src()
        try {
            try{ManualPhotoEditor.render(input,ManualAdjustments(exposure=4f));fail("invalid EV")}
            catch(_:IllegalArgumentException){}
            var invoked=0
            try {
                ManualPhotoEditor.render(input,ManualAdjustments(contrast=22f)){
                    invoked++
                    throw java.util.concurrent.CancellationException("cancel")
                }
                fail("Ignored cancellation")
            }catch(_:java.util.concurrent.CancellationException){}
            assertEquals(1,invoked)
            assertFalse(input.isRecycled)
        }finally{input.recycle()}
    }
}
