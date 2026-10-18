package com.example.engine.v14

import org.junit.Assert.*
import org.junit.Test

/** All inputs synthetic; never coded to benchmark images, names or expected colors. */
class AutoPhotographyTest {
    private fun pixel(r:Int,g:Int,b:Int)=(0xff shl 24) or (r shl 16) or (g shl 8) or b
    private fun pixels(r:Int,g:Int,b:Int,n:Int=300)=IntArray(n){pixel(r,g,b)}
    @Test fun sourceExposureChangesTheAutomaticTreatment() {
        val bright=AutoPhotography.choose(AutoPhotography.evidence(pixels(170,180,165)))
        val dim=AutoPhotography.choose(AutoPhotography.evidence(pixels(72,80,70)))
        assertNotEquals(bright.gamma,dim.gamma)
        assertTrue(dim.gamma<bright.gamma)
    }
    @Test fun aLowKeyImageMustNotBeLiftedToDaylight() {
        val raw=IntArray(800){i->if(i%11==0)pixel(160,175,165) else pixel(22,34,48)}
        val stats=AutoPhotography.evidence(raw)
        assertTrue(stats.lowKey)
        val edit=AutoPhotography.choose(stats)
        assertTrue(edit.lowKey)
        assertTrue("Low-key contrast must be controlled",edit.contrast<.15f)
    }
    @Test fun noInventedColorWhenSourceIsNeutral() {
        val grade=AutoPhotography.choose(AutoPhotography.evidence(pixels(100,100,100)))
        assertEquals(1f,grade.chroma,0f)
        val rgb=floatArrayOf(.39f,.39f,.39f)
        AutoPhotography.apply(rgb,grade,.40f,.39f)
        assertEquals(rgb[0],rgb[1],.000001f)
        assertEquals(rgb[1],rgb[2],.000001f)
    }
    @Test fun fullAttenuationIsIdentityAndFinite() {
        val grade=AutoPhotography.choose(AutoPhotography.evidence(
            IntArray(500){if(it%2==0)pixel(45,62,75) else pixel(210,180,112)}))
        val rgb=floatArrayOf(.16f,.28f,.52f)
        AutoPhotography.apply(rgb,grade.scaled(0f),.25f,.28f)
        assertArrayEquals(floatArrayOf(.16f,.28f,.52f),rgb,0f)
        val edited=floatArrayOf(.16f,.28f,.52f)
        AutoPhotography.apply(edited,grade,.25f,.28f)
        assertTrue(edited.all {it.isFinite()})
        assertFalse(edited.contentEquals(rgb))
    }
    @Test fun treatmentChangesAcrossFlatAndContrastyInput() {
        val flat=IntArray(900){i->if(i%2==0)pixel(112,114,108) else pixel(124,124,117)}
        val contrasty=IntArray(900){i->if(i%2==0)pixel(25,26,29) else pixel(235,233,223)}
        val a=AutoPhotography.choose(AutoPhotography.evidence(flat))
        val b=AutoPhotography.choose(AutoPhotography.evidence(contrasty))
        assertTrue(a.contrast>b.contrast)
    }
}
