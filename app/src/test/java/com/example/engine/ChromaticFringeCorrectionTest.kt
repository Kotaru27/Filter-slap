package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Color evidence, not object identities or a particular photograph, governs CA correction. */
class ChromaticFringeCorrectionTest {
    private fun rgb(r: Int, g: Int, b: Int) = -0x1000000 or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c ushr 16) and 255
    private fun green(c: Int) = (c ushr 8) and 255

    @Test fun `colored object edges remain bit identical`() {
        val width = 96; val height = 64
        val colors = listOf(rgb(215, 140, 38), rgb(45, 115, 170), rgb(85, 160, 60), rgb(90, 82, 78))
        for (shift in 0..3) {
            val src = IntArray(width * height) { i ->
                val x = i % width; val y = i / width
                colors[((x + shift) / 16 + y / 21) % colors.size]
            }
            val copy = src.copyOf()
            assertEquals(0, ChromaticFringeCorrection.correctInPlace(copy, width, height, 0.8f))
            assertTrue(copy.contentEquals(src))
        }
    }

    @Test fun `shifted channel fringe is reduced without changing neutral regions`() {
        val width = 96; val height = 64
        val original = IntArray(width * height) { i ->
            val x = i % width
            rgb(if (x < 50) 40 else 220, if (x < 48) 40 else 220, if (x < 48) 40 else 220)
        }
        val corrected = original.copyOf()
        val changes = ChromaticFringeCorrection.correctInPlace(corrected, width, height, 0.7f)
        assertTrue("real channel displacement should produce local corrections", changes > 0)
        val i = 32 * width + 48
        assertTrue("fringe chroma should move toward surrounding neutral colors",
            kotlin.math.abs(red(corrected[i]) - green(corrected[i])) <
                kotlin.math.abs(red(original[i]) - green(original[i])))
        for (x in listOf(7, 24, 69, 87)) {
            assertEquals("away from fringes no changes are permitted", original[32 * width + x], corrected[32 * width + x])
        }
    }

    @Test fun `clean gray edge and uniform color stay identical`() {
        val w = 96; val h = 64
        val clean = IntArray(w * h) { i -> val v = if (i % w < 48) 40 else 220; rgb(v, v, v) }
        val uniform = IntArray(w * h) { rgb(150, 70, 160) }
        assertEquals(0, ChromaticFringeCorrection.correctInPlace(clean, w, h, 0.7f))
        assertEquals(0, ChromaticFringeCorrection.correctInPlace(uniform, w, h, 0.7f))
    }

    @Test fun `tiny and zero intensity frames are safe`() {
        val tooSmall = IntArray(16) { rgb(255, 0, 255) }
        assertEquals(0, ChromaticFringeCorrection.correctInPlace(tooSmall, 4, 4, 1f))
        val pixels = IntArray(24 * 24) { rgb(190, 130, 80) }
        val before = pixels.copyOf()
        assertEquals(0, ChromaticFringeCorrection.correctInPlace(pixels, 24, 24, 0f))
        assertTrue(before.contentEquals(pixels))
    }
}
