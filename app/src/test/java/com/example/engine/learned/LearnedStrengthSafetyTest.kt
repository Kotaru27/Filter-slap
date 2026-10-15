package com.example.engine.learned

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The EXACT existing V9 DamageReport.safe policy applies at each strength.
 * These fixtures are synthetic engineering tests, not quality evidence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnedStrengthSafetyTest {
    private fun grid(bias:Float):LearnedCoefficientGrid {
        val values=FloatArray(12*8)
        for(channel in intArrayOf(3,7,11))
            for(z in 0..7)values[channel*8+z]=bias
        return LearnedCoefficientGrid(values,8,1,1)
    }

    @Test fun fullStrengthMatchesExistingPixelRendererExactly() {
        val input=Bitmap.createBitmap(31,27,Bitmap.Config.ARGB_8888)
        for(y in 0 until input.height)for(x in 0 until input.width)
            input.setPixel(x,y,0xff000000.toInt() or
                ((30+x*5).coerceAtMost(255) shl 16) or
                ((50+y*4).coerceAtMost(255) shl 8) or 76)
        val grid=grid(.15f)
        try {
            val old=LearnedOfflineCandidate.renderCandidate(input,grid)
            val alpha1=LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,1f)
            assertNotNull(old); assertNotNull(alpha1)
            assertTrue(old!!.bitmap.sameAs(alpha1!!.bitmap))
            assertEquals(old.damage,alpha1.damage)
            old.bitmap.recycle();alpha1.bitmap.recycle()
        }finally {input.recycle()}
    }

    @Test fun strongerChangeRejectedButWeakerSafeWithoutChangingPolicy() {
        val input=Bitmap.createBitmap(29,37,Bitmap.Config.ARGB_8888)
        input.eraseColor(0xff606060.toInt())
        val grid=grid(.8f)
        try {
            assertNull("Full strength must fail V9 existing max-change rule",
                LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,1f))
            val half=LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,.5f)
            assertNotNull("Half strength should satisfy unchanged V9 guard",half)
            try {
                assertTrue(half!!.damage.safe)
                assertTrue(half.damage.meanChange<=35f)
                val delta=(half.bitmap.getPixel(12,8) and 255)-96
                assertTrue(delta in 20..35)
            }finally{half?.bitmap?.recycle()}
            val zero=LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,0f)
            assertNotNull(zero)
            try{ assertTrue(input.sameAs(zero!!.bitmap)) }
            finally{zero?.bitmap?.recycle()}
        }finally{input.recycle()}
    }

    @Test fun fullStrengthHarmCheckCannotBeBypassedByStrongerCandidate() {
        val input=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888)
        input.eraseColor(0xfff4f4f4.toInt())
        val grid=grid(3f)
        try {
            // Even a mild change near white can create new clipping.
            val full=LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,1f)
            assertNull(full)
            val zero=LearnedOfflineCandidate.renderCandidateAtStrength(input,grid,0f)
            assertNotNull(zero)
            try{assertTrue(input.sameAs(zero!!.bitmap))}
            finally{zero?.bitmap?.recycle()}
        }finally{input.recycle()}
    }
}
