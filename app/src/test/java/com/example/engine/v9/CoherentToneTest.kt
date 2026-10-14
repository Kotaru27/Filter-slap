package com.example.engine.v9

import com.example.engine.v7.EditRecipe
import com.example.engine.v7.EnhancementMath
import com.example.engine.v8.CandidatePlanner
import com.example.engine.v8.CorrectionEvidence
import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Basic source-invariant tests, not evidence of aesthetic quality. */
class CoherentToneTest {
    private val noColor=floatArrayOf(0f,0f,0f)
    private fun recipe(shadows:Float=0f,highlights:Float=0f):EditRecipe =
        EditRecipe(null,null,0f,0f,0f,0f,1f,"tone","none",emptyList(),
            coherentShadows=shadows,coherentHighlights=highlights)

    @Test fun `zero strength does not modify even a colorful original`() {
        val pixels=intArrayOf(0xff7e3d24.toInt(),0xffa0bcf1.toInt(),
            0xff424242.toInt(),0xff05a0c0.toInt())
        for(p in pixels) assertEquals(p,EnhancementMath.edit(p,0f,recipe(),
            EnhancementMath.luma(p),FloatArray(3)))
    }

    @Test fun `continuous tone curve preserves absolute black and white`() {
        for(p in intArrayOf(0xff000000.toInt(),0xffffffff.toInt()))
            assertEquals(p,EnhancementMath.edit(p,0f,recipe(.30f,.25f),
                EnhancementMath.luma(p),FloatArray(3)))
        for(y in 0..1000){
            val value=y/1000f
            val d=CoherentTone.difference(value,.3f,.25f)
            assertTrue(d.isFinite() && abs(d)<.16f)
        }
    }

    @Test fun `shadow operation never darkens and highlight operation never brightens`() {
        for(i in 0..255){
            val y=i/255f
            assertTrue(CoherentTone.difference(y,.30f,0f)>=-1e-7f)
            assertTrue(CoherentTone.difference(y,0f,.25f)<=1e-7f)
        }
        assertTrue(CoherentTone.difference(.22f,.30f,0f)>0f)
        assertTrue(CoherentTone.difference(.80f,0f,.25f)<0f)
    }

    @Test fun `new tone curve cannot repaint saturated source colors`() {
        for(p in intArrayOf(0xffb56b20.toInt(),0xff276caa.toInt(),0xff41926a.toInt())) {
            val before=EnhancementMath.luma(p)
            val out=EnhancementMath.edit(p,0f,recipe(.30f,.25f),before,FloatArray(3))
            val r=(p ushr 16 and 255).toFloat()
            val g=(p ushr 8 and 255).toFloat()
            val b=(p and 255).toFloat()
            val rr=(out ushr 16 and 255).toFloat()
            val gg=(out ushr 8 and 255).toFloat()
            val bb=(out and 255).toFloat()
            assertTrue(abs(rr/r-gg/g)<.04f)
            assertTrue(abs(rr/r-bb/b)<.04f)
        }
    }

    @Test fun `all intentional darkness candidates preserve deliberately dark scene`() {
        val frame=FrameTones(RegionTones(.04f,.20f,.54f))
        val e=CorrectionEvidence(frame,true,0f,0f,spatialPotential=.45f)
        val candidates=CandidatePlanner.propose(e,true)
        assertTrue(candidates.none{it.coherentShadows>0f || it.spatialStrength>0f})
    }

    @Test fun `an extreme photographic contrast source receives coherent proposal`() {
        val frame=FrameTones(RegionTones(.08f,.48f,.95f))
        val e=CorrectionEvidence(frame,false,0f,0f)
        val candidates=CandidatePlanner.propose(e,false)
        val tone=candidates.firstOrNull{it.name=="Coherent tonal balance"}
        assertNotNull(tone)
        assertTrue(tone!!.coherentShadows>0f)
        assertTrue(tone.coherentHighlights>0f)
    }
}
