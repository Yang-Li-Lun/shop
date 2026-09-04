package com.example.csc.automation

internal data class OverlayRegionBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Maps persisted screen-relative regions into an overlay View's local coordinates. */
internal fun mapRecognitionRegionToOverlay(
    region: RecognitionRegion,
    screenWidth: Int,
    screenHeight: Int,
    overlayLeft: Int,
    overlayTop: Int,
): OverlayRegionBounds {
    val normalized = region.normalized()
    return OverlayRegionBounds(
        left = screenWidth * normalized.left - overlayLeft,
        top = screenHeight * normalized.top - overlayTop,
        right = screenWidth * normalized.right - overlayLeft,
        bottom = screenHeight * normalized.bottom - overlayTop,
    )
}
