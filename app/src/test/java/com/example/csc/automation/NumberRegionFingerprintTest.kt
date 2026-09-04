package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NumberRegionFingerprintTest {
    @Test
    fun changesOutsideRoiDoNotChangeSignature() {
        val outsideColor = 0xff000000.toInt()
        val insideColor = 0xffffffff.toInt()
        fun signature(outsideChanged: Boolean): Long = numberRegionFingerprint(
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

        assertEquals(signature(false), signature(true))
    }

    @Test
    fun aSmallGlyphChangeInsideRoiChangesSignature() {
        val background = 0xff000000.toInt()
        val glyph = 0xffffffff.toInt()
        fun signature(withGlyph: Boolean): Long = numberRegionFingerprint(
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

        assertNotEquals(signature(false), signature(true))
    }
}
