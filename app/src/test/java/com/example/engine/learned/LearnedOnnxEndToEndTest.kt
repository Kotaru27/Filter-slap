package com.example.engine.learned

import android.graphics.Bitmap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.tanh

/** Real native ONNX Runtime execution with CI-created UNTRAINED test models.
 * No image-specific editing rules; this verifies the actual model/runtime path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LearnedOnnxEndToEndTest {
    private fun fixture(mode:String):ByteArray {
        val directory=System.getenv("LEARNED_TEST_FIXTURE_DIR")
            ?: throw IllegalStateException("CI did not supply fixture path")
        return File(directory,"learned_${mode}_predictor.onnx").readBytes()
    }
    private fun fingerprint(data:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString(""){"%02x".format(it)}

    @Test fun actualGlobalOnnxInferenceProducesNativeResolutionCandidateAndPreservesOriginal() {
        val data=fixture("global")
        val hash=fingerprint(data)
        val original=Bitmap.createBitmap(51,33,Bitmap.Config.ARGB_8888)
        original.eraseColor(0xff4a6988.toInt())
        val untouched=original.getPixel(0,0)
        try {
            val result=LearnedOfflineCandidate.previewApprovedBytes(original,data,hash,"global")
            assertNotNull("Could not run actual ONNX offline model",result)
            result!!
            try {
                assertEquals(51,result.bitmap.width)
                assertEquals(33,result.bitmap.height)
                assertEquals(hash,result.fingerprint)
                assertEquals(untouched,original.getPixel(0,0))
                val pixel=result.bitmap.getPixel(25,16)
                val expectedAdd=(255*.35*tanh(.08)+.5).toInt()
                assertEquals((untouched ushr 24),pixel ushr 24)
                for(shift in intArrayOf(16,8,0)) {
                    val diff=((pixel ushr shift) and 255)-
                        ((untouched ushr shift) and 255)
                    assertTrue("Unexpected known-bias native color transformation",
                        abs(diff-expectedAdd)<=1)
                }
                assertTrue(result.damage.safe)
            }finally {result.bitmap.recycle()}
        }finally {original.recycle()}
    }

    @Test fun spatialOnnxUsesPositionAndLuminanceInsteadOfGlobalConstant() {
        val data=fixture("spatial")
        val original=Bitmap.createBitmap(47,79,Bitmap.Config.ARGB_8888)
        original.eraseColor(0xff586a7b.toInt())
        try {
            val result=LearnedOfflineCandidate.previewApprovedBytes(
                original,data,fingerprint(data),"spatial")
            assertNotNull(result)
            result!!
            try {
                val top=result.bitmap.getPixel(23,0) and 255
                val bottom=result.bitmap.getPixel(23,78) and 255
                assertTrue("Spatial ONNX predictor was ignored",bottom>top+3)
                assertEquals(0xff586a7b.toInt(),original.getPixel(23,78))
            } finally {result.bitmap.recycle()}
        } finally {original.recycle()}
    }

    @Test fun incorrectPinsAndModesFailClosedBeforeEditingSource() {
        val data=fixture("global")
        val source=Bitmap.createBitmap(23,9,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff6a482b.toInt())
        try {
            try {
                LearnedOfflineCandidate.previewApprovedBytes(source,data,"0".repeat(64))
                fail("Wrong model hash accepted")
            }catch(_:IllegalArgumentException) {}
            try {
                LearnedOfflineCandidate.previewApprovedBytes(
                    source,data,fingerprint(data),"spatial")
                fail("Wrong model mode accepted")
            }catch(_:IllegalArgumentException) {}
            assertEquals(0xff6a482b.toInt(),source.getPixel(11,4))
        }finally{source.recycle()}
    }

    @Test fun userCancellationCanInterruptTheModelBeforeFullResolutionRendering() {
        val data=fixture("global")
        val source=Bitmap.createBitmap(67,39,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff8899aa.toInt())
        var calls=0
        try {
            try {
                LearnedOfflineCandidate.previewApprovedBytes(
                    source,data,fingerprint(data),"global") {
                    calls++
                    if(calls>=4)throw InterruptedException("Cancel requested")
                }
                fail("Cancellation ignored")
            }catch(_:InterruptedException) {}
            assertTrue(calls>=4)
            assertEquals(0xff8899aa.toInt(),source.getPixel(2,2))
        }finally {source.recycle()}
    }
}
