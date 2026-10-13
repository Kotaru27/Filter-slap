package com.example.engine.v7

import org.junit.Assert.*
import org.junit.Test

class EnhancementMathTest {
    @Test fun `recovering another region cannot hide newly clipped pixels`() {
        val audit=DamageAccumulator()
        repeat(50) { audit.add(0xffeeeeee.toInt(),0xffffffff.toInt()) }
        repeat(50) { audit.add(0xffffffff.toInt(),0xffeeeeee.toInt()) }
        assertEquals(.5f,audit.report().newlyClipped,0f)
        assertFalse(audit.report().safe)
    }
    @Test fun `gamut mapping preserves delta direction and highlight headroom`() {
        val out=floatArrayOf(1.4f,.8f,.4f)
        EnhancementMath.boundedDelta(.8f,.4f,.2f,out)
        val rr=(out[0]-.8f)/.6f;val gg=(out[1]-.4f)/.4f;val bb=(out[2]-.2f)/.2f
        assertEquals(rr,gg,.00001f);assertEquals(rr,bb,.00001f)
        assertTrue(out[0]<=250f/255f+.00001f)
    }
    @Test fun `flat uncertain maps cannot be normalized into confident subjects`() {
        assertNull(MaskField.fromProbabilities(FloatArray(100) { .51f },10,10))
        assertNull(MaskField.fromProbabilities(FloatArray(100) { .99f },10,10))
        assertNull(MaskField.fromProbabilities(FloatArray(100) { Float.NaN },10,10))
    }
    @Test fun `same source color receives different subject lighting`() {
        val recipe=EditRecipe(null,null,0f,0f,.28f,0f,1f,"test","none",emptyList())
        val pixel=0xff665532.toInt();val y=EnhancementMath.luma(pixel)
        val bg=EnhancementMath.edit(pixel,0f,recipe,y,FloatArray(3))
        val subject=EnhancementMath.edit(pixel,1f,recipe,y,FloatArray(3))
        assertEquals(pixel,bg)
        assertTrue(EnhancementMath.luma(subject)>EnhancementMath.luma(bg))
        val audit=DamageAccumulator();audit.add(pixel,subject);assertTrue(audit.report().safe)
    }
    @Test fun `lifting shadows preserves true black and white`() {
        val recipe=EditRecipe(null,null,0f,.12f,.28f,0f,1f,"test","none",emptyList())
        for(p in intArrayOf(0xff000000.toInt(),0xffffffff.toInt()))
            assertEquals(p,EnhancementMath.edit(p,1f,recipe,EnhancementMath.luma(p),FloatArray(3)))
    }
    @Test fun `learned black offset cannot wash true black into gray`() {
        val recipe=EditRecipe(FloatArray(107811) { .10f },null,.4f,0f,0f,0f,
            1f,"bias-test","none",emptyList())
        assertEquals(0xff000000.toInt(),EnhancementMath.edit(0xff000000.toInt(),1f,recipe,0f,FloatArray(3)))
    }
}
