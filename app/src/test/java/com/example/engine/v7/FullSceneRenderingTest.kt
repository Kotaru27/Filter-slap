package com.example.engine.v7

import com.example.engine.v8.CandidatePlanner
import com.example.engine.v8.CorrectionEvidence
import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import org.junit.Assert.*
import org.junit.Test

/** Mathematical invariants with varied synthetic inputs; no photo-specific fixtures. */
class FullSceneRenderingTest {
    private fun gray(v: Int) = -0x1000000 or (v shl 16) or (v shl 8) or v
    private fun luminance(p: Int) = EnhancementMath.luma(p)
    private fun edit(p: Int, recipe: EditRecipe, foreground: Float): Int =
        EnhancementMath.edit(p,foreground,recipe,luminance(p),FloatArray(3))

    private fun syntheticGlobalLut(scale: Float): FloatArray {
        val dim=33
        val volume=dim*dim*dim
        val lut=FloatArray(volume*3)
        for(b in 0 until dim) for(g in 0 until dim) for(r in 0 until dim) {
            val p=(b*dim+g)*dim+r
            lut[p]=scale*r/(dim-1f)
            lut[volume+p]=scale*g/(dim-1f)
            lut[volume*2+p]=scale*b/(dim-1f)
        }
        return lut
    }

    private fun validMask(): MaskField {
        val probabilities=FloatArray(100) { i -> if(i<45) 1f else 0f }
        return MaskField.fromProbabilities(probabilities,10,10)!!
    }

    @Test fun `report distinguishes whole-scene grade from selective corrections`() {
        val report=DamageReport(5f,0f,0f,0f,0f,100)
        val globallyGraded=EnhancementOutcome("Enhanced","V8_AUTO_EDITOR","r1","model",.65f,true,report,emptyList())
        assertTrue(globallyGraded.summary().contains("whole-scene grade"))
        val regionOnly=EnhancementOutcome("Enhanced","V8_AUTO_EDITOR","r2","none",0f,true,report,emptyList())
        assertTrue(regionOnly.summary().contains("regional corrections"))
        val noEdit=EnhancementOutcome("Original kept","V8_AUTO_EDITOR","r3","none",0f,true,report,emptyList())
        assertTrue(noEdit.summary().contains("original preserved"))
    }

    @Test fun `global learned LUT does not depend on foreground membership`() {
        val recipe=EditRecipe(syntheticGlobalLut(.75f),validMask(),.68f,0f,0f,0f,
            1f,"whole-scene","unit",emptyList())
        for (pixel in intArrayOf(0xff649ab6.toInt(),0xffb68853.toInt(),gray(119))) {
            val background=edit(pixel,recipe,0f)
            val foreground=edit(pixel,recipe,1f)
            val boundary=edit(pixel,recipe,.45f)
            assertEquals("Global grade must reach background",foreground,background)
            assertEquals("Global grade must not fluctuate at mask edges",foreground,boundary)
            assertNotEquals("Learned global grade should transform a non-extreme pixel",pixel,background)
        }
    }

    @Test fun `regional background contrast is independent of foreground contrast`() {
        val recipe=EditRecipe(null,validMask(),0f,0f,0f,0f,1f,"regional","unit",
            emptyList(),backgroundContrast=.21f)
        val dark=gray(80);val bright=gray(175)
        val backgroundDark=edit(dark,recipe,0f)
        val backgroundBright=edit(bright,recipe,0f)
        assertEquals("Foreground should have no unrelated contrast adjustment",dark,edit(dark,recipe,1f))
        assertEquals("Foreground should have no unrelated contrast adjustment",bright,edit(bright,recipe,1f))
        assertTrue(luminance(backgroundDark)<luminance(dark))
        assertTrue(luminance(backgroundBright)>luminance(bright))
    }

    @Test fun `no foreground mask still gets global exposure and learned color`() {
        val recipe=EditRecipe(syntheticGlobalLut(.85f),null,.55f,0f,0f,0f,
            1f,"no-mask","unit",emptyList(),exposureEv=.32f)
        val source=gray(105)
        assertEquals(edit(source,recipe,0f),edit(source,recipe,1f))
        assertNotEquals(source,edit(source,recipe,0f))
    }

    @Test fun `weakening unsafe recipe also weakens background correction`() {
        val recipe=EditRecipe(null,validMask(),0f,0f,0f,0f,1f,"safety","unit",
            emptyList(),backgroundContrast=.18f)
        val reduced=recipe.attenuated(.5f,"safety")
        assertEquals(.09f,reduced.backgroundContrast,.00001f)
        assertEquals(0f,recipe.attenuated(0f,"safety").backgroundContrast,0f)
        assertTrue(recipe.attenuated(0f,"safety").isIdentity)
    }

    @Test fun `background correction requires measured deficiency`() {
        val weakBackground=CorrectionEvidence(
            FrameTones(RegionTones(.10f,.52f,.88f),
                RegionTones(.15f,.51f,.84f,.30f),
                RegionTones(.30f,.50f,.54f,.62f)),false,0f,0f)
        val proposals=CandidatePlanner.propose(weakBackground,true)
        val regional=proposals.firstOrNull { it.name=="Independent regions" }
        assertNotNull("Weak regional contrast should enable independent option",regional)
        assertTrue(regional!!.backgroundContrast>0f)
        val after=weakBackground.before.copy(background=RegionTones(.24f,.50f,.61f,.62f))
        val chosen=CandidatePlanner.assess(weakBackground,regional,after,5f,0f,0f,0f,0f)
        assertTrue("Measurably improved background contrast should earn credit",chosen.score>0f)

        val balanced=CorrectionEvidence(FrameTones(
            RegionTones(.10f,.52f,.88f),
            RegionTones(.15f,.51f,.84f,.30f),
            RegionTones(.12f,.54f,.85f,.62f)),false,0f,0f)
        assertTrue(CandidatePlanner.propose(balanced,true).all { it.backgroundContrast==0f })
    }

    @Test fun `background decisions vanish if there is no verified background region`() {
        val noMask=CorrectionEvidence(FrameTones(RegionTones(.05f,.22f,.75f)),false,0f,0f)
        assertTrue(CandidatePlanner.propose(noMask,true).all { it.backgroundContrast==0f })
    }
}
