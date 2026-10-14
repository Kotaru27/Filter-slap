package com.example.engine.v8

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Photographic *correction* planner, not an aesthetic quality predictor.
 * Candidate scores measure improvement of deficiencies present BEFORE editing;
 * large pixel differences never earn a positive score by themselves.
 * No object class, scene name, image fingerprint, or per-image special rule appears here.
 */
data class RegionTones(val p10: Float, val median: Float, val p90: Float, val coverage: Float = 1f) {
    val range get() = p90 - p10
}

data class FrameTones(
    val whole: RegionTones,
    val subject: RegionTones? = null,
    val background: RegionTones? = null
)

data class CandidateSettings(
    val name: String,
    val lutStrength: Float = 0f,
    val exposureEv: Float = 0f,
    val subjectEv: Float = 0f,
    val backgroundEv: Float = 0f,
    val contrast: Float = 0f,
    val subjectContrast: Float = 0f,
    val redGain: Float = 1f,
    val greenGain: Float = 1f,
    val blueGain: Float = 1f
) {
    val isNeutral get() = lutStrength == 0f && exposureEv == 0f && subjectEv == 0f &&
        backgroundEv == 0f && contrast == 0f && subjectContrast == 0f &&
        redGain == 1f && greenGain == 1f && blueGain == 1f
}

data class CorrectionEvidence(
    val before: FrameTones,
    val intentionallyDark: Boolean,
    val neutralCoverage: Float,
    val neutralDisagreement: Float,
    val neutralRgb: FloatArray? = null
)

data class CandidateAssessment(
    val candidate: CandidateSettings,
    val score: Float,
    val reason: String,
    val change: Float,
    val safe: Boolean
)

object CandidatePlanner {
    private fun approach(target: Float, value: Float, initial: Float): Float =
        if (initial < target) max(0f, target - value) else max(0f, value - target)

    private fun deficit(e: CorrectionEvidence, after: FrameTones): Float {
        val before = e.before
        var penalty = 0f
        // Only judge a specific defect when the original provides its evidence.
        if (!e.intentionallyDark && before.whole.median < .33f && before.whole.p90 < .88f)
            penalty += 140f * approach(.43f, after.whole.median, before.whole.median)
        if (!e.intentionallyDark && before.whole.median > .77f && before.whole.p10 > .14f)
            penalty += 110f * approach(.59f, after.whole.median, before.whole.median)
        val subject = before.subject
        val bg = before.background
        if (!e.intentionallyDark && subject != null && subject.coverage > .04f &&
            subject.median < .42f && (bg == null || bg.median > subject.median + .07f)) {
            penalty += 260f * approach(.46f, after.subject?.median ?: after.whole.median, subject.median)
        }
        if (subject != null && bg != null && bg.coverage > .04f && subject.median < .57f &&
            bg.median > .69f && bg.median > subject.median + .17f) {
            penalty += 105f * approach(.66f, after.background?.median ?: after.whole.median, bg.median)
        }
        if (!e.intentionallyDark && before.whole.range in .08f.. .40f &&
            before.whole.median in .26f.. .72f)
            penalty += 65f * max(0f, .51f - after.whole.range)
        return penalty
    }

