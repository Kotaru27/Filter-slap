package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Deterministic synthetic fixtures for photographic regression testing.
 *
 * All fixtures are SMALL (typically 100x100 or smaller) and algorithmically
 * generated. They are NOT substitutes for real photographs — they test the
 * algorithm's behavior on controlled inputs.
 *
 * CLASSIFICATION:
 * 1. Normal/balanced exposure — balancedGray
 * 2. Underexposed — darkGray, crushedShadows
 * 3. Overexposed — brightGray, clippedHighlights
 * 4. High-key — highKey
 * 5. Low-key — lowKey, lowKeyWithCrushing
 * 6. High contrast — highContrastEdge, checkerboard
 * 7. Low contrast — lowContrastGradient
 * 8. Sky-dominant — skyScene
 * 9. Landscape/vegetation — vegetationScene
 * 10. Portrait/skin — skinToneScene
 * 11. Mixed lighting — warmCoolSplit
 * 12. Noisy/low-light — noisyPattern
 * 13. High-detail — edgePattern, fineDetail
 * 14. Low-detail/blurred — smoothGradient
 * 15. Challenging highlight clipping — clippedHighlights
 * 16. Challenging shadow crushing — crushedShadows
 */
object PhotoRegressionFixtures {

    // === 1. NORMAL/BALANCED ===

