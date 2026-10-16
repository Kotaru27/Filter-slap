package com.example.engine.learned

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Universal photographic invariants; NEVER match or label specific photos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnedNaturalnessSafetyTest {
    private fun grid(r:Float,g:Float,b:Float):LearnedCoefficientGrid {
        val coeff=FloatArray(12*8)
        for(z in 0 until 8) {
            coeff[3*8+z]=r
            coeff[7*8+z]=g
            coeff[11*8+z]=b
        }
        return LearnedCoefficientGrid(coeff,8,1,1)
    }

    @Test fun neutralSourceCannotBeColorizedEvenWhenOldDamageGateWouldPermit() {
        val gray=Bitmap.createBitmap(35,27,Bitmap.Config.ARGB_8888)
        gray.eraseColor(0xff606060.toInt())
        val colorizing=grid(0f,.3f,0f)
        try {
            assertNull("Automatic edit should not turn neutral greys green",
                LearnedOfflineCandidate.renderCandidateAtStrength(gray,colorizing,1f))
            val chosen=LearnedOfflineCandidate.strongestSafeStrengthForTest(gray,colorizing)
            assertTrue("Must attenuate dangerous neutral color cast",chosen==null||chosen<=.25f)
        }finally{gray.recycle()}
    }

    @Test fun lowKeySceneWithStrongHighlightsMustKeepItsDarkAtmosphere() {
        val dark=Bitmap.createBitmap(51,41,Bitmap.Config.ARGB_8888)
        dark.eraseColor(0xff202020.toInt())
        // Only generic luminance structure is observed; no recognition of
        // objects, scene types, colors, or photograph identifiers.
        for(y in 0 until 3)for(x in 0 until dark.width)
            dark.setPixel(x,y,0xffffffff.toInt())
        val flatLift=grid(.4f,.4f,.4f)
        try {
            assertNull("Unrequested global exposure lift must preserve low-key character",
                LearnedOfflineCandidate.renderCandidateAtStrength(dark,flatLift,1f))
            val strength=LearnedOfflineCandidate.strongestSafeStrengthForTest(dark,flatLift)
            assertTrue(strength==null || strength<1f)
        }finally{dark.recycle()}
    }

    @Test fun trulyUnderexposedImageWithoutBrightAnchorsCanStillBeRecovered() {
        val dark=Bitmap.createBitmap(33,21,Bitmap.Config.ARGB_8888)
        dark.eraseColor(0xff282828.toInt())
        try {
            val output=LearnedOfflineCandidate.renderCandidateAtStrength(dark,
                grid(.2f,.2f,.2f),1f)
            assertNotNull("Guard must not reject all legitimate low-light recovery",output)
            try {
                assertTrue(output!!.damage.safe)
                val p=output.bitmap.getPixel(0,0)
                assertTrue((p and 255)>40)
                assertEquals((p ushr 16) and 255,(p ushr 8) and 255)
            }finally{output?.bitmap?.recycle()}
        }finally{dark.recycle()}
    }

    @Test fun ordinaryFullFrameNeutralEditsKeepAlphaAndExactSourceDimensions() {
        val source=Bitmap.createBitmap(13,19,Bitmap.Config.ARGB_8888)
        source.eraseColor(0x88464646.toInt())
        try {
            val output=LearnedOfflineCandidate.renderCandidateAtStrength(
                source,grid(.09f,.09f,.09f),1f)
            assertNotNull(output)
            try {
                assertEquals(13,output!!.bitmap.width)
                assertEquals(19,output.bitmap.height)
                assertEquals(0x88,output.bitmap.getPixel(5,7) ushr 24)
                assertTrue(output.damage.safe)
            }finally{output?.bitmap?.recycle()}
        }finally{source.recycle()}
    }
}