    /** Passes only measurable, bounded corrections into the renderer. */
    fun propose(e: CorrectionEvidence, hasLut: Boolean): List<CandidateSettings> {
        val b = e.before
        val dark = e.intentionallyDark
        val lift = if (!dark && b.whole.median < .33f && b.whole.p90 < .88f)
            ((.44f - b.whole.median) * 2.1f).coerceIn(0f, .85f) else 0f
        val lower = if (b.whole.median > .77f && b.whole.p10 > .14f)
            ((.64f - b.whole.median) * 1.5f).coerceIn(-.55f, 0f) else 0f
        val s=b.subject;val bg=b.background
        val sEv = if (!dark && s != null && s.coverage > .04f && s.median < .42f &&
            (bg==null || bg.median>s.median+.07f))
            ((.47f-s.median)*2.7f).coerceIn(0f,1.0f) else 0f
        val bEv = if (s != null && bg != null && bg.coverage > .04f &&
            bg.median > .69f && bg.median > s.median+.17f && s.median<.57f)
            ((.66f-bg.median)*1.2f).coerceIn(-.48f,0f) else 0f
        val contrast = if (!dark && b.whole.range in .08f.. .40f && b.whole.median in .26f.. .72f)
            ((.51f-b.whole.range)*.8f).coerceIn(0f,.22f) else 0f
        var wr=1f;var wg=1f;var wb=1f
        val anchors=e.neutralRgb
        if (anchors != null && anchors.size==3 && anchors.all { it.isFinite() && it>15f } &&
            e.neutralCoverage >= .008f && e.neutralDisagreement < .07f) {
            val mean=(anchors[0]+anchors[1]+anchors[2])/3f
            if ((anchors.maxOrNull()!!-anchors.minOrNull()!!)/mean > .065f) {
                wr=(mean/anchors[0]).coerceIn(.86f,1.14f)
                wg=(mean/anchors[1]).coerceIn(.86f,1.14f)
                wb=(mean/anchors[2]).coerceIn(.86f,1.14f)
            }
        }
        val result=mutableListOf(CandidateSettings("Original"))
        if (hasLut) {
            result += CandidateSettings("Learned grade",lutStrength=.85f)
            result += CandidateSettings("Learned with correction",lutStrength=.65f,
                exposureEv=lift+lower,subjectEv=sEv,backgroundEv=bEv,contrast=contrast,
                redGain=wr,greenGain=wg,blueGain=wb)
        }
        result += CandidateSettings("Photographic correction",exposureEv=lift+lower,
            subjectEv=sEv,backgroundEv=bEv,contrast=contrast,redGain=wr,greenGain=wg,blueGain=wb)
        if (sEv != 0f || bEv != 0f)
            result += CandidateSettings("Independent regions",subjectEv=sEv,backgroundEv=bEv,
                subjectContrast=if (s != null && s.range < .31f) .13f else 0f)
        return result.filterIndexed { index,c -> index==0 || !c.isNeutral }
    }

    fun assess(e: CorrectionEvidence, settings: CandidateSettings, after: FrameTones,
               meanChange: Float, newlyClipped: Float, newlyCrushed: Float,
               hueShifted: Float, vividBoosted: Float,
               neutralErrorBefore: Float = 0f, neutralErrorAfter: Float = 0f): CandidateAssessment {
        val valid = meanChange.isFinite() && meanChange <= 38f &&
            newlyClipped <= .003f && newlyCrushed <= .003f &&
            hueShifted <= .035f && vividBoosted <= .035f
        if (!valid) return CandidateAssessment(settings,Float.NEGATIVE_INFINITY,
            "Output damage limit exceeded",meanChange,false)
        if (settings.isNeutral) return CandidateAssessment(settings,0f,"Original preserved",0f,true)
        var correction = deficit(e,e.before) - deficit(e,after)
        val anchors = e.neutralRgb
        if (anchors != null && anchors.size == 3 && anchors.all { it.isFinite() && it > 15f } &&
            e.neutralCoverage >= .008f && e.neutralDisagreement < .07f) {
            val mean = (anchors[0] + anchors[1] + anchors[2]) / 3f
            val cast = (anchors.maxOrNull()!! - anchors.minOrNull()!!) / mean
            if (cast > .065f && neutralErrorBefore.isFinite() && neutralErrorAfter.isFinite()) {
                // A correction earns credit only when *actual corresponding pixels*
                // become more neutral; gain settings alone never earn points.
                correction += 110f * (neutralErrorBefore - neutralErrorAfter)
            }
        }
        // Changing more pixels is a cost, never a source of benefit.
        val cost = meanChange * .13f + newlyClipped*3000f + newlyCrushed*3000f +
            hueShifted*200f + vividBoosted*120f
        val score = correction - cost
        return CandidateAssessment(settings,score,
            "Defect improvement %.2f minus change/artifact cost %.2f".format(correction,cost),
            meanChange,true)
    }

    fun select(scores: List<CandidateAssessment>): CandidateAssessment {
        require(scores.isNotEmpty() && scores[0].candidate.isNeutral)
        return scores.filter { it.safe }.maxByOrNull { it.score } ?: scores[0]
    }
}