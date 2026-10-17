package com.example.engine.learned

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real ONNX models are UNTRAINED fixtures, never included in an APK.
 * Tests source-owned model SAF import, private integrity, full export strength.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UserModelStoreTest {
    private fun context():Context=ApplicationProvider.getApplicationContext()
    private fun fixture(name:String):File {
        val root=System.getenv("LEARNED_TEST_FIXTURE_DIR")
            ?:throw IllegalStateException("Missing independent ONNX test fixture")
        return File(root,name).also{assertTrue(it.isFile)}
    }

    @Test fun importedGlobalPredictorRunsOfflineAndDetectsTamperedPrivateFile() {
        val app=context()
        UserModelStore.clear(app)
        val f=fixture("learned_global_predictor.onnx")
        try {
            val import=UserModelStore.importFromDocument(app,Uri.fromFile(f))
            assertEquals(64,import.sha256.length)
            val persisted=UserModelStore.loadIfVerified(app)
            assertNotNull(persisted)
            assertEquals(import.sha256,persisted!!.sha256)
            assertArrayEquals(import.bytes,persisted.bytes)
            val privateFile=File(app.filesDir,"user-approved-global-predictor.onnx")
            assertTrue(privateFile.isFile)
            privateFile.appendBytes(byteArrayOf(1))
            assertNull("Persisted model bytes must remain pinned",UserModelStore.loadIfVerified(app))
        }finally {UserModelStore.clear(app)}
    }

    @Test fun spatialPredictorRejectedByGlobalOnlyResearchLab() {
        val app=context()
        UserModelStore.clear(app)
        try {
            try {
                UserModelStore.importFromDocument(
                    app,Uri.fromFile(fixture("learned_spatial_predictor.onnx")))
                fail("Global-only model interface not enforced")
            } catch(_:IllegalArgumentException) {
                assertNull(UserModelStore.loadIfVerified(app))
            }
        }finally{UserModelStore.clear(app)}
    }

    @Test fun previewAndExplicitExportOnIdenticalPixelsRenderSameAndRetainAlpha() {
        val bytes=fixture("learned_global_predictor.onnx").readBytes()
        val app=context()
        UserModelStore.clear(app)
        val verified=UserModelStore.importFromDocument(
            app,Uri.fromFile(fixture("learned_global_predictor.onnx")))
        val photo=Bitmap.createBitmap(79,45,Bitmap.Config.ARGB_8888)
        photo.eraseColor(0xff465e75.toInt())
        try {
            val select=LearnedOfflineCandidate.previewApprovedStrengthSearchBytes(
                photo,bytes,verified.sha256)
            assertNotNull("Genuine ONNX candidate should pass safe gate",select)
            select!!
            val exported=LearnedOfflineCandidate.previewApprovedFixedStrengthBytes(
                photo,bytes,verified.sha256,select.strength)
            assertNotNull(exported)
            assertTrue("Preview differs from equivalent native-resolution export",
                select.preview.bitmap.sameAs(exported!!.bitmap))
            assertEquals(255,exported.bitmap.getPixel(3,9) ushr 24)
            assertTrue(exported.damage.safe)
            select.preview.bitmap.recycle()
            exported.bitmap.recycle()
        }finally{photo.recycle();UserModelStore.clear(app)}
    }

    @Test fun badPinPreventsAnyUnapprovedModelRendering() {
        val bytes=fixture("learned_global_predictor.onnx").readBytes()
        val photo=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        photo.eraseColor(0xff223344.toInt())
        try {
            try {
                LearnedOfflineCandidate.previewApprovedFixedStrengthBytes(
                    photo,bytes,"0".repeat(64),.5f)
                fail("Unpinned model ran")
            }catch(_:IllegalArgumentException){}
            assertEquals(0xff223344.toInt(),photo.getPixel(5,5))
        }finally{photo.recycle()}
    }
}
