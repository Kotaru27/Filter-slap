package com.example.engine.learned

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import com.example.engine.v7.OfflineEnhancer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/** Private real-photo audit. Never packaged into the application. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RegressionEvidenceTest {
    @Test fun pinnedEngineAndNativeLearnedOutputs() {
        val sources=File(requireNotNull(System.getenv("AUDIT_SOURCE")))
        val outputs=File(requireNotNull(System.getenv("AUDIT_OUTPUT")))
        val engine=requireNotNull(System.getenv("AUDIT_ENGINE"))
        val expected=requireNotNull(System.getenv("AUDIT_COUNT")).toInt()
        val model=System.getenv("AUDIT_MODEL")?.let{File(it).readBytes()}
        val mode=System.getenv("AUDIT_MODE") ?: "global"
        val files=sources.listFiles()!!.filter{it.name.endsWith("_source.png")}.sortedBy{it.name}
        assertEquals(expected,files.size)
        outputs.mkdirs()
        val baseline=File(outputs,"baseline").apply{mkdirs()}
        val learned=File(outputs,"learned").apply{mkdirs()}
        val decisions=mutableListOf("id,decision,strength,mean_change,new_clipping,new_crushing,hue_shift,vivid_boost")
        val provenance=mutableListOf("id,engine,width,height,source_pixel_sha256,baseline_pixel_sha256")
        fun hash(b:ByteArray)=MessageDigest.getInstance("SHA-256").digest(b).joinToString(""){"%02x".format(it)}
        fun pixelHash(b:Bitmap):String {
            val digest=MessageDigest.getInstance("SHA-256")
            val row=IntArray(b.width)
            for(y in 0 until b.height) {
                b.getPixels(row,0,b.width,0,y,b.width,1)
                for(p in row) {digest.update((p ushr 16).toByte());digest.update((p ushr 8).toByte());digest.update(p.toByte())}
            }
            return digest.digest().joinToString(""){"%02x".format(it)}
        }
        fun save(b:Bitmap,f:File) {f.outputStream().use{assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,it))}}
        val context=ApplicationProvider.getApplicationContext<Context>()
        for(file in files) {
            val src=BitmapFactory.decodeFile(file.absolutePath) ?: error("Source decode failed")
            val id="fivek_"+file.name.removeSuffix("_source.png")
            try {
                val before=pixelHash(src)
                val result=OfflineEnhancer.process(context,src,ImageAnalyzer.analyze(src))
                try {
                    val outcome=requireNotNull(result.outcome)
                    assertEquals("Wrong baseline engine",engine,outcome.engine)
                    assertTrue(outcome.damage.safe)
                    assertEquals(src.width,result.bitmap.width); assertEquals(src.height,result.bitmap.height)
                    save(result.bitmap,File(baseline,"$id.png"))
                    provenance+="$id,$engine,${src.width},${src.height},$before,${pixelHash(result.bitmap)}"
                }finally{if(result.bitmap!==src)result.bitmap.recycle()}
                if(model!=null) {
                    val selected=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(src,model,hash(model),mode)
                    if(selected==null) decisions+="$id,rejected,0,0,0,0,0,0"
                    else try {
                        val d=selected.preview.damage
                        assertTrue(d.safe)
                        save(selected.preview.bitmap,File(learned,"$id.png"))
                        decisions+="$id,accepted,${selected.strength},${d.meanChange},${d.newlyClipped},${d.newlyCrushed},${d.hueShifted},${d.vividBoosted}"
                    }finally{selected.preview.bitmap.recycle()}
                }
                assertEquals("Source mutated",before,pixelHash(src))
            }finally{src.recycle()}
        }
        File(outputs,"engine_provenance.csv").writeText(provenance.joinToString("\n")+"\n")
        if(model!=null)File(learned,"decisions.csv").writeText(decisions.joinToString("\n")+"\n")
    }
}
