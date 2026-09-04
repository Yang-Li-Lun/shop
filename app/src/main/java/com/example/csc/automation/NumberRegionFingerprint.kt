package com.example.csc.automation

/**
 * Computes a small luminance signature from coordinates relative to one ROI. The pixel provider
 * keeps this helper JVM-testable and lets the service sample a Bitmap without allocating a crop.
 */
internal fun numberRegionFingerprint(
    width: Int,
    height: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    pixelAt: (x: Int, y: Int) -> Int,
    columns: Int = 32,
    rows: Int = 32,
): Long {
    if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return 0L
    val safeLeft = minOf(left, right).coerceIn(0f, 1f)
    val safeTop = minOf(top, bottom).coerceIn(0f, 1f)
    val safeRight = maxOf(left, right).coerceIn(0f, 1f)
    val safeBottom = maxOf(top, bottom).coerceIn(0f, 1f)
    var hash = 1_125_899_906_842_597L
    for (row in 1..rows) {
        val yRatio = row.toFloat() / (rows + 1f)
        val y = ((safeTop + (safeBottom - safeTop) * yRatio) * height)
            .toInt()
            .coerceIn(0, height - 1)
        for (column in 1..columns) {
            val xRatio = column.toFloat() / (columns + 1f)
            val x = ((safeLeft + (safeRight - safeLeft) * xRatio) * width)
                .toInt()
                .coerceIn(0, width - 1)
            val color = pixelAt(x, y)
            val red = (color ushr 16) and 0xff
            val green = (color ushr 8) and 0xff
            val blue = color and 0xff
            val luminance = ((red * 77 + green * 150 + blue * 29) shr 8) / 12
            hash = (hash xor luminance.toLong()) * 1_099_511_628_211L
        }
    }
    return hash
}
