package com.example.engine.v8

import org.junit.Assert.*
import org.junit.Test

class CandidatePlannerTest {
    private fun r(p10:Float,m:Float,p90:Float,coverage:Float=1f) = RegionTones(p10,m,p90,coverage)

    @Test fun `balanced image does not get arbitrary tone corrections`() {
        val e=CorrectionEvidence(FrameTones(r(.12f,.50f,.88f)),false,0f,0f)
        val proposals=CandidatePlanner.propose(e,true)
        assertTrue(proposals.any { it.name=="Learned grade" })
        assertTrue(proposals.none { it.exposureEv!=0f || it.subjectEv!=0f || it.backgroundEv!=0f || it.contrast!=0f })
        val original=CandidatePlanner.assess(e,proposals[0],e.before,0f,0f,0f,0f,0f)
        val learned=CandidatePlanner.assess(e,proposals[1],e.before,8f,0f,0f,0f,0f)
        assertEquals("Original",CandidatePlanner.select(listOf(original,learned)).candidate.name)
    }

    @Test fun `dark foreground may be corrected independently of a bright background`() {
        val e=CorrectionEvidence(FrameTones(r(.14f,.51f,.90f),r(.07f,.22f,.67f,.27f),r(.48f,.75f,.98f,.62f)),false,0f,0f)
        val plans=CandidatePlanner.propose(e,true)
        val local=plans.single { it.name=="Independent regions" }
        assertTrue(local.subjectEv>0f)
        assertTrue(local.backgroundEv<0f)
        val output=FrameTones(r(.16f,.52f,.88f),r(.09f,.38f,.77f,.27f),r(.41f,.67f,.94f,.62f))
        val a=CandidatePlanner.assess(e,local,output,9f,0f,0f,0f,0f)
        assertTrue(a.reason,a.score>0f)
    }

    @Test fun `more changed pixels is not rewarded`() {
        val e=CorrectionEvidence(FrameTones(r(.10f,.24f,.70f)),false,0f,0f)
        val p=CandidateSettings("tone",exposureEv=.5f)
        val after=FrameTones(r(.15f,.41f,.78f))
        val gentle=CandidatePlanner.assess(e,p,after,6f,0f,0f,0f,0f)
        val aggressive=CandidatePlanner.assess(e,p,after,29f,0f,0f,0f,0f)
        assertTrue(gentle.score>aggressive.score)
    }

    @Test fun `potential clipping rejects regardless of apparent benefit`() {
        val e=CorrectionEvidence(FrameTones(r(.10f,.25f,.69f)),false,0f,0f)
        val p=CandidateSettings("tone",exposureEv=.6f)
        val a=CandidatePlanner.assess(e,p,FrameTones(r(.15f,.46f,.80f)),10f,.008f,0f,0f,0f)
        assertFalse(a.safe)
    }

    @Test fun `intentional dark scene is not automatically normalized`() {
        val e=CorrectionEvidence(FrameTones(r(.02f,.20f,.54f)),true,0f,0f)
        assertTrue(CandidatePlanner.propose(e,false).none { it.exposureEv>0f || it.subjectEv>0f })
    }

    @Test fun `neutral balance needs adequate anchors and uniform lighting`() {
        val colors=floatArrayOf(130f,105f,82f)
        val b=FrameTones(r(.10f,.43f,.85f))
        val ambiguous=CandidatePlanner.propose(CorrectionEvidence(b,false,.001f,0f,colors),false)
        assertEquals(1,ambiguous.size)
        val mixed=CandidatePlanner.propose(CorrectionEvidence(b,false,.025f,.3f,colors),false)
        assertEquals(1,mixed.size)
        val confident=CandidatePlanner.propose(CorrectionEvidence(b,false,.025f,.01f,colors),false)
        assertTrue(confident.any { it.redGain != 1f })
    }

    @Test fun `white balance earns points only for measured anchor improvement`() {
        val e=CorrectionEvidence(FrameTones(r(.1f,.48f,.87f)),false,.025f,.012f,
            floatArrayOf(132f,109f,85f))
        val plan=CandidatePlanner.propose(e,false).last()
        val helpful=CandidatePlanner.assess(e,plan,e.before,5f,0f,0f,0f,0f,.20f,.06f)
        val harmful=CandidatePlanner.assess(e,plan,e.before,5f,0f,0f,0f,0f,.20f,.24f)
        assertTrue(helpful.score > 0f)
        assertTrue(harmful.score < 0f)
    }

    @Test fun `without model genuine exposure correction is still possible`() {
        val e=CorrectionEvidence(FrameTones(r(.04f,.16f,.60f)),false,0f,0f)
        val plans=CandidatePlanner.propose(e,false)
        assertTrue(plans.any { it.exposureEv > 0f && it.lutStrength == 0f })
    }
}