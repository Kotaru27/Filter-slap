package com.example.engine.v9

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.engine.ImageAnalyzer
import com.example.engine.PhotoRegressionFixtures
import com.example.engine.v7.OfflineEnhancer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Measurement-only baseline. No edits to production source, recipes, quality
 * thresholds, or class-based rules. Synthetic fixtures are diagnostic, NOT proof
 * that V9 aesthetically improves real photographs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class V9IndependentBaselineAuditTest {

    @Test fun `existing V9 renderer is repeatable and reports measured results`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtures: List<Pair<String, () -> Bitmap>> = listOf(
            "balanced" to { PhotoRegressionFixtures.balancedGray() },
            "low_key" to { PhotoRegressionFixtures.lowKey() },
            "sky" to { PhotoRegressionFixtures.skyScene() },
            "vegetation" to { PhotoRegressionFixtures.vegetationScene() },
            "skin_tone" to { PhotoRegressionFixtures.skinToneScene() },
            "warm_cool" to { PhotoRegressionFixtures.warmCoolSplit() },
            "fine_detail" to { PhotoRegressionFixtures.fineDetail() }
        )
        // No picture data is written: only aggregate numerical measurements.
        val lines = mutableListOf(
            "fixture,status,strategy,mask_used,model_strength,mean_abs_rgb_change," +
                "mean_luma_before,mean_luma_after,mean_channel_range_before," +
                "mean_channel_range_after,newly_clipped,newly_crushed,hue_shifted," +
                "vivid_boosted,render_ms"
        )
        for ((name, factory) in fixtures) {
            val original = factory()
            var enhanced: Bitmap? = null
            var replayed: Bitmap? = null
            try {
                val analysis = ImageAnalyzer.analyze(original)
                val output = OfflineEnhancer.process(context, original, analysis)
                enhanced = output.bitmap
                val recipe = requireNotNull(output.recipe)
                val outcome = requireNotNull(output.outcome)
                val report = outcome.damage
                assertTrue("Pixel-safety gate failed for $name", report.safe)
                assertFalse("Input was recycled for $name", original.isRecycled)
                assertEquals(original.width, enhanced.width)
                assertEquals(original.height, enhanced.height)
                val replay = OfflineEnhancer.render(original, recipe, analysis)
                replayed = replay.bitmap
                assertTrue("Same input and recipe not deterministic: $name", enhanced.sameAs(replayed))
                val source = IntArray(original.width * original.height)
                val target = IntArray(enhanced.width * enhanced.height)
                original.getPixels(source, 0, original.width, 0, 0, original.width, original.height)
                enhanced.getPixels(target, 0, enhanced.width, 0, 0, enhanced.width, enhanced.height)
                var sumLumaBefore = 0.0
                var sumLumaAfter = 0.0
                var rangeBefore = 0.0
                var rangeAfter = 0.0
                var sumAbsChange = 0.0
                for (i in source.indices) {
                    val p = source[i]; val q = target[i]
                    val r = p ushr 16 and 255; val g = p ushr 8 and 255; val b = p and 255
                    val rr = q ushr 16 and 255; val gg = q ushr 8 and 255; val bb = q and 255
                    sumLumaBefore += (0.2126*r + 0.7152*g + 0.0722*b) / 255.0
                    sumLumaAfter += (0.2126*rr + 0.7152*gg + 0.0722*bb) / 255.0
                    rangeBefore += max(r, max(g,b)) - min(r, min(g,b))
                    rangeAfter += max(rr, max(gg,bb)) - min(rr, min(gg,bb))
                    sumAbsChange += (abs(rr-r) + abs(gg-g) + abs(bb-b)) / 3.0
                }
                val n = source.size.toDouble()
                fun number(value: Double) = String.format(Locale.US, "%.6f", value)
                lines += listOf(
                    name,outcome.status,recipe.strategy,outcome.maskUsed.toString(),
                    number(outcome.modelStrength.toDouble()),number(sumAbsChange/n),
                    number(sumLumaBefore/n),number(sumLumaAfter/n),
                    number(rangeBefore/n),number(rangeAfter/n),
                    number(report.newlyClipped.toDouble()),number(report.newlyCrushed.toDouble()),
                    number(report.hueShifted.toDouble()),number(report.vividBoosted.toDouble()),
                    output.stats.computeTimeMs.toString()
                ).joinToString(",")
            } finally {
                if (replayed != null && replayed !== original && !replayed.isRecycled) replayed.recycle()
                if (enhanced != null && enhanced !== original && !enhanced.isRecycled) enhanced.recycle()
                if (!original.isRecycled) original.recycle()
            }
        }
        val directory = File("build/v9-independent-audit")
        require(directory.mkdirs() || directory.isDirectory)
        File(directory, "synthetic-metrics.csv").writeText(lines.joinToString("\n") + "\n")
        lines.forEach { println("V9_MEASUREMENT $it") }
        assertEquals("Some planned fixtures were not measured", fixtures.size + 1, lines.size)
    }
}
