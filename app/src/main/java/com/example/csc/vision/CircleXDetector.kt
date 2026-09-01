package com.example.csc.vision

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shape detector for a bright circular close icon containing two crossing diagonals. */
object CircleXDetector {
    private data class Buffers(
        var pixels: IntArray = IntArray(0),
        var gray: IntArray = IntArray(0),
        var edges: IntArray = IntArray(0),
    ) {
        fun ensure(size: Int) {
            if (pixels.size < size) pixels = IntArray(size)
            if (gray.size < size) gray = IntArray(size)
            if (edges.size < size) edges = IntArray(size)
        }
    }

    private val threadBuffers = ThreadLocal.withInitial { Buffers() }
    private const val MAX_ANALYSIS_LONG_SIDE = 480

    fun find(
        bitmap: Bitmap,
        minDiameterRatio: Float = 0.16f,
        maxDiameterRatio: Float = 0.72f,
    ): VisualMatch? {
        val downScale = min(
            1f,
            MAX_ANALYSIS_LONG_SIDE.toFloat() / max(bitmap.width, bitmap.height),
        )
        if (downScale >= 1f) return findAtNativeResolution(bitmap, minDiameterRatio, maxDiameterRatio)
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            max(24, (bitmap.width * downScale).roundToInt()),
            max(24, (bitmap.height * downScale).roundToInt()),
            true,
        )
        return try {
            findAtNativeResolution(scaled, minDiameterRatio, maxDiameterRatio)?.let { match ->
                match.copy(
                    centerX = match.centerX / downScale,
                    centerY = match.centerY / downScale,
                    width = match.width / downScale,
                    height = match.height / downScale,
                    clickCenterX = match.clickCenterX / downScale,
                    clickCenterY = match.clickCenterY / downScale,
                )
            }
        } finally { scaled.recycle() }
    }

    private fun findAtNativeResolution(
        bitmap: Bitmap,
        minDiameterRatio: Float,
        maxDiameterRatio: Float,
    ): VisualMatch? {
        if (bitmap.width < 24 || bitmap.height < 24) return null
        val size = bitmap.width * bitmap.height
        val buffers = threadBuffers.get()!!.also { it.ensure(size) }
        val gray = buffers.gray
        val pixels = buffers.pixels
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (index in 0 until size) {
            val color = pixels[index]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            gray[index] = (red * 77 + green * 150 + blue * 29) shr 8
        }
        return find(gray, bitmap.width, bitmap.height, minDiameterRatio, maxDiameterRatio)
    }

    internal fun find(
        gray: IntArray,
        width: Int,
        height: Int,
        minDiameterRatio: Float = 0.16f,
        maxDiameterRatio: Float = 0.72f,
    ): VisualMatch? {
        require(gray.size >= width * height)
        if (width < 24 || height < 24) return null
        val edges = edges(gray, width, height)
        val shortSide = min(width, height)
        val minRadius = max(8, (shortSide * minDiameterRatio.coerceIn(0.10f, 0.80f) / 2f).roundToInt())
        val maxRadius = min(
            (shortSide * maxDiameterRatio.coerceIn(minDiameterRatio + 0.05f, 0.90f) / 2f).roundToInt(),
            72,
        )
        if (maxRadius < minRadius) return null
        var best: ShapeCandidate? = null
        // Coarse samples only seed the bounded pixel-level refinement below. A denser grid
        // costs heavily on animated app pages without improving the final click coordinates.
        val centerStep = max(4, minRadius / 2)
        val radiusStep = max(3, (maxRadius - minRadius) / 6)
        var radius = minRadius
        while (radius <= maxRadius) {
            val margin = radius + 3
            var centerY = margin
            while (centerY < height - margin) {
                var centerX = margin
                while (centerX < width - margin) {
                    val candidate = scoreAt(gray, edges, width, height, centerX, centerY, radius)
                    if (best == null || candidate.score > best.score) best = candidate
                    centerX += centerStep
                }
                centerY += centerStep
            }
            radius += radiusStep
        }
        val coarse = best ?: return null
        var refined = coarse
        for (r in max(minRadius, coarse.radius - radiusStep)..min(maxRadius, coarse.radius + radiusStep)) {
            for (y in coarse.centerY - centerStep..coarse.centerY + centerStep) {
                for (x in coarse.centerX - centerStep..coarse.centerX + centerStep) {
                    if (x - r < 1 || y - r < 1 || x + r >= width - 1 || y + r >= height - 1) continue
                    val candidate = scoreAt(gray, edges, width, height, x, y, r)
                    if (candidate.score > refined.score) refined = candidate
                }
            }
        }
        val diameter = refined.radius * 2f
        return VisualMatch(
            centerX = refined.centerX.toFloat(),
            centerY = refined.centerY.toFloat(),
            // Reserve 100% for an impossible perfect mathematical sample. Real
            // camera/screenshot input remains at 99% or below, making a stuck
            // score immediately visible instead of looking like a valid hit.
            score = refined.score.coerceIn(0f, 0.994f),
            width = diameter,
            height = diameter,
            clickCenterX = refined.centerX.toFloat(),
            clickCenterY = refined.centerY.toFloat(),
            foregroundCoverage = refined.coverage,
        )
    }

    private fun scoreAt(
        gray: IntArray,
        edges: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): ShapeCandidate {
        val backgroundSamples = ArrayList<Int>(24)
        for (step in 0 until 8) {
            val angle = step * PI / 4.0
            for (ratio in floatArrayOf(0.45f, 1.18f)) {
                val x = centerX + (cos(angle) * radius * ratio).roundToInt()
                val y = centerY + (sin(angle) * radius * ratio).roundToInt()
                if (x in 0 until width && y in 0 until height) backgroundSamples += gray[y * width + x]
            }
        }
        if (backgroundSamples.isEmpty()) return ShapeCandidate(centerX, centerY, radius, 0f, 0f)
        backgroundSamples.sort()
        val background = backgroundSamples[backgroundSamples.size / 2]

        val ring = sampleRing(gray, edges, width, height, centerX, centerY, radius, background)
        val diagonalRadius = radius * 0.58f
        val diagonalA = sampleLine(gray, edges, width, height, centerX, centerY, diagonalRadius, 1f, 1f, background)
        val diagonalB = sampleLine(gray, edges, width, height, centerX, centerY, diagonalRadius, 1f, -1f, background)
        val weakestRingQuadrant = ring.quadrants.minOrNull() ?: 0f
        val symmetry = 1f - kotlin.math.abs(diagonalA.coverage - diagonalB.coverage)
        val centerSignal = signalAt(gray, edges, width, centerX, centerY, background)
        val centerScore = (centerSignal / 90f).coerceIn(0f, 1f)
        val cleanliness = sampleNegativeSpace(gray, width, height, centerX, centerY, radius, background)
        val weightedScore = ring.coverage * 0.32f + weakestRingQuadrant * 0.18f +
            diagonalA.coverage * 0.17f + diagonalB.coverage * 0.17f +
            symmetry * 0.08f + centerScore * 0.08f
        // A high average is not enough: every ring quadrant and both halves of
        // both diagonals must be present. This suppresses textured backgrounds,
        // partial arcs and isolated diagonal lines.
        val structuralFloor = minOf(
            weakestRingQuadrant,
            diagonalA.weakestHalf,
            diagonalB.weakestHalf,
            centerScore,
        )
        val shapeScore = weightedScore * 0.52f + structuralFloor * 0.36f + cleanliness * 0.12f
        // Dense text, grids and textured panels can accidentally cross all positive
        // sample points. A real close icon also has dark/quiet wedges between its X
        // and ring, so missing negative space strongly suppresses those false hits.
        val score = shapeScore * (0.25f + cleanliness * 0.75f)
        val coverage = (ring.coverage + diagonalA.coverage + diagonalB.coverage) / 3f
        return ShapeCandidate(centerX, centerY, radius, score, coverage)
    }

    private fun sampleRing(
        gray: IntArray,
        edges: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
        background: Int,
    ): RingScore {
        val quadrants = FloatArray(4)
        val counts = IntArray(4)
        var total = 0f
        val samples = 48
        for (step in 0 until samples) {
            val angle = step * 2.0 * PI / samples
            val x = centerX + (cos(angle) * radius).roundToInt()
            val y = centerY + (sin(angle) * radius).roundToInt()
            val radialOffset = max(2, (radius * 0.13f).roundToInt())
            val offsetX = (cos(angle) * radialOffset).roundToInt()
            val offsetY = (sin(angle) * radialOffset).roundToInt()
            val raw = (orientedSignalAt(
                gray, edges, width, x, y,
                cos(angle).roundToInt(), sin(angle).roundToInt(), background,
            ) / 90f).coerceIn(0f, 1f)
            val contrast = localLineContrast(
                gray, width, height, x, y, offsetX, offsetY,
            )
            val radialEdge = radialEdgeScore(
                gray, width, height, x, y, cos(angle).toFloat(), sin(angle).toFloat(),
            )
            val hit = raw * 0.18f + contrast * 0.34f + radialEdge * 0.48f
            val quadrant = step * 4 / samples
            quadrants[quadrant] += hit
            counts[quadrant]++
            total += hit
        }
        for (index in quadrants.indices) quadrants[index] /= max(1, counts[index])
        return RingScore(total / samples, quadrants)
    }

    private fun sampleLine(
        gray: IntArray,
        edges: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        halfLength: Float,
        directionX: Float,
        directionY: Float,
        background: Int,
    ): LineScore {
        val norm = sqrt(directionX * directionX + directionY * directionY)
        val normalX = -directionY / norm
        val normalY = directionX / norm
        val sideOffset = max(2, (halfLength * 0.17f).roundToInt())
        var total = 0f
        var firstHalf = 0f
        var secondHalf = 0f
        var firstCount = 0
        var secondCount = 0
        var count = 0
        val steps = 17
        for (step in 0 until steps) {
            val offset = -halfLength + 2f * halfLength * step / (steps - 1)
            val x = (centerX + offset * directionX / norm).roundToInt()
            val y = (centerY + offset * directionY / norm).roundToInt()
            val raw = (orientedSignalAt(
                gray, edges, width, x, y,
                normalX.roundToInt(), normalY.roundToInt(), background,
            ) / 90f).coerceIn(0f, 1f)
            val contrast = localLineContrast(
                gray,
                width,
                height,
                x,
                y,
                (normalX * sideOffset).roundToInt(),
                (normalY * sideOffset).roundToInt(),
            )
            // Near the crossing, the other diagonal legitimately enters the side
            // samples. Use raw center evidence there; elsewhere require isolation.
            val hit = if (step in (steps / 2 - 1)..(steps / 2 + 1)) raw else raw * 0.28f + contrast * 0.72f
            total += hit
            if (step < steps / 2) {
                firstHalf += hit
                firstCount++
            } else if (step > steps / 2) {
                secondHalf += hit
                secondCount++
            }
            count++
        }
        return LineScore(
            coverage = if (count == 0) 0f else total / count,
            weakestHalf = minOf(
                if (firstCount == 0) 0f else firstHalf / firstCount,
                if (secondCount == 0) 0f else secondHalf / secondCount,
            ),
        )
    }

    private fun signalAt(
        gray: IntArray,
        edges: IntArray,
        width: Int,
        x: Int,
        y: Int,
        background: Int,
    ): Float {
        var strongest = 0f
        for (dy in -1..1) {
            for (dx in -1..1) {
                val index = (y + dy) * width + x + dx
                val brightness = (gray[index] - background).coerceAtLeast(0).toFloat()
                // Edge energy is supporting evidence only. Previously it could
                // independently saturate the score on ordinary textured screens.
                val edgeSupport = min(edges[index].toFloat(), brightness * 1.25f)
                strongest = max(strongest, brightness * 0.82f + edgeSupport * 0.18f)
            }
        }
        return strongest
    }

    private fun orientedSignalAt(
        gray: IntArray,
        edges: IntArray,
        width: Int,
        x: Int,
        y: Int,
        thicknessX: Int,
        thicknessY: Int,
        background: Int,
    ): Float {
        var strongest = 0f
        for (step in -1..1) {
            val index = (y + thicknessY * step) * width + x + thicknessX * step
            val brightness = (gray[index] - background).coerceAtLeast(0).toFloat()
            val edgeSupport = min(edges[index].toFloat(), brightness * 1.25f)
            strongest = max(strongest, brightness * 0.82f + edgeSupport * 0.18f)
        }
        return strongest
    }

    private fun localLineContrast(
        gray: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        offsetX: Int,
        offsetY: Int,
    ): Float {
        if (x !in 1 until width - 1 || y !in 1 until height - 1) return 0f
        val ax = x + offsetX
        val ay = y + offsetY
        val bx = x - offsetX
        val by = y - offsetY
        if (ax !in 0 until width || bx !in 0 until width || ay !in 0 until height || by !in 0 until height) return 0f
        val unitX = offsetX.coerceIn(-1, 1)
        val unitY = offsetY.coerceIn(-1, 1)
        var foreground = gray[y * width + x]
        for (step in -1..1) {
            foreground = max(foreground, gray[(y + unitY * step) * width + x + unitX * step])
        }
        val sides = (gray[ay * width + ax] + gray[by * width + bx]) / 2f
        return ((foreground - sides) / 75f).coerceIn(0f, 1f)
    }

    private fun radialEdgeScore(
        gray: IntArray,
        width: Int,
        height: Int,
        x: Int,
        y: Int,
        radialX: Float,
        radialY: Float,
    ): Float {
        var best = 0f
        for (offset in -2..2) {
            val px = x + (radialX * offset).roundToInt()
            val py = y + (radialY * offset).roundToInt()
            if (px !in 1 until width - 1 || py !in 1 until height - 1) continue
            val gx = (gray[py * width + px + 1] - gray[py * width + px - 1]).toFloat()
            val gy = (gray[(py + 1) * width + px] - gray[(py - 1) * width + px]).toFloat()
            val magnitude = sqrt(gx * gx + gy * gy)
            if (magnitude < 1f) continue
            val alignment = kotlin.math.abs(gx * radialX + gy * radialY) / magnitude
            val strength = (magnitude / 65f).coerceIn(0f, 1f)
            best = max(best, alignment * strength)
        }
        return best
    }

    private fun sampleNegativeSpace(
        gray: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
        background: Int,
    ): Float {
        var unwanted = 0f
        var count = 0
        fun sample(angle: Double, ratio: Float) {
            val x = centerX + (cos(angle) * radius * ratio).roundToInt()
            val y = centerY + (sin(angle) * radius * ratio).roundToInt()
            if (x !in 0 until width || y !in 0 until height) {
                unwanted += 1f
            } else {
                val brightness = (gray[y * width + x] - background).coerceAtLeast(0)
                unwanted += (brightness / 90f).coerceIn(0f, 1f)
            }
            count++
        }
        // A genuine close icon is isolated outside the ring. Dense layouts and grids
        // usually fail this 32-direction annulus even if a few positive points align.
        for (step in 0 until 32) sample(step * 2.0 * PI / 32.0, 1.24f)
        // Axis sectors inside the circle must also remain empty between both X strokes.
        for (step in 0 until 4) {
            val angle = step * PI / 2.0
            for (ratio in floatArrayOf(0.34f, 0.56f, 0.76f)) sample(angle, ratio)
        }
        return (1f - unwanted / max(1, count)).coerceIn(0f, 1f)
    }

    private fun edges(gray: IntArray, width: Int, height: Int): IntArray {
        val output = threadBuffers.get()!!.also { it.ensure(width * height) }.edges
        java.util.Arrays.fill(output, 0, width * height, 0)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                output[index] = min(
                    255,
                    kotlin.math.abs(gray[index + 1] - gray[index - 1]) +
                        kotlin.math.abs(gray[index + width] - gray[index - width]),
                )
            }
        }
        return output
    }

    private data class ShapeCandidate(
        val centerX: Int,
        val centerY: Int,
        val radius: Int,
        val score: Float,
        val coverage: Float,
    )

    private data class RingScore(val coverage: Float, val quadrants: FloatArray)
    private data class LineScore(val coverage: Float, val weakestHalf: Float)
}
