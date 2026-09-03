package com.example.csc.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberRegionFingerprintTest {
    @Test
    fun changesOutsideRoiDoNotChangeSignature() {
        val outsideColor = 0xff000000.toInt()
        val insideColor = 0xffffffff.toInt()
        fun signature(outsideChanged: Boolean): NumberRegionSignature = numberRegionSignature(
            width = 100,
            height = 100,
            left = 0.2f,
            top = 0.2f,
            right = 0.8f,
            bottom = 0.8f,
            pixelAt = { x, y ->
                if (outsideChanged && x < 15 && y < 15) insideColor else outsideColor
            },
        )

        assertTrue(signature(false).distance(signature(true)).isApproximatelySame)
    }

    @Test
    fun aSmallGlyphChangeInsideRoiChangesSignature() {
        val background = 0xff000000.toInt()
        val glyph = 0xffffffff.toInt()
        fun signature(withGlyph: Boolean): NumberRegionSignature = numberRegionSignature(
            width = 100,
            height = 100,
            left = 0.2f,
            top = 0.2f,
            right = 0.8f,
            bottom = 0.8f,
            pixelAt = { x, y ->
                if (withGlyph && x in 48..52 && y in 48..52) glyph else background
            },
        )

        assertFalse(signature(false).distance(signature(true)).isApproximatelySame)
    }

    @Test
    fun smallBackgroundChangeIsSimilarButGlyphChangeIsDetected() {
        fun signature(glyph: Boolean, background: Int): NumberRegionSignature = numberRegionSignature(
            width = 120,
            height = 80,
            left = 0.1f,
            top = 0.1f,
            right = 0.9f,
            bottom = 0.9f,
            pixelAt = { x, y ->
                if (glyph && x in 53..59 && y in 34..43) 0xffffffff.toInt() else background
            },
        )
        val stable = signature(false, 0xff101010.toInt())
        val animated = signature(false, 0xff181818.toInt())
        val changed = signature(true, 0xff101010.toInt())
        assertTrue(stable.distance(animated).isApproximatelySame)
        assertFalse(stable.distance(changed).isApproximatelySame)
    }
}
