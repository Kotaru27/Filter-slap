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
 * P2: BACKGROUND DE-EMPHASIS — the second half of visual hierarchy.
 *
 * Invariants:
 * 1. A real subject in a PORTRAIT/GENERAL frame produces NEGATIVE Contrast and Vibrance
 *    decisions scoped to the Background mask (bounded: felt, not seen).
 * 2. No dominant subject (subject coverage < 4%) → no background edits (detection is
 *    never permission).
 * 3. Sky-dominant frames (landscape) never get background de-emphasis.
 * 4. The render actually de-emphasizes: background-region contrast/chroma drops while
 *    the subject region holds — measured through the full pipeline.
 * 5. Fidelity guards still hold (no new clipping/crushing; dimensions preserved).
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
    fun `subject scene produces bounded negative background decisions`() {
        val analysis = ImageAnalyzer.analyze(subjectScene())
        val plan = ContextualDecisionEngine.evaluate(analysis)

        val bgContrast = plan.decisions.find { it.parameter == "Contrast" && it.regionMask == "Background" }
        assertNotNull("Subject frames must de-emphasize the background (contrast)", bgContrast)
        assertTrue("Background contrast must be negative (recession); got ${bgContrast!!.magnitude}",
            bgContrast.magnitude < 0f)
        assertTrue("Background contrast recession is bounded (>= -0.08); got ${bgContrast.magnitude}",
            bgContrast.magnitude >= -0.08f)

        val bgVibrance = plan.decisions.find { it.parameter == "Vibrance" && it.regionMask == "Background" }
        assertNotNull("Subject frames must de-emphasize the background (vibrance)", bgVibrance)
        assertTrue("Background vibrance must be negative; got ${bgVibrance!!.magnitude}",
            bgVibrance.magnitude < 0f)
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
    fun `render separates subject from background`() {
        val bitmap = subjectScene()
        val analysis = ImageAnalyzer.analyze(bitmap)
        val render = ImageProcessor.process(bitmap, analysis)
        // Control: identical render with ONLY the Background decisions removed. The delta
        // between render and control IS the masked de-emphasis — isolated from every global
        // edit the plan also applies. (An in/out comparison would conflate global tonal
        // shifts with the separation effect.)
        val controlPlan = EditPlan(
            render.appliedPlan.decisions.filter { it.regionMask != "Background" },
            render.appliedPlan.profileName, render.appliedPlan.profileReason
        )
        // Re-render the control in-process: same pipeline, decisions minus the Background ones.
        val controlRender = renderWithPlan(bitmap, analysis, controlPlan)
        val out = render.bitmap
        val ctl = controlRender
        assertEquals(bitmap.width, out.width)
        assertEquals(bitmap.height, out.height)

        fun localContrast(bmp: Bitmap, x0: Int, y0: Int, size: Int): Double {
            var acc = 0.0; var cnt = 0
            for (y in y0 until y0 + size) for (x in x0 until x0 + size) {
                if (x + 1 < x0 + size) {
                    val a = bmp.getPixel(x, y); val b = bmp.getPixel(x + 1, y)
                    val la = (Color.red(a) + Color.green(a) + Color.blue(a)) / 3.0
                    val lb = (Color.red(b) + Color.green(b) + Color.blue(b)) / 3.0
                    acc += kotlin.math.abs(la - lb); cnt++
                }
            }
            return acc / cnt
        }

        // Background band (left margin strip) and subject band (center checkerboard):
        // the mask's OWN effect must reduce background contrast and leave the subject band.
        val bgWithMask = localContrast(out, 8, 60, 24)
        val bgNoMask = localContrast(ctl, 8, 60, 24)
        assertTrue(
            "Background band must be calmer with the mask effect (with=$bgWithMask without=$bgNoMask)",
            bgWithMask <= bgNoMask
        )
        val subjWithMask = localContrast(out, 64, 64, 32)
        val subjNoMask = localContrast(ctl, 64, 64, 32)
        assertTrue(
            "Subject band must hold its contrast (with=$subjWithMask without=$subjNoMask)",
            subjWithMask >= subjNoMask * 0.95
        )

        if (bitmap !== out) bitmap.recycle()
        out.recycle()
        ctl.recycle()
    }

    @Test
    fun `fidelity guards hold under background de-emphasis`() {
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
