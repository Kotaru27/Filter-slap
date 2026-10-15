package com.example.engine.learned

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.tanh

/** Identity, output geometry, full-resolution and fail-closed contract.
 * These synthetic invariants are NOT visual-quality test evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnedOfflineCandidateTest {
    private fun grid(fill:Float=0f,side:Int=1):FloatArray =
        FloatArray(12*8*side*side){ fill }
    private fun pixel(a:Int,r:Int,g:Int,b:Int):Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun zeroGridIsBitwiseIdentityIncludingAlphaAndExtremeColors() {
        for(side in listOf(1,16)) {
            val renderer=LearnedCoefficientGrid(grid(side=side),8,side,side)
            for(color in listOf(0,0x1f124568,0xff000000.toInt(),
                0xffffffff.toInt(),0xffee45bd.toInt(),0xff3472dc.toInt())) {
                for(y in listOf(0f,.12f,.5f,1f))
                    for(x in listOf(0f,.32f,1f))
                        assertEquals(color,renderer.pixel(color,x,y))
            }
        }
    }

    @Test fun constantBiasMatchesReferenceTanhAcrossGlobalCoordinates() {
        val entries=grid()
        for(channel in listOf(3,7,11))
            for(z in 0..7) entries[channel*8+z]=.20f
        val renderer=LearnedCoefficientGrid(entries,8,1,1)
        val p=pixel(255,80,100,120)
        val expectedDelta=(255*.35*tanh(.20)).toInt()
        for(y in listOf(0f,.5f,1f))for(x in listOf(0f,.5f,1f)) {
            val out=renderer.pixel(p,x,y)
            assertEquals(255,out ushr 24)
            assertTrue(kotlin.math.abs((out ushr 16 and 255)-80-expectedDelta)<=1)
            assertTrue(kotlin.math.abs((out ushr 8 and 255)-100-expectedDelta)<=1)
            assertTrue(kotlin.math.abs((out and 255)-120-expectedDelta)<=1)
        }
    }

    @Test fun trilinearGridRespectsLuminanceAndGlobalXYCoordinates() {
        val side=16
        val coefficients=grid(side=side)
        for(c in listOf(3,7,11))for(z in 0..7)for(y in 0 until side)
            for(x in 0 until side) {
                val index=(((c*8+z)*side+y)*side+x)
                coefficients[index]=.04f*z+.02f*x
            }
        val renderer=LearnedCoefficientGrid(coefficients,8,side,side)
        val p=pixel(255,90,90,90)
        val low=renderer.pixel(p,0f,.1f) and 255
        val middle=renderer.pixel(p,.5f,.1f) and 255
        val high=renderer.pixel(p,1f,.9f) and 255
        assertTrue(low<middle && middle<high)
        val spatialMid=renderer.pixel(p,.5f,.5f)
        assertEquals(spatialMid,renderer.pixel(p,.5f,.5f))
    }

    @Test fun invalidOnnxLayoutAndNonfiniteCoefficientsRejected() {
        try { LearnedCoefficientGrid(FloatArray(12*7),7,1,1);fail("bad bins") }
        catch(_:IllegalArgumentException) {}
        try { LearnedCoefficientGrid(FloatArray(12*8*2*2),8,2,2);fail("bad grid") }
        catch(_:IllegalArgumentException) {}
        val entries=grid();entries[5]=Float.NaN
        try { LearnedCoefficientGrid(entries,8,1,1);fail("NaN") }
        catch(_:IllegalArgumentException) {}
    }

    @Test fun nativeSizeIdentityCandidateAndMissingModelFailClosed() {
        val original=Bitmap.createBitmap(7,5,Bitmap.Config.ARGB_8888)
        for(y in 0 until 5)for(x in 0 until 7)
            original.setPixel(x,y,pixel(255,x*28,y*37,90))
        try {
            val context=ApplicationProvider.getApplicationContext<Context>()
            assertNull(LearnedOfflineCandidate.previewIfApproved(context,original,null))
            val preview=LearnedOfflineCandidate.renderCandidate(original,
                LearnedCoefficientGrid(grid(),8,1,1))
            assertNotNull(preview)
            assertTrue(original.sameAs(preview!!.bitmap))
            assertEquals(0f,preview.damage.meanChange,0f)
            preview.bitmap.recycle()
        } finally { original.recycle() }
    }

    @Test fun damageGateRejectsExcessiveLearnedCandidateWithoutReplacingOriginal() {
        val original=Bitmap.createBitmap(11,11,Bitmap.Config.ARGB_8888)
        original.eraseColor(pixel(255,96,96,96))
        val entries=grid()
        for(c in listOf(3,7,11))for(z in 0..7)entries[c*8+z]=3f
        try {
            assertNull(LearnedOfflineCandidate.renderCandidate(original,
                LearnedCoefficientGrid(entries,8,1,1)))
            assertEquals(pixel(255,96,96,96),original.getPixel(5,5))
        } finally {original.recycle()}
    }
}
