package com.example.engine.v9

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import com.example.engine.v6.V6AdaptiveEditor
import com.example.engine.v7.EditRecipe
import com.example.engine.v7.OfflineEnhancer
import com.example.engine.v7.UnsafeRenderException
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

/**
 * Research-only: inspect the real offline pretrained LUT with two strengths,
 * separately from V9 candidate ranking. No production code is changed.
 * These outputs MUST NOT be silently substituted for the selected output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnedGradeAblationTest {
    @Test fun `pretrained grade alternatives produce inspectable safe outputs only`() {
        val src=System.getenv("PHOTO_COMPARE_INPUT")
        val dst=System.getenv("PHOTO_COMPARE_OUTPUT")
        assumeTrue(src!=null && dst!=null)
        require(File(src!!).isDirectory) { "Missing public photo input for grade evaluation" }
        val context=ApplicationProvider.getApplicationContext<Context>()
        val directory=File(dst!!,"learned-grade-study")
        require(directory.mkdirs() || directory.isDirectory)
        val rows=mutableListOf("photo,strength,status,mean_rgb_change,newly_clipped,newly_crushed")
        val photos=File(src).listFiles()!!.filter { it.extension.lowercase() in setOf("png","jpg","jpeg") }
        assertTrue(photos.size>=4)
        for(file in photos) {
            val source=BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Cannot decode public photograph")
            try {
                val analysis=ImageAnalyzer.analyze(source)
                val lut=V6AdaptiveEditor.fusedLut(context,source)
                val base=File(directory,file.nameWithoutExtension)
                base.mkdirs()
                File(base,"original.png").outputStream().use {
                    assertTrue(source.compress(Bitmap.CompressFormat.PNG,100,it))
                }
                for(strength in listOf(.35f,.70f)) {
                    val code=if(strength<.5f)"soft" else "strong"
                    val recipe=EditRecipe(lut,null,strength,0f,0f,0f,
                        source.width.toFloat()/source.height,
                        "ablation-${file.nameWithoutExtension}-$code","pretrained",
                        emptyList(),strategy="Learned grade ablation")
                    val result=try {
                        OfflineEnhancer.render(source,recipe,analysis)
                    } catch(unsafe:UnsafeRenderException) {
                        rows+="${file.nameWithoutExtension},$code,UNSAFE,NA,NA,NA"
                        continue
                    }
                    try {
                        assertTrue(result.outcome!!.damage.safe)
                        File(base,"learned-$code.png").outputStream().use {
                            assertTrue(result.bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                        }
                        val harm=result.outcome!!.damage
                        fun v(x:Float)=String.format(Locale.US,"%.5f",x)
                        rows+=listOf(file.nameWithoutExtension,code,"safe",
                            v(harm.meanChange),v(harm.newlyClipped),
                            v(harm.newlyCrushed)).joinToString(",")
                    } finally {
                        result.bitmap.recycle()
                    }
                }
            } finally { source.recycle() }
        }
        File(directory,"learned-measurements.csv").writeText(rows.joinToString("\n")+"\n")
        assertTrue("The study did not evaluate any grade",rows.size>1)
    }
}
