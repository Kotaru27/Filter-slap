package com.example.engine.v9

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.BuildConfig
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

/**
 * Real photographic output for HUMAN side-by-side review, not quality scoring.
 * Automatically skipped without PHOTO_COMPARE_INPUT and PHOTO_COMPARE_OUTPUT.
 * Assets are separate CC0/public-domain examples; no picture is embedded in
 * the renderer or used to tune a color/scene/subject-specific production rule.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealPhotoComparisonTest {
    @Test fun `paired input and actual offline output prepared for independent review`() {
        val inputPath=System.getenv("PHOTO_COMPARE_INPUT")
        val outputPath=System.getenv("PHOTO_COMPARE_OUTPUT")
        assumeTrue(inputPath!=null && outputPath!=null)
        require(File(inputPath!!).isDirectory) {
            "CI photographic comparison input path missing: $inputPath"
        }
        val root=File(outputPath!!)
        require(root.mkdirs() || root.isDirectory)
        val context=ApplicationProvider.getApplicationContext<Context>()
        var photographicSelections=0
        val records=mutableListOf("image,engine,status,recipe,mask,global_model_strength,mean_abs_rgb_change,"+
            "luminance_source,luminance_edited,mean_rgb_channel_spread_source,"+
            "mean_rgb_channel_spread_edited,newly_clipped,newly_crushed")
        val originals=File(inputPath!!).listFiles()!!.filter { it.extension.lowercase() in
            setOf("jpg","jpeg","png") }.sortedBy { it.name }
        assertTrue("No public photographs available for evaluation",originals.size>=4)
        for(file in originals) {
            val original=BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalArgumentException("Failed to decode public reference")
            var edited:Bitmap?=null
            try {
                val analysis=ImageAnalyzer.analyze(original)
                val result=OfflineEnhancer.process(context,original,analysis)
                edited=result.bitmap
                val outcome=requireNotNull(result.outcome)
                // Prevent a ZIP/APK labelled V9.3 from reporting an older runtime
                // engine. This uses actual rendered output and compiled BuildConfig.
                if(outcome.engine=="V14_CLASSICAL_AUTOEDIT_NO_AI")photographicSelections++
                assertEquals(outcome.engine,result.appliedPlan.profileName)
                assertEquals("1.6.0-v14-auto-photo-recovery-no-ai",BuildConfig.VERSION_NAME)
                assertEquals(19,BuildConfig.VERSION_CODE)
                assertEquals("No pretrained model in classical mode",0f,result.recipe!!.modelStrength,0f)
                assertNull("No model-based foreground segmentation in classical mode",result.recipe!!.mask)
                assertTrue("Safety failed for ${file.name}",outcome.damage.safe)
                assertEquals(original.width,edited.width)
                assertEquals(original.height,edited.height)
                val name=file.nameWithoutExtension
                File(root,"${name}-original.png").outputStream().use {
                    assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                File(root,"${name}-edited.png").outputStream().use {
                    assertTrue(edited.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                var change=0.0;var lo=0.0;var ln=0.0;var so=0.0;var sn=0.0
                val w=original.width;val h=original.height
                val oldPixels=IntArray(w)
                val newPixels=IntArray(w)
                for(y in 0 until h) {
                    original.getPixels(oldPixels,0,w,0,y,w,1)
                    edited.getPixels(newPixels,0,w,0,y,w,1)
                    for(x in 0 until w) {
                        val p=oldPixels[x];val q=newPixels[x]
                        val a=p ushr 16 and 255;val b=p ushr 8 and 255;val c=p and 255
                        val aa=q ushr 16 and 255;val bb=q ushr 8 and 255;val cc=q and 255
                        change+=(abs(a-aa)+abs(b-bb)+abs(c-cc))/3.0
                        lo+=(.2126*a+.7152*b+.0722*c)/255.0
                        ln+=(.2126*aa+.7152*bb+.0722*cc)/255.0
                        so+=(maxOf(a,b,c)-minOf(a,b,c))
                        sn+=(maxOf(aa,bb,cc)-minOf(aa,bb,cc))
                    }
                }
                val count=(w*h).toDouble()
                fun num(value:Double)=String.format(Locale.US,"%.5f",value)
                records+=listOf(name,outcome.engine,outcome.status,requireNotNull(result.recipe).strategy,
                    outcome.maskUsed.toString(),num(outcome.modelStrength.toDouble()),
                    num(change/count),num(lo/count),num(ln/count),num(so/count),
                    num(sn/count),num(outcome.damage.newlyClipped.toDouble()),
                    num(outcome.damage.newlyCrushed.toDouble())).joinToString(",")
            } finally {
                if(edited!=null && edited!==original && !edited.isRecycled)edited.recycle()
                if(!original.isRecycled)original.recycle()
            }
        }
        File(root,"measurements.csv").writeText(records.joinToString("\n")+"\n")
        records.forEach { println("PHOTO_PAIR_REPORT $it") }
        assertTrue("V14 fell back on too many untouched public photos: "+
            photographicSelections+"/"+originals.size,photographicSelections>=4)
    }
}
