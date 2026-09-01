package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRegionTest {
    @Test
    fun normalizesBoundsAndKeepsMinimumSize() {
        val result = RecognitionRegion(-0.2f, 0.98f, 0.01f, 1.4f).normalized()

        assertEquals(0f, result.left, 0.0001f)
        assertEquals(0.95f, result.top, 0.0001f)
        assertEquals(0.05f, result.right, 0.0001f)
        assertEquals(1f, result.bottom, 0.0001f)
    }

    @Test
    fun containsUsesRelativeScreenCoordinates() {
        val region = RecognitionRegion(0.25f, 0.2f, 0.75f, 0.8f)

        assertTrue(region.contains(500f, 500f, 1_000, 1_000))
        assertFalse(region.contains(100f, 500f, 1_000, 1_000))
        assertFalse(region.contains(500f, 900f, 1_000, 1_000))
    }

    @Test
    fun acceptsAButtonThatSubstantiallyOverlapsTheZone() {
        val region = RecognitionRegion(0.662037f, 0.238889f, 1f, 0.368519f)

        // The Shopee label is x=839..1011, y=848..917 on a 1080x2400 display.
        // Its lower portion crosses the saved zone edge (y=884), but more than
        // half remains available for a region-safe click.
        assertTrue(region.hasSafeClickArea(839f, 848f, 1011f, 917f, 1_080, 2_400))
    }

    @Test
    fun rejectsAButtonWithOnlyASmallSliverInsideTheZone() {
        val region = RecognitionRegion(0.662037f, 0.238889f, 1f, 0.368519f)

        assertFalse(region.hasSafeClickArea(839f, 875f, 1011f, 1_020f, 1_080, 2_400))
    }
}
