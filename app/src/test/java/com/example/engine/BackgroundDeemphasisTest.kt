package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for conservative treatment of backgrounds. A center/focus-based
 * subject mask does not establish semantic confidence, so automatic background styling
 * must abstain. Manually supplied mask rendering remains available for future testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackgroundDeemphasisTest {

    // Centered detailed subject (checkerboard micro-contrast, saturated) on a smooth,
    // busy-ish saturated background: focus-saliency subject mask + center prior.
    private fun subjectScene(): Bitmap {
        val w = 160; val h = 160
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) {
            val inSubject = x in 48..112 && y in 48..112
            if (inSubject) {
                val v = if (((x + y) / 3) % 2 == 0) 60 else 200   // detailed structure
                bitmap.setPixel(x, y, Color.rgb(v, v / 2 + 30, v / 3))
            } else {
                // Saturated, slightly varying background (mask-wise background, not sky hue)
                bitmap.setPixel(x, y, Color.rgb(90 + (x % 7), 60, 110))
            }
        }
        return bitmap
    }

    private fun noSubjectScene(): Bitmap {
        // Uniform smooth frame — nothing saliency-worthy; subject coverage ~0 (blurred).
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(120, 120, 130))
        return bitmap
    }

    @Test
    fun `focus saliency alone never authorizes background desaturation`() {
        val analysis = ImageAnalyzer.analyze(subjectScene())
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("Focus and center priors do not prove a safe background region",
            plan.decisions.find { it.regionMask == "Background" })
    }

    @Test
    fun `no-subject frame gets no background de-emphasis`() {
        val analysis = ImageAnalyzer.analyze(noSubjectScene())
        val plan = ContextualDecisionEngine.evaluate(analysis)
        assertNull("A frame without a dominant subject must not de-emphasize",
            plan.decisions.find { it.regionMask == "Background" })
    }

    // Control render: run the same processing pipeline but force the plan to the given one
    // (the public process() re-derives its own converged plan — for an isolated mask A/B we
    // need the SAME plan minus the Background decisions). Direct pixel-level application of
    // the plan + post-passes mirrors the final render stages of process().
    private fun renderWithPlan(bitmap: Bitmap, analysis: AnalysisResult, plan: EditPlan): Bitmap {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        // Tonal/color application via the same post-passes the renderer uses.
        ImageProcessor.applyWhiteBalance(px, w, h, plan, analysis)
        ImageProcessor.applyOklabColor(px, w, h, plan, analysis)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    @Test
    fun `render does not silently introduce background styling`() {
        val bitmap = subjectScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val render = ImageProcessor.process(bitmap, analysis)
        assertEquals(bitmap.width, render.bitmap.width)
        assertEquals(bitmap.height, render.bitmap.height)
        assertNull("Neither initial nor converged editing may add a background grade",
            render.appliedPlan.decisions.find { it.regionMask == "Background" })
        render.bitmap.recycle()
        bitmap.recycle()
    }

    @Test
    fun `fidelity guards hold without automatic background de-emphasis`() {
        val bitmap = subjectScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val render = ImageProcessor.process(bitmap, analysis)

        val inPx = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(inPx, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val outPx = IntArray(render.bitmap.width * render.bitmap.height)
        render.bitmap.getPixels(outPx, 0, render.bitmap.width, 0, 0, render.bitmap.width, render.bitmap.height)

        fun luma(c: Int) = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
        val inCrushed = inPx.count { luma(it) < 5f }
        val outCrushed = outPx.count { luma(it) < 5f }
        // Negative contrast on the background must not materially crush it.
        assertTrue(
            "Background de-emphasis must not create shadow crushing (in=$inCrushed out=$outCrushed)",
            outCrushed <= inCrushed + (inPx.size * 0.01f).toInt()
        )
        render.bitmap.recycle()
        if (bitmap !== render.bitmap) bitmap.recycle()
    }
}