    /** 100x100 uniform neutral gray (128,128,128). Balanced exposure, zero contrast. */
    fun balancedGray(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(128, 128, 128))
        return bitmap
    }

    // === 2. UNDEREXPOSED ===

    /** 100x100 uniform dark gray (30,30,30). Underexposed, isDarkImage=true. */
    fun darkGray(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 30, 30))
        return bitmap
    }

    /** 100x100 mostly very dark (5,5,5) with 1% slightly brighter (20,20,20). Shadow crushing. */
    fun crushedShadows(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 10 && y < height / 10) 20 else 5
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 3. OVEREXPOSED ===

    /** 100x100 uniform bright gray (230,230,230). Overexposed, isBrightImage=true. */
    fun brightGray(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(230, 230, 230))
        return bitmap
    }

    /** 100x100 mostly very bright (250,250,250) with 1% slightly darker (235,235,235). Highlight clipping. */
    fun clippedHighlights(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 10 && y < height / 10) 235 else 250
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 4. HIGH-KEY ===

    /** 100x100 mostly bright (220-250 range) with few darker accents. High-key image. */
    fun highKey(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = 220 + ((x + y) % 30)
                val v = base.coerceAtMost(255)
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 5. LOW-KEY ===

    /** 100x100 mostly dark (20-50 range) with few brighter accents. Low-key image. */
    fun lowKey(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val base = 20 + ((x + y) % 30)
                bitmap.setPixel(x, y, Color.rgb(base, base, base))
            }
        }
        return bitmap
    }

    /** 100x100 mostly very dark (5-25) with tiny bright spot. Low-key with crushing. */
    fun lowKeyWithCrushing(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < 5 && y < 5) 180 else 5 + ((x + y) % 20)
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 6. HIGH CONTRAST ===

    /** 100x100 sharp edge: left half black, right half white. */
    fun highContrastEdge(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 2) 0 else 255
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    /** 2x2 checkerboard: alternating black and white. Scales to high-contrast. */
    fun checkerboard(): Bitmap {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.BLACK)
        bitmap.setPixel(1, 0, Color.BLACK)
        bitmap.setPixel(0, 1, Color.WHITE)
        bitmap.setPixel(1, 1, Color.WHITE)
        return bitmap
    }

    // === 7. LOW CONTRAST ===

    /** 100x100 narrow gradient: values 115-135 across width. Low contrast. */
    fun lowContrastGradient(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = 115 + (x * 20 / width)
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 8. SKY-DOMINANT ===

    /** 100x100: top 60% blue sky (70,130,210), bottom 40% gray ground. */
    fun skyScene(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val skyY = (height * 0.6).toInt()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < skyY) {
                    bitmap.setPixel(x, y, Color.rgb(70, 130, 210))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(128, 128, 128))
                }
            }
        }
        return bitmap
    }

    // === 9. LANDSCAPE/VEGETATION ===

    /** 100x100: top 40% blue sky, bottom 60% green vegetation. */
    fun vegetationScene(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val skyY = (height * 0.4).toInt()
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (y < skyY) {
                    bitmap.setPixel(x, y, Color.rgb(70, 130, 210))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(51, 153, 71))
                }
            }
        }
        return bitmap
    }

    // === 10. PORTRAIT/SKIN ===

    /** 100x100: warm orange (220,160,120) simulating skin tone. HSL ~(24, 50%, 68%). */
    fun skinToneScene(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(220, 160, 120))
        return bitmap
    }

    // === 11. MIXED LIGHTING ===

    /** 100x100: left half warm (200,120,80), right half cool (80,120,200). */
    fun warmCoolSplit(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x < width / 2) {
                    bitmap.setPixel(x, y, Color.rgb(200, 120, 80))
                } else {
                    bitmap.setPixel(x, y, Color.rgb(80, 120, 200))
                }
            }
        }
        return bitmap
    }

    // === 12. NOISY/LOW-LIGHT ===

    /** 100x100: checkerboard of 80/180. High local variance, low gradient → noise detection. */
    fun noisyPattern(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if ((x + y) % 2 == 0) 80 else 180
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 13. HIGH DETAIL ===

    /** 100x100: sharp vertical edge at center. High gradient, high variance → detail detection. */
    fun edgePattern(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x < width / 2) 20 else 235
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    /** 100x100: fine vertical stripes (1px alternating 50/200). High-frequency detail. */
    fun fineDetail(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = if (x % 2 == 0) 50 else 200
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 14. LOW DETAIL/BLURRED ===

    /** 100x100: smooth horizontal gradient 40-215. Low detail, smooth transitions. */
    fun smoothGradient(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = 40 + (x * 175 / (width - 1).coerceAtLeast(1))
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    // === 15. FULL GRADIENT ===

    /** 256x1: full luminance gradient 0-255. Maximum dynamic range. */
    fun fullGradient(): Bitmap {
        val bitmap = Bitmap.createBitmap(256, 1, Bitmap.Config.ARGB_8888)
        for (x in 0 until 256) {
            bitmap.setPixel(x, 0, Color.rgb(x, x, x))
        }
        return bitmap
    }

    // === 16. UNIFORM BLACK ===

    /** 100x100 pure black (0,0,0). Shadow crushing at luma=0. */
    fun uniformBlack(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        return bitmap
    }

    // === 17. UNIFORM WHITE ===

    /** 100x100 pure white (255,255,255). Highlight clipping at luma=255. */
    fun uniformWhite(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return bitmap
    }

    // === 18. SINGLE PIXEL ===

    /** 1x1 neutral gray. Edge case: should not crash analyzer. */
    fun singlePixel(): Bitmap {
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bitmap.setPixel(0, 0, Color.rgb(128, 128, 128))
        return bitmap
    }

    // === 19. WARM SCENE ===

    /** 100x100: warm tones (200,120,80). avgR > avgB by > 15. */
    fun warmScene(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 120, 80))
        return bitmap
    }

    // === 20. COOL SCENE ===

    /** 100x100: cool tones (80,120,200). avgB > avgR by > 15. */
    fun coolScene(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(80, 120, 200))
        return bitmap
    }

    // === 21. CROPPED SCENE ===

    /** 200x100: gradient, for testing crop + pipeline interaction. */
    fun croppedGradient(): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        for (x in 0 until 200) {
            val v = (x * 255) / 199
            for (y in 0 until 100) {
                bitmap.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        return bitmap
    }

    /**
     * Returns all named fixtures for parameterized testing.
     * Each entry is (name, bitmapFactory).
     */
    fun allFixtures(): List<Pair<String, () -> Bitmap>> = listOf(
        "balancedGray" to { balancedGray() },
        "darkGray" to { darkGray() },
        "crushedShadows" to { crushedShadows() },
        "brightGray" to { brightGray() },
        "clippedHighlights" to { clippedHighlights() },
        "highKey" to { highKey() },
        "lowKey" to { lowKey() },
        "lowKeyWithCrushing" to { lowKeyWithCrushing() },
        "highContrastEdge" to { highContrastEdge() },
        "checkerboard" to { checkerboard() },
        "lowContrastGradient" to { lowContrastGradient() },
        "skyScene" to { skyScene() },
        "vegetationScene" to { vegetationScene() },
        "skinToneScene" to { skinToneScene() },
        "warmCoolSplit" to { warmCoolSplit() },
        "noisyPattern" to { noisyPattern() },
        "edgePattern" to { edgePattern() },
        "fineDetail" to { fineDetail() },
        "smoothGradient" to { smoothGradient() },
        "fullGradient" to { fullGradient() },
        "uniformBlack" to { uniformBlack() },
        "uniformWhite" to { uniformWhite() },
        "singlePixel" to { singlePixel() },
        "warmScene" to { warmScene() },
        "coolScene" to { coolScene() },
        "croppedGradient" to { croppedGradient() },
    )
}
