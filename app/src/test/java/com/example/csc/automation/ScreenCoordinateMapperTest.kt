package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCoordinateMapperTest {
    @Test
    fun mapsRealScreenRegionIntoInsetOverlayCoordinates() {
        val bounds = mapRecognitionRegionToOverlay(
            region = RecognitionRegion(0.68f, 0.226f, 0.795f, 0.35f),
            screenWidth = 720,
            screenHeight = 1_560,
            overlayLeft = 0,
            overlayTop = 54,
        )

        assertEquals(489.6f, bounds.left, 0.001f)
        assertEquals(298.56f, bounds.top, 0.001f)
        assertEquals(572.4f, bounds.right, 0.001f)
        assertEquals(492f, bounds.bottom, 0.001f)
    }

    @Test
    fun keepsCoordinatesInPlaceWhenOverlayStartsAtScreenOrigin() {
        val bounds = mapRecognitionRegionToOverlay(
            region = RecognitionRegion(0.1f, 0.2f, 0.4f, 0.5f),
            screenWidth = 1_000,
            screenHeight = 2_000,
            overlayLeft = 0,
            overlayTop = 0,
        )

        assertEquals(100f, bounds.left, 0.001f)
        assertEquals(400f, bounds.top, 0.001f)
        assertEquals(400f, bounds.right, 0.001f)
        assertEquals(1_000f, bounds.bottom, 0.001f)
    }
}
