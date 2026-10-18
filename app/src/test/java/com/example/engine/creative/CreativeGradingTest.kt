package com.example.engine.creative

import com.example.engine.v8.FrameTones
import com.example.engine.v8.RegionTones
import org.junit.Assert.*
import org.junit.Test

/**
 * Photographic quality is NOT established by synthetic tests. These only guard
 * deterministic math, per-scene parameter variation, and failure behavior.
 */
class CreativeGradingTest {
    private fun tones(lo: Float, mid: Float, hi: Float) =
        FrameTones(RegionTones(lo, mid, hi))

    private fun pixels(n: Int, r: Int, g: Int, b: Int) =
        IntArray(n) { (255 shl 24) or (r shl 16) or (g shl 8) or b }

    @Test fun neutralGradeIsBitwiseNoOp() {
        val values=floatArrayOf(.24f,.48f,.80f)
        CreativeGrading.apply(values,CreativeGrade())
        assertArrayEquals(floatArrayOf(.24f,.48f,.80f),values,0f)
    }

    @Test fun sceneEvidenceProducesDistinctPhotographicGrades() {
        val dim=CreativeGrading.propose(
            tones(.17f,.40f,.66f),pixels(800,100,93,80),false)
        val harsh=CreativeGrading.propose(
            tones(.12f,.68f,.96f),pixels(800,49,137,215),false)
        assertEquals(3,dim.size)
        assertEquals(3,harsh.size)
        assertTrue(dim.all { !it.creativeGrade.isNeutral })
        assertNotEquals(dim[1].creativeGrade,harsh[1].creativeGrade)
        assertTrue("bright scene needs stronger highlight shoulder",
            harsh[1].creativeGrade.highlightShoulder >
                dim[1].creativeGrade.highlightShoulder)
        assertTrue("existing vibrant color must be protected",
            harsh[1].creativeGrade.vibrance <= dim[1].creativeGrade.vibrance)
    }

    @Test fun balancedGradeShapesTonesAtMultipleLocations() {
        val grade=CreativeGrade(contrast=.28f,shadowDepth=.03f,
            highlightShoulder=.22f,vibrance=.10f)
        fun gradePixel(y: Float): FloatArray =
            floatArrayOf(y*.90f,y,y*1.06f).also { CreativeGrading.apply(it,grade) }
        val shadow=gradePixel(.20f)
        val middle=gradePixel(.50f)
        val highlight=gradePixel(.87f)
        assertTrue(shadow.all { it.isFinite() })
        assertTrue(middle.all { it.isFinite() })
        assertTrue(highlight.all { it.isFinite() })
        // A positive shoulder does not brighten already bright image areas.
        assertTrue(highlight[1] < .87f)
        assertTrue("the grade must affect shadow and highlight zones",
            kotlin.math.abs(shadow[1]-.20f)>.001f &&
            kotlin.math.abs(highlight[1]-.87f)>.001f)
    }

    @Test fun evidenceBasedBenefitRejectsArbitraryDarkeningOrFlattening() {
        val source=tones(.13f,.46f,.79f)
        val improved=tones(.11f,.45f,.83f)
        val degraded=tones(.19f,.34f,.73f)
        assertTrue(CreativeGrading.benefit(source,improved,false) >
            CreativeGrading.benefit(source,degraded,false))
        assertTrue(CreativeGrading.benefit(source,degraded,false) < 0f)
    }

    @Test fun brightSceneRewardsShoulderButNotLostShadowStructure() {
        val original=tones(.15f,.66f,.96f)
        val controlled=tones(.15f,.65f,.92f)
        val flattened=tones(.32f,.52f,.89f)
        assertTrue(CreativeGrading.benefit(original,controlled,false) >
            CreativeGrading.benefit(original,flattened,false))
    }

    @Test fun scaledGradePreservesZeroAndReducesEveryChannel() {
        val value=CreativeGrade(.22f,.04f,.25f,.09f,-.008f,.014f)
        assertTrue(value.scaled(0f).isNeutral)
        assertEquals(.11f,value.scaled(.5f).contrast,0.00001f)
        assertEquals(.125f,value.scaled(.5f).highlightShoulder,0.00001f)
    }

    @Test fun monochromeRemainsMonochromeAfterAutomaticGrading() {
        val before=tones(.11f,.47f,.82f)
        val source=pixels(600,94,94,94)
        val selected=CreativeGrading.propose(before,source,false)
        selected.forEach {
            assertEquals(0f,it.creativeGrade.vibrance,0f)
            assertEquals(0f,it.creativeGrade.shadowWarmth,0f)
            assertEquals(0f,it.creativeGrade.highlightWarmth,0f)
            val rgb=floatArrayOf(.32f,.32f,.32f)
            CreativeGrading.apply(rgb,it.creativeGrade)
            assertEquals(rgb[0],rgb[1],0.00001f)
            assertEquals(rgb[1],rgb[2],0.00001f)
        }
        val fabricated=pixels(600,108,94,91)
        assertTrue(CreativeGrading.colorBenefit(source,fabricated)<0f)
    }

    @Test fun intentionalLowKeySceneIsNotAutoBrightened() {
        val dark=tones(.03f,.20f,.54f)
        CreativeGrading.propose(dark,pixels(700,49,51,59),true).forEach {
            assertEquals(0f,it.creativeGrade.midtoneLift,0f)
        }
    }

    @Test fun creativeColorScoreDoesNotRewardOversaturation() {
        val vivid=pixels(1000,208,39,27)
        val oversaturated=pixels(1000,255,0,0)
        assertTrue(CreativeGrading.colorBenefit(vivid,oversaturated)<0f)
    }

}
