package com.example.csc.automation

import kotlin.math.abs

data class NumberSignatureDistance(
    val luminanceMae: Float,
    val changedCellRatio: Float,
    val edgeHammingRatio: Float,
    val peakLuminanceDelta: Int,
) {
    val isApproximatelySame: Boolean
        get() = luminanceMae <= 0.08f && changedCellRatio <= 0.25f &&
            edgeHammingRatio <= 0.20f && peakLuminanceDelta <= 3
}

/** A local, comparable signature. It retains enough structure to tolerate animation noise. */
class NumberRegionSignature internal constructor(
    internal val luminance: ByteArray,
    internal val edgeBits: LongArray,
) {
    fun distance(other: NumberRegionSignature): NumberSignatureDistance {
        if (luminance.size != other.luminance.size) return NumberSignatureDistance(1f, 1f, 1f, 15)
        var absoluteDifference = 0
        var changed = 0
        for (index in luminance.indices) {
            val difference = abs((luminance[index].toInt() and 0xff) - (other.luminance[index].toInt() and 0xff))
            absoluteDifference += difference
            if (difference >= 2) changed++
        }
        var differingBits = 0
        var totalBits = 0
        for (index in edgeBits.indices) {
            differingBits += java.lang.Long.bitCount(edgeBits[index] xor other.edgeBits.getOrElse(index) { 0L })
            totalBits += 64
        }
        return NumberSignatureDistance(
            luminanceMae = absoluteDifference.toFloat() / (luminance.size.coerceAtLeast(1) * 15f),
            changedCellRatio = changed.toFloat() / luminance.size.coerceAtLeast(1),
            edgeHammingRatio = differingBits.toFloat() / totalBits.coerceAtLeast(1),
            peakLuminanceDelta = abs(
                (luminance.maxOrNull()?.toInt() ?: 0) - (other.luminance.maxOrNull()?.toInt() ?: 0),
            ),
        )
    }

    fun isApproximatelySame(other: NumberRegionSignature): Boolean = distance(other).isApproximatelySame
}

internal fun numberRegionSignature(
    width: Int,
    height: Int,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    pixelAt: (x: Int, y: Int) -> Int,
    columns: Int = 16,
    rows: Int = 16,
): NumberRegionSignature {
    if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) {
        return NumberRegionSignature(ByteArray(0), LongArray(0))
    }
    val safeLeft = minOf(left, right).coerceIn(0f, 1f)
    val safeTop = minOf(top, bottom).coerceIn(0f, 1f)
    val safeRight = maxOf(left, right).coerceIn(0f, 1f)
    val safeBottom = maxOf(top, bottom).coerceIn(0f, 1f)
    val luminance = ByteArray(columns * rows)
    fun sample(x: Int, y: Int): Int {
        val color = pixelAt(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
        return (((color ushr 16 and 0xff) * 77 +
            ((color ushr 8) and 0xff) * 150 +
            (color and 0xff) * 29) shr 8) / 16
    }
    for (row in 0 until rows) {
        for (column in 0 until columns) {
            val cellLeft = ((safeLeft + (safeRight - safeLeft) * column / columns) * width).toInt()
            val cellTop = ((safeTop + (safeBottom - safeTop) * row / rows) * height).toInt()
            val cellRight = ((safeLeft + (safeRight - safeLeft) * (column + 1) / columns) * width).toInt()
            val cellBottom = ((safeTop + (safeBottom - safeTop) * (row + 1) / rows) * height).toInt()
            var total = 0
            var count = 0
            val yStep = maxOf(1, (cellBottom - cellTop) / 3)
            val xStep = maxOf(1, (cellRight - cellLeft) / 3)
            for (y in cellTop until maxOf(cellTop + 1, cellBottom) step yStep) {
                for (x in cellLeft until maxOf(cellLeft + 1, cellRight) step xStep) {
                    total += sample(x, y)
                    count++
                }
            }
            luminance[row * columns + column] = (total / count.coerceAtLeast(1)).coerceIn(0, 15).toByte()
        }
    }
    val edgeBits = LongArray((columns * rows + 63) / 64)
    for (index in luminance.indices) {
        val rightNeighbor = if (index % columns == columns - 1) luminance[index] else luminance[index + 1]
        val downNeighbor = if (index / columns == rows - 1) luminance[index] else luminance[index + columns]
        if (abs(luminance[index] - rightNeighbor) >= 2 || abs(luminance[index] - downNeighbor) >= 2) {
            edgeBits[index / 64] = edgeBits[index / 64] or (1L shl (index % 64))
        }
    }
    return NumberRegionSignature(luminance, edgeBits)
}
