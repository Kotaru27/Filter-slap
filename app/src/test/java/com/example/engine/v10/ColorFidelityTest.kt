package com.example.engine.v10

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.abs

class ColorFidelityTest {
    @Test fun neutralInputsRemainNeutralThroughToneEdits() {
        for(v in 15..230 step 23) for(target in listOf(.08f,.25f,.52f,.82f)) {
            val original=v/255f
            val t=floatArrayOf(target,target,target)
            ColorFidelity.stabilizeToneOnly(original,original,original,t)
            assertEquals(t[0],t[1],.00002f)
            assertEquals(t[1],t[2],.00002f)
        }
    }
    @Test fun toneOnlyUsesCandidateLuminanceWithoutArbitraryColorBoost() {
        for(r in 20..220 step 40)for(g in 18..216 step 37)for(b in 23..216 step 38){
            val src=floatArrayOf(r/255f,g/255f,b/255f)
            val dst=floatArrayOf((src[0]*1.28f).coerceAtMost(.97f),
                (src[1]*1.28f).coerceAtMost(.97f),(src[2]*1.28f).coerceAtMost(.97f))
            val origLin=floatArrayOf(ColorFidelity.linear(src[0]),ColorFidelity.linear(src[1]),ColorFidelity.linear(src[2]))
            val xBefore=origLin[0]-origLin[1];val yBefore=origLin[2]-origLin[1]
            ColorFidelity.stabilizeToneOnly(src[0],src[1],src[2],dst)
            assertTrue(dst.all{it.isFinite()&&it>=0f&&it<=1f})
            val afterLin=floatArrayOf(ColorFidelity.linear(dst[0]),ColorFidelity.linear(dst[1]),ColorFidelity.linear(dst[2]))
            val xAfter=afterLin[0]-afterLin[1];val yAfter=afterLin[2]-afterLin[1]
            if(abs(xBefore)>.01f && abs(yBefore)>.01f){
                assertTrue("Hue direction reversed",xBefore*xAfter>=-.00003f&&yBefore*yAfter>=-.00003f)
                assertTrue("Color channel spread amplified",abs(xAfter)<=abs(xBefore)+.001f)
                assertTrue("Color channel spread amplified",abs(yAfter)<=abs(yBefore)+.001f)
            }
        }
    }
    @Test fun perceptualScoreDetectsUnrequestedChromaBoost() {
        val colors=IntArray(220) { i ->
            val r=60+i%110; val g=40+(i*7)%95; val b=38+(i*17)%110
            -0x1000000 or (r shl 16) or (g shl 8) or b
        }
        val identical=ColorFidelity.evaluate(colors,colors,1)
        assertEquals(0f,identical.penalty,.000001f)
        val raised=IntArray(colors.size){i-> val p=colors[i];
            val rr=((p ushr 16 and 255)*1.6f).toInt().coerceAtMost(255)
            val gg=((p ushr 8 and 255)*1.6f).toInt().coerceAtMost(255)
            val bb=((p and 255)*1.6f).toInt().coerceAtMost(255)
            -0x1000000 or (rr shl 16) or (gg shl 8) or bb
        }
        val shifted=ColorFidelity.evaluate(colors,raised,1)
        assertTrue(shifted.meanChromaDelta>0f)
        assertTrue(shifted.excessChroma>0f)
    }
    @Test fun unchangedOutputHasExactZeroPixelDifferences() {
        val unchanged=IntArray(128){i-> -0x1000000 or (((i*7)%256) shl 16) or (((i*17)%256) shl 8) or ((i*97)%256)}
        assertEquals(0f,ColorFidelity.evaluate(unchanged,unchanged).meanChromaDelta,.000001f)
    }
}