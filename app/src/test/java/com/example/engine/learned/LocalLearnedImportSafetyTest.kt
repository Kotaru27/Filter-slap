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
import java.io.File
import java.security.MessageDigest

/** No real training weights, no private photographs; verify strict local use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalLearnedImportSafetyTest {
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString(""){"%02x".format(it)}

    @Test fun corruptOrUnpinnedImportedModelIsNeverLoaded() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        LocalLearnedModelStore.clear(context)
        val path=File(context.filesDir,"user-approved-global-predictor.onnx")
        val preferences=context.getSharedPreferences("locally-imported-ai-model",Context.MODE_PRIVATE)
        val good="local test-only ONNX placeholder".toByteArray()
        try {
            path.writeBytes(good)
            assertNull("Unpinned model was loaded",LocalLearnedModelStore.load(context))
            preferences.edit().putString("sha256",sha(good)).commit()
            assertArrayEquals(good,requireNotNull(LocalLearnedModelStore.load(context)).bytes)
            path.writeBytes(good+"tamper".toByteArray())
            assertNull("Altered model was loaded",LocalLearnedModelStore.load(context))
        } finally {LocalLearnedModelStore.clear(context)}
    }

    @Test fun pinnedPreparedRenderProducesExactlyRepeatablePixelsAndNeverAltersSource() {
        val coefficients=FloatArray(12*8)
        for(ch in intArrayOf(3,7,11)) for(z in 0..7)
            coefficients[ch*8+z]=.12f
        val grid=LearnedCoefficientGrid(coefficients,8,1,1)
        val prepared=LearnedOfflineCandidate.Prepared(grid,.75f,"test-only")
        val source=Bitmap.createBitmap(43,67,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xff607a92.toInt())
        try {
            val a=LearnedOfflineCandidate.renderPrepared(source,prepared)
            val b=LearnedOfflineCandidate.renderPrepared(source,prepared)
            assertNotNull(a);assertNotNull(b)
            try {
                assertTrue("Immutable edit did not reproduce export pixels",
                    requireNotNull(a).bitmap.sameAs(requireNotNull(b).bitmap))
                assertTrue(a.damage.safe && b.damage.safe)
                assertEquals(0xff607a92.toInt(),source.getPixel(21,20))
            } finally {
                a?.bitmap?.recycle()
                b?.bitmap?.recycle()
            }
        } finally{source.recycle()}
    }

    @Test fun unsafeFullResolutionMustRejectInsteadOfSilentlyExporting() {
        val coefficients=FloatArray(12*8)
        for(ch in intArrayOf(3,7,11))for(z in 0..7)
            coefficients[ch*8+z]=3f
        val grid=LearnedCoefficientGrid(coefficients,8,1,1)
        val prepared=LearnedOfflineCandidate.Prepared(grid,1f,"test-only")
        val source=Bitmap.createBitmap(99,111,Bitmap.Config.ARGB_8888)
        source.eraseColor(0xffe8e8e8.toInt())
        try {
            val export=LearnedOfflineCandidate.renderPrepared(source,prepared)
            assertNull("Dangerous output escaped original V9 harm guard",export)
            assertEquals(0xffe8e8e8.toInt(),source.getPixel(14,30))
        }finally{source.recycle()}
    }
}
