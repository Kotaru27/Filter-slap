package com.example.engine.v6

import kotlin.math.abs
import kotlin.math.max

/** Testable, conservative acceptance rule: this is a damage *screen*, not an aesthetic judge. */
object V6DecisionPolicy {
    data class Result(val accepted: Boolean, val reason: String, val meanChange: Float)

    fun decide(
        original: IntArray,
        proposed: IntArray,
        outOfGamutFraction: Float,
        enoughEvidenceToEdit: Boolean
    ): Result {
        if (!enoughEvidenceToEdit) return Result(false, "No measurable correction required", 0f)
        if (original.size != proposed.size || original.isEmpty()) return Result(false, "Invalid comparison", 0f)
        if (!outOfGamutFraction.isFinite() || outOfGamutFraction > 0.012f)
            return Result(false, "Model predicted significant out-of-gamut colors", 0f)
        var difference = 0.0
        var origClip = 0
        var newClip = 0
        var origSat = 0.0
        var newSat = 0.0
        for (i in original.indices) {
            val o = original[i]; val p = proposed[i]
            val or = o ushr 16 and 255; val og = o ushr 8 and 255; val ob = o and 255
            val pr = p ushr 16 and 255; val pg = p ushr 8 and 255; val pb = p and 255
            difference += (abs(or-pr) + abs(og-pg) + abs(ob-pb)) / 3.0
            if (max(or,max(og,ob)) >= 252 || minOf(or,og,ob) <= 3) origClip++
            if (max(pr,max(pg,pb)) >= 252 || minOf(pr,pg,pb) <= 3) newClip++
            origSat += max(or,max(og,ob))-minOf(or,og,ob)
            newSat += max(pr,max(pg,pb))-minOf(pr,pg,pb)
        }
        val n = original.size.toDouble()
        val changed = (difference / n).toFloat()
        if (changed < 1.8f) return Result(false, "No visible benefit expected", changed)
        if (changed > 42f) return Result(false, "Excessive global color or tone shift", changed)
        if ((newClip - origClip) / n > 0.025) return Result(false, "Introduces clipping", changed)
        if (abs(newSat - origSat) / n > 27.0) return Result(false, "Excessive saturation shift", changed)
        return Result(true, "Passed objective damage checks (not an aesthetic guarantee)", changed)
    }
}
