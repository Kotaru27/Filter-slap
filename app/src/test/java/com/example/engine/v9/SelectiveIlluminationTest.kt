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

/**
 * Scene-independent invariants and counterexamples. These fabricated arrays
 * test boundaries and compatibility, never steer production toward a photo.
 */
class SelectiveIlluminationTest {
    private fun gray(i:Int):Int =
        -0x1000000 or (i shl 16) or (i shl 8) or i
    private fun proposals(p10:Float,p50:Float,p90:Float,potential:Float,dark:Boolean=false) =
        CandidatePlanner.propose(
            CorrectionEvidence(FrameTones(RegionTones(p10,p50,p90)),dark,0f,0f,
                spatialPotential=potential),false)
    @Test fun `strong candidate requires actual contrast and textured source evidence`() {
        val candidates=proposals(.09f,.43f,.94f,.26f)
        assertTrue(candidates.any{it.name=="Selective illumination recovery" && it.spatialStrength>1f})
        assertTrue(candidates.any{it.name=="Local light recovery" && it.spatialStrength<=1f})
        assertTrue(candidates.first().isNeutral)
        assertTrue(proposals(.13f,.43f,.72f,.26f).none{it.spatialStrength>1f})
        // Large relative contrast can require selective recovery even when
        // diffuse highlights never approach white.
        assertTrue(proposals(.06f,.50f,.79f,.20f)
            .any{it.name=="Selective illumination recovery"})
        assertTrue(proposals(.13f,.43f,.94f,.0f).none{it.spatialStrength>1f})
        assertTrue(proposals(.09f,.43f,.94f,.26f,true).none{it.spatialStrength>0f})
    }

    @Test fun `legacy spatial renderer remains pixel-equivalent for previous strengths`() {
        val w=128;val h=80
        val pixels=IntArray(w*h){i->
            val x=i%w
            gray(if(x<w/2) 32+(i*47%51) else 212+(i*11%23))
        }
        val f=SpatialIllumination.build(pixels,w,h,1.4f)
        for(y in 0 until h step 5)for(x in 0 until w step 7){
            val i=y*w+x
            val sy=(pixels[i] and 255)/255f
            val direct=f.liftAt((x+.5f)/w,(y+.5f)/h,sy,.84f)
            // Contract for old values: compare to the reference equation,
            // using strength scaling against a fixed legacy 1x measurement.
            val full=f.liftAt((x+.5f)/w,(y+.5f)/h,sy,1f)
            assertEquals(full*.84f,direct,.000002f)
        }
    }

    @Test fun `enhanced recovery protects highlights and limits shadow gain`() {
        val w=144;val h=92
        val pixels=IntArray(w*h){i->
            val x=i%w
            gray(if(x<w/2) 40+(i*17%62) else 217+(i*11%24))
        }
        val f=SpatialIllumination.build(pixels,w,h,1.2f)
        var oldShadow=0f;var newShadow=0f;var bright=0f;var count=0
        for(y in 10 until h-10 step 3)for(x in 10 until w-10 step 3) {
            val sy=(pixels[y*w+x] and 255)/255f
            val old=f.liftAt((x+.5f)/w,(y+.5f)/h,sy,.86f)
            val enhanced=f.liftAt((x+.5f)/w,(y+.5f)/h,sy,1.30f)
            assertTrue(enhanced>=0f && enhanced<=.255f+1e-6f)
            if(x<w/2) {
                oldShadow+=old;newShadow+=enhanced;count++
            } else bright+=enhanced
        }
        assertTrue("New candidate did not lift recoverable signal",newShadow>oldShadow+count*.008f)
        assertTrue("Bright areas not protected",bright<.005f*count)
        assertEquals(0f,f.liftAt(.5f,.5f,.001f,1.35f),0f)
        assertEquals(0f,f.liftAt(.5f,.5f,.84f,1.35f),0f)
    }
    @Test fun `final RGB renderer honors enhanced bound only for new candidate`() {
        fun recipe(strength:Float)=EditRecipe(null,null,0f,0f,0f,0f,1f,
            "quality-study","none",emptyList(),spatialStrength=strength)
        val source=0xff544d43.toInt()
        val y=EnhancementMath.luma(source)
        fun render(strength:Float,lift:Float)=EnhancementMath.edit(source,0f,
            recipe(strength),y,FloatArray(3),spatialLift=lift)
        val legacy=render(.85f,.243f)
        val clippedLegacy=render(.85f,.19f)
        assertEquals("Old renderer must retain original 0.19 ceiling",clippedLegacy,legacy)
        val experimental=render(1.3f,.243f)
        assertTrue("Extended lift failed to reach the final pixels",
            EnhancementMath.luma(experimental)>EnhancementMath.luma(legacy)+.025f)
        val unchanged=render(1.3f,0f)
        assertEquals("New candidate with no spatial lift changed pixels",source,unchanged)
    }

}
