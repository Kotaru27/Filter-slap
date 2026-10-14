package com.example.engine.v7

import org.junit.Assert.*
import org.junit.Test

/** General mathematical counterexamples, not fixtures based on any user photo. */
class V8RendererTest {
    private fun gray(v:Int):Int = -0x1000000 or (v shl 16) or (v shl 8) or v
    private fun edit(color:Int,recipe:EditRecipe,fg:Float=.5f):Int =
        EnhancementMath.edit(color,fg,recipe,EnhancementMath.luma(color),FloatArray(3))
    private fun brightness(color:Int):Int = ((color ushr 16 and 255)+(color ushr 8 and 255)+(color and 255))/3

    @Test fun `positive exposure lifts intact middle gray`() {
        val recipe=EditRecipe(null,null,0f,0f,0f,0f,1f,"exp","none",emptyList(),exposureEv=.7f)
        assertTrue(brightness(edit(gray(100),recipe))>100)
        assertEquals(gray(0),edit(gray(0),recipe))
        assertEquals(gray(255),edit(gray(255),recipe))
    }

    @Test fun `subject and background can receive opposite exposure decisions`() {
        val probabilities=FloatArray(100) { i->if(i<35)1f else 0f }
        val mask=MaskField.fromProbabilities(probabilities,10,10)!!
        val recipe=EditRecipe(null,mask,0f,0f,0f,0f,1f,"region","none",emptyList(),
            subjectEv=.75f,backgroundEv=-.45f)
        val fg=brightness(edit(gray(110),recipe,1f))
        val bg=brightness(edit(gray(110),recipe,0f))
        assertTrue("Foreground was not lifted: $fg",fg>110)
        assertTrue("Background was not independently reduced: $bg",bg<110)
    }

    @Test fun `bounded contrast expands a midtone pair`() {
        val r=EditRecipe(null,null,0f,0f,0f,0f,1f,"contrast","none",emptyList(),contrast=.20f)
        val lo=brightness(edit(gray(76),r))
        val hi=brightness(edit(gray(181),r))
        assertTrue("Original tonal ordering inverted",lo<hi)
        assertTrue("Contrast must expand the interval",hi-lo>181-76)
    }

    @Test fun `neutral anchor gains can reduce channel imbalance`() {
        val r=EditRecipe(null,null,0f,0f,0f,0f,1f,"wb","none",emptyList(),
            redGain=.9f,greenGain=1f,blueGain=1.1f)
        val source=-0x1000000 or (150 shl 16) or (130 shl 8) or 110
        val target=edit(source,r)
        val rr=target ushr 16 and 255;val gg=target ushr 8 and 255;val bb=target and 255
        assertTrue(maxOf(rr,gg,bb)-minOf(rr,gg,bb)<40)
    }

    @Test fun `neutral recipe leaves arbitrary colors identical`() {
        val r=EditRecipe(null,null,0f,0f,0f,0f,1f,"original","none",emptyList())
        for(c in intArrayOf(0xff00aaff.toInt(),0xffdb9b51.toInt(),0xff203548.toInt(),0xffeff3f4.toInt()))
            assertEquals(c,edit(c,r))
    }
}