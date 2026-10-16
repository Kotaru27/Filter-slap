package com.example.engine.learned

import com.example.engine.v7.DamageReport

/**
 * Conservative full-frame naturalness checks that complement, never relax,
 * the existing V9.3 clipping/crushing/hue/vividness safety thresholds.
 *
 * Universal photographic invariants, not learned from or keyed to any test
 * image: an achromatic source should not develop a color cast; a naturally
 * low-key frame with existing bright anchors should not lose its atmosphere.
 * Source/edited RGB are observed per pixel at full resolution.
 */
internal class LearnedNaturalnessAccumulator {
    private var count=0L
    private var sourceNeutral=0L
    private var disturbedNeutral=0L
    private var nearlyMonochrome=0L
    private var newlyColoredMonochrome=0L
    private var darkPixels=0L
    private var darkLift=0.0
    private var brightAnchors=0L

    fun add(source:Int,edited:Int) {
        val r=(source ushr 16) and 255
        val g=(source ushr 8) and 255
        val b=source and 255
        val rr=(edited ushr 16) and 255
        val gg=(edited ushr 8) and 255
        val bb=edited and 255
        val oldRange=maxOf(r,g,b)-minOf(r,g,b)
        val newRange=maxOf(rr,gg,bb)-minOf(rr,gg,bb)
        val oldLuma=(.2126*r+.7152*g+.0722*b)/255.0
        val newLuma=(.2126*rr+.7152*gg+.0722*bb)/255.0
        count++
        // Natural grey and white materials should stay perceptually neutral
        // under an unrequested automatic edit.
        if(oldRange<=3) {
            sourceNeutral++
            if(newRange>8) disturbedNeutral++
        }
        if(oldRange<=1) {
            nearlyMonochrome++
            if(newRange>5)newlyColoredMonochrome++
        }
        if(oldLuma<.35) {
            darkPixels++
            darkLift+=newLuma-oldLuma
        }
        if(oldLuma>.75) brightAnchors++
    }

    data class Verdict(
        val safe:Boolean,
        val neutralDrift:Double,
        val monochromeColorization:Double,
        val darknessFraction:Double,
        val lightAnchorFraction:Double,
        val darkRegionLuminanceLift:Double
    )

    fun verdict():Verdict {
        val n=count.coerceAtLeast(1).toDouble()
        val neutralDrift=if(sourceNeutral>0)disturbedNeutral.toDouble()/sourceNeutral else 0.0
        val achromaticDrift=if(nearlyMonochrome>0)
            newlyColoredMonochrome.toDouble()/nearlyMonochrome else 0.0
        val darkness=darkPixels/n
        val bright=brightAnchors/n
        val lift=if(darkPixels>0) darkLift/darkPixels else 0.0

        // Broad photograph-invariant guardrails; neither image-specific
        // thresholds nor automatic scene/category identification.
        val heavilyNeutral=sourceNeutral>=count/20L
        val fullyMonochrome=nearlyMonochrome/n>=.98
        val neutralPreserved=!heavilyNeutral || neutralDrift<=.025
        val monochromePreserved=!fullyMonochrome || achromaticDrift<=.005
        val lowKeyHasBrightAnchors=darkness>=.60 && bright>=.01
        val moodPreserved=!lowKeyHasBrightAnchors || lift<=.07

        return Verdict(neutralPreserved && monochromePreserved && moodPreserved,
            neutralDrift,achromaticDrift,darkness,bright,lift)
    }
}
