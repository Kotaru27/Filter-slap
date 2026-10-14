package com.example.engine.v9

import com.example.engine.v8.CandidatePlanner
import com.example.engine.v8.CandidateSettings
import com.example.engine.v8.CorrectionEvidence
import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import org.junit.Assert.*
import org.junit.Test

/**
 * Generic mathematical checks. The varied generated RGB samples are not
 * photographic quality targets and no production decisions use these values.
 */
class PhotographicGuardTest {
    private fun rgb(r:Int,g:Int,b:Int):Int =
        -0x1000000 or (r shl 16) or (g shl 8) or b

    private fun colorfulSource(n:Int=2048):IntArray=IntArray(n){ i ->
        val t=i%13
        rgb(128+t*3,76+t*2,31+t)
    }

    @Test fun `unchanged colorful image has zero chroma damage`() {
        val source=colorfulSource()
        val r=ChromaticIntegrity.assess(source,source,1)
        assertTrue(r.eligiblePixels>200)
        assertEquals(0f,r.severeLossFraction,0f)
        assertEquals(0f,r.meanRelativeLoss,0f)
        assertFalse(r.widespreadDamage)
    }

    @Test fun `large chroma removal is recognized even without luminance clipping`() {
        val source=colorfulSource()
        val gray=IntArray(source.size){ i ->
            val p=source[i]
            val y=((p ushr 16 and 255)*.2126+(p ushr 8 and 255)*.7152+
                (p and 255)*.0722).toInt()
            rgb(y,y,y)
        }
        val r=ChromaticIntegrity.assess(source,gray,1)
        assertTrue(r.eligiblePixels>200)
        assertTrue(r.severeLossFraction>.95f)
        assertTrue(r.meanRelativeLoss>.90f)
        assertTrue(r.widespreadDamage)
    }

    @Test fun `small tonal change with visible original colors is accepted`() {
        val source=colorfulSource()
        val revised=IntArray(source.size){ i ->
            val p=source[i]
            rgb((p ushr 16 and 255)+8,(p ushr 8 and 255)+7,(p and 255)+4)
        }
        val r=ChromaticIntegrity.assess(source,revised,1)
        assertTrue(r.eligiblePixels>200)
        assertFalse(r.widespreadDamage)
    }

    @Test fun `localized color loss alone does not reject the whole frame`() {
        val source=colorfulSource()
        val revised=source.copyOf()
        for(i in 0 until source.size/8) {
            val p=source[i]
            val v=((p ushr 16 and 255)+(p ushr 8 and 255)+(p and 255))/3
            revised[i]=rgb(v,v,v)
        }
        val r=ChromaticIntegrity.assess(source,revised,1)
        assertTrue(r.severeLossFraction>.05f)
        assertFalse(r.widespreadDamage)
    }

    @Test fun `neutral source cannot trigger arbitrary chroma loss rejections`() {
        val neutral=IntArray(1024){i->val v=60+(i%150);rgb(v,v,v)}
        val shadowLift=IntArray(neutral.size){i->val p=neutral[i] and 255
            val v=(p+18).coerceAtMost(255);rgb(v,v,v)}
        val r=ChromaticIntegrity.assess(neutral,shadowLift,1)
        assertEquals(0,r.eligiblePixels)
        assertFalse(r.widespreadDamage)
    }

    @Test fun `candidate is rejected when measured output loses source color widely`() {
        val original=FrameTones(RegionTones(.07f,.27f,.80f))
        val evidence=CorrectionEvidence(original,false,0f,0f)
        val plan=CandidateSettings("candidate",exposureEv=.25f)
        val unsafe=ChromaticIntegrityReport(1000,650,.85f,.75f)
        val result=CandidatePlanner.assess(evidence,plan,original,7f,0f,0f,0f,0f,
            chromatic=unsafe)
        assertFalse(result.safe)
        assertEquals(Float.NEGATIVE_INFINITY,result.score,0f)
    }

    @Test fun `dark intent stops local recovery from proposal and scoring`() {
        val tones=FrameTones(RegionTones(.03f,.19f,.54f))
        val evidence=CorrectionEvidence(tones,true,0f,0f,spatialPotential=.42f)
        val proposals=CandidatePlanner.propose(evidence,true)
        assertTrue(proposals.none{it.spatialStrength>0f})
        val setting=CandidateSettings("local",spatialStrength=.8f)
        val quality=SpatialQuality(.08f,0f,0f,0f,18)
        val assessment=CandidatePlanner.assess(evidence,setting,tones,5f,0f,0f,0f,0f,
            spatial=quality)
        assertTrue("Shadow brightening must not be rewarded for dark-intent scenes",assessment.score<0f)
    }
}
