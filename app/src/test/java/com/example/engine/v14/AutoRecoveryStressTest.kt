package com.example.engine.v14

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import com.example.engine.v7.OfflineEnhancer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale
import kotlin.math.abs

/** Diagnostics on CC0 photos with generic damage. Never used by production grading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoRecoveryStressTest {
    private fun damaged(original:Bitmap,variant:Int):Bitmap {
        val w=original.width;val h=original.height
        val p=IntArray(w*h);original.getPixels(p,0,w,0,0,w,h)
        val result=IntArray(p.size)
        fun ch(x:Float)=(x+.5f).toInt().coerceIn(0,255)
        for(i in p.indices) {
            val src=p[i];val r=src ushr 16 and 255
            val g=src ushr 8 and 255;val b=src and 255
            val rr:Int;val gg:Int;val bb:Int
            when(variant) {
                0 -> {rr=ch(r*.66f);gg=ch(g*.66f);bb=ch(b*.66f)}
                1 -> {rr=ch(r*.76f+29f);gg=ch(g*.76f+29f);bb=ch(b*.76f+29f)}
                else -> {rr=ch(r*.8f);gg=ch(g*1.04f);bb=ch(b*1.16f)}
            }
            result[i]=(src and -0x1000000) or (rr shl 16) or
                (gg shl 8) or bb
        }
        return Bitmap.createBitmap(result,w,h,Bitmap.Config.ARGB_8888)
    }
    private fun luma(x:Int)=((x ushr 16 and 255)*.2126+
        (x ushr 8 and 255)*.7152+(x and 255)*.0722)/255.0

    @Test fun realAndroidAutomaticEngineRunsOnOrdinaryPhotoDefects() {
        val inputs=System.getenv("PHOTO_COMPARE_INPUT")
        val outputs=System.getenv("PHOTO_COMPARE_OUTPUT")
        assumeTrue(inputs!=null && outputs!=null)
        val sources=File(inputs!!).listFiles()!!.filter {
            it.extension.lowercase() in setOf("png","jpg","jpeg")
        }.sortedBy {it.name}
        assertTrue(sources.size>=4)
        val destination=File(outputs!!)
        require(destination.mkdirs()||destination.isDirectory)
        val context=ApplicationProvider.getApplicationContext<Context>()
        var chosen=0
        val notes=mutableListOf("photo,damage,engine,original_error,edited_error,mean_change")
        for(file in sources) {
            val original=BitmapFactory.decodeFile(file.absolutePath)!!
            try {
                for(v in 0..2) {
                    val flawed=damaged(original,v)
                    var revised:Bitmap?=null
                    try {
                        val output=OfflineEnhancer.process(context,flawed,
                            ImageAnalyzer.analyze(flawed))
                        val edited=output.bitmap
                        revised=edited
                        if(output.outcome!!.engine=="V14_CLASSICAL_AUTOEDIT_NO_AI")
                            chosen++
                        assertTrue(output.outcome!!.damage.safe)
                        val label=when(v){0->"dark";1->"flat";else->"cast"}
                        val name=file.nameWithoutExtension+"-"+label
                        File(destination,name+"-original.png").outputStream().use {
                            assertTrue(flawed.compress(Bitmap.CompressFormat.PNG,100,it))
                        }
                        File(destination,name+"-edited.png").outputStream().use {
                            assertTrue(edited.compress(Bitmap.CompressFormat.PNG,100,it))
                        }
                        val a=IntArray(original.width);val b=IntArray(original.width)
                        val c=IntArray(original.width)
                        var srcErr=0.0;var outErr=0.0;var changed=0.0
                        for(y in 0 until original.height) {
                            original.getPixels(a,0,a.size,0,y,a.size,1)
                            flawed.getPixels(b,0,b.size,0,y,b.size,1)
                            edited.getPixels(c,0,c.size,0,y,c.size,1)
                            for(x in a.indices) {
                                srcErr+=abs(luma(a[x])-luma(b[x]))
                                outErr+=abs(luma(a[x])-luma(c[x]))
                                changed+=abs(luma(b[x])-luma(c[x]))
                            }
                        }
                        val n=(original.width*original.height).toDouble()
                        fun f(x:Double)=String.format(Locale.US,"%.5f",x/n)
                        notes+=listOf(name,label,output.outcome!!.engine,
                            f(srcErr),f(outErr),f(changed))
                            .joinToString(",")
                    } finally {
                        if(revised!=null && revised!==flawed && !revised.isRecycled)
                            revised.recycle()
                        flawed.recycle()
                    }
                }
            } finally {original.recycle()}
        }
        File(destination,"stress-measurements.csv")
            .writeText(notes.joinToString("\n")+"\n")
        notes.forEach {println("PHOTO_RECOVERY_STRESS "+it)}
        assertTrue("V14 reverted too many realistic bad-phone-photo inputs: "+
            chosen+"/"+notes.size,chosen>=sources.size*2)
    }
}
