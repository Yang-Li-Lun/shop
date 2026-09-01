package com.example.csc.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Detects a bright white left-facing chevron without loading a reference image. */
object BackArrowDetector {
    private data class Buffers(
        var pixels: IntArray = IntArray(0),
        var whiteness: IntArray = IntArray(0),
    ) {
        fun ensure(size: Int) {
            if (pixels.size < size) pixels = IntArray(size)
            if (whiteness.size < size) whiteness = IntArray(size)
        }
    }

    private data class Candidate(
        val vertexX: Int,
        val vertexY: Int,
        val horizontalSpan: Int,
        val verticalSpan: Int,
        val score: Float,
        val coverage: Float,
    )

    private data class ArmScore(
        val score: Float,
        val coverage: Float,
    )

    private data class CandidateSeed(
        val vertexX: Int,
        val vertexY: Int,
        val horizontalSpan: Int,
        val verticalSpan: Int,
        val score: Float,
    )

    private val threadBuffers = ThreadLocal.withInitial { Buffers() }

    fun find(bitmap: Bitmap): VisualMatch? {
        if (bitmap.width < MIN_SIZE || bitmap.height < MIN_SIZE) return null
        val size = bitmap.width * bitmap.height
        val buffers = threadBuffers.get()!!.also { it.ensure(size) }
        bitmap.getPixels(buffers.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return find(buffers.pixels, bitmap.width, bitmap.height, buffers.whiteness)
    }

    internal fun find(
        colors: IntArray,
        width: Int,
        height: Int,
    ): VisualMatch? = find(colors, width, height, IntArray(width * height))

    private fun find(
        colors: IntArray,
        width: Int,
        height: Int,
        whiteness: IntArray,
    ): VisualMatch? {
        require(colors.size >= width * height)
        if (width < MIN_SIZE || height < MIN_SIZE) return null
        val size = width * height
        for (index in 0 until size) {
            val color = colors[index]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            val darkest = min(red, min(green, blue))
            val lightest = max(red, max(green, blue))
            // The supplied icon is white on a saturated orange scene. Minimum-channel
            // brightness selects neutral white strokes while rejecting yellow highlights.
            whiteness[index] = (darkest - (lightest - darkest) / 3).coerceIn(0, 255)
        }

        val shortSide = min(width, height)
        val minSpan = max(7, (shortSide * 0.07f).roundToInt())
        val maxSpan = min(58, (shortSide * 0.42f).roundToInt())
        if (maxSpan < minSpan) return null
        val spanStep = max(2, (maxSpan - minSpan) / 12)
        val seeds = ArrayList<CandidateSeed>(MAX_FULL_EVALUATIONS)

        // A true vertex contains at least one nearly white pixel. Restricting candidate
        // vertices to those pixels keeps this geometric search cheap in cropped regions.
        for (vertexY in 2 until height - 2 step COARSE_POSITION_STEP) {
            for (vertexX in 2 until width - minSpan - 2 step COARSE_POSITION_STEP) {
                if (!isPlausibleVertex(whiteness, width, height, vertexX, vertexY, minSpan)) continue
                var horizontalSpan = minSpan
                while (horizontalSpan <= maxSpan && vertexX + horizontalSpan < width - 2) {
                    for (slope in SLOPES) {
                        val verticalSpan = (horizontalSpan * slope).roundToInt()
                        if (vertexY - verticalSpan < 2 || vertexY + verticalSpan >= height - 2) continue
                        val seedScore = seedScore(
                            whiteness, width, height, vertexX, vertexY, horizontalSpan, verticalSpan,
                        )
                        retainSeed(
                            seeds,
                            CandidateSeed(vertexX, vertexY, horizontalSpan, verticalSpan, seedScore),
                        )
                    }
                    horizontalSpan += spanStep
                }
            }
        }

        var coarse: Candidate? = null
        seeds.forEach { seed ->
            val candidate = scoreAt(
                whiteness, width, height,
                seed.vertexX, seed.vertexY, seed.horizontalSpan, seed.verticalSpan,
            )
            if (coarse == null || candidate.score > coarse!!.score) coarse = candidate
        }
        val initial = coarse ?: return null
        var refined = initial
        for (vertexY in coarse.vertexY - 3..coarse.vertexY + 3) {
            for (vertexX in coarse.vertexX - 3..coarse.vertexX + 3) {
                for (horizontalSpan in max(minSpan, coarse.horizontalSpan - spanStep)..min(maxSpan, coarse.horizontalSpan + spanStep)) {
                    for (slope in SLOPES) {
                        val verticalSpan = (horizontalSpan * slope).roundToInt()
                        if (
                            vertexX < 2 || vertexX + horizontalSpan >= width - 2 ||
                            vertexY - verticalSpan < 2 || vertexY + verticalSpan >= height - 2
                        ) continue
                        val candidate = scoreAt(
                            whiteness,
                            width,
                            height,
                            vertexX,
                            vertexY,
                            horizontalSpan,
                            verticalSpan,
                        )
                        if (candidate.score > refined.score) refined = candidate
                    }
                }
            }
        }

        val centerX = refined.vertexX + refined.horizontalSpan * 0.5f
        val widthPixels = refined.horizontalSpan.toFloat()
        val heightPixels = refined.verticalSpan * 2f
        return VisualMatch(
            centerX = centerX,
            centerY = refined.vertexY.toFloat(),
            score = refined.score.coerceIn(0f, 0.994f),
            width = widthPixels,
            height = heightPixels,
            clickCenterX = centerX,
            clickCenterY = refined.vertexY.toFloat(),
            foregroundCoverage = refined.coverage,
        )
    }

    private fun scoreAt(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        horizontalSpan: Int,
        verticalSpan: Int,
    ): Candidate {
        val upper = sampleArm(
            whiteness, width, height, vertexX, vertexY, horizontalSpan, -verticalSpan,
        )
        val lower = sampleArm(
            whiteness, width, height, vertexX, vertexY, horizontalSpan, verticalSpan,
        )
        val weakestArm = min(upper.score, lower.score)
        val symmetry = 1f - abs(upper.coverage - lower.coverage)
        val negativeSpace = negativeSpaceScore(
            whiteness, width, height, vertexX, vertexY, horizontalSpan, verticalSpan,
        )
        val isolation = isolationScore(
            whiteness, width, height, vertexX, vertexY, horizontalSpan, verticalSpan,
        )
        val vertex = ((whiteAt(whiteness, width, height, vertexX, vertexY, 1) - 150f) / 95f)
            .coerceIn(0f, 1f)
        val shapeScore = (
            upper.score * 0.25f + lower.score * 0.25f + weakestArm * 0.25f +
                symmetry * 0.08f + negativeSpace * 0.12f + vertex * 0.05f
            ) * (0.55f + weakestArm * 0.45f)
        // Dense grids can touch every positive sample through the small stroke-width
        // tolerance. A real chevron has very few white pixels away from its two arms.
        val score = shapeScore * (0.28f + isolation * 0.72f)
        return Candidate(
            vertexX,
            vertexY,
            horizontalSpan,
            verticalSpan,
            score,
            (upper.coverage + lower.coverage) * 0.5f,
        )
    }

    private fun seedScore(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        horizontalSpan: Int,
        verticalSpan: Int,
    ): Float {
        var armSignal = 0f
        for (ratio in floatArrayOf(0.34f, 0.68f, 1f)) {
            val x = vertexX + (horizontalSpan * ratio).roundToInt()
            val y = (verticalSpan * ratio).roundToInt()
            armSignal += whiteAt(whiteness, width, height, x, vertexY - y, 1) / 255f
            armSignal += whiteAt(whiteness, width, height, x, vertexY + y, 1) / 255f
        }
        val leftOffset = max(3, horizontalSpan / 3)
        val leftBackground = 1f - whiteAt(
            whiteness, width, height, vertexX - leftOffset, vertexY, 1,
        ) / 255f
        return (armSignal / 6f) * (0.35f + leftBackground * 0.65f)
    }

    private fun retainSeed(seeds: MutableList<CandidateSeed>, candidate: CandidateSeed) {
        if (seeds.size < MAX_FULL_EVALUATIONS) {
            seeds += candidate
            return
        }
        var weakestIndex = 0
        for (index in 1 until seeds.size) {
            if (seeds[index].score < seeds[weakestIndex].score) weakestIndex = index
        }
        if (candidate.score > seeds[weakestIndex].score) seeds[weakestIndex] = candidate
    }

    /** Reject white page backgrounds before the expensive geometric scoring pass. */
    private fun isPlausibleVertex(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        minSpan: Int,
    ): Boolean {
        if (whiteAt(whiteness, width, height, vertexX, vertexY, 3) < 178) return false
        // A left-facing chevron's tip has background immediately to its left. Without this,
        // a white Shopee header promotes nearly every sampled pixel into the full scorer.
        val leftOffset = max(3, minSpan / 3)
        return whiteAt(whiteness, width, height, vertexX - leftOffset, vertexY, 1) < 220
    }

    private fun sampleArm(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        horizontalSpan: Int,
        verticalDelta: Int,
    ): ArmScore {
        val samples = max(9, horizontalSpan / 2)
        var coverage = 0
        var total = 0f
        var firstSegment = 0f
        var secondSegment = 0f
        var thirdSegment = 0f
        var firstCount = 0
        var secondCount = 0
        var thirdCount = 0
        val segment = max(1, samples / 3)
        for (sample in 0 until samples) {
            val ratio = sample.toFloat() / (samples - 1)
            val x = vertexX + (horizontalSpan * ratio).roundToInt()
            val y = vertexY + (verticalDelta * ratio).roundToInt()
            val center = whiteAt(whiteness, width, height, x, y, 2)
            val sideOffset = max(3, horizontalSpan / 9)
            val normalX = (-verticalDelta.toFloat() / max(1, horizontalSpan) * sideOffset).roundToInt()
            val normalY = sideOffset
            val sideA = whiteAt(whiteness, width, height, x + normalX, y + normalY, 1)
            val sideB = whiteAt(whiteness, width, height, x - normalX, y - normalY, 1)
            val absoluteWhite = ((center - 145f) / 100f).coerceIn(0f, 1f)
            val contrast = ((center - (sideA + sideB) * 0.5f) / 105f).coerceIn(0f, 1f)
            val value = absoluteWhite * 0.72f + contrast * 0.28f
            total += value
            if (value >= 0.62f) coverage++
            when {
                sample < segment -> {
                    firstSegment += value
                    firstCount++
                }
                sample < segment * 2 -> {
                    secondSegment += value
                    secondCount++
                }
                else -> {
                    thirdSegment += value
                    thirdCount++
                }
            }
        }
        val weakestSegment = minOf(
            firstSegment / firstCount,
            secondSegment / secondCount,
            thirdSegment / thirdCount,
        )
        val mean = total / samples
        val coverageRatio = coverage.toFloat() / samples
        return ArmScore(
            score = mean * 0.36f + weakestSegment * 0.36f + coverageRatio * 0.28f,
            coverage = coverageRatio,
        )
    }

    private fun negativeSpaceScore(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        horizontalSpan: Int,
        verticalSpan: Int,
    ): Float {
        var total = 0f
        var count = 0
        for (step in 2..7) {
            val ratio = step / 9f
            val x = vertexX + (horizontalSpan * ratio).roundToInt()
            val halfGap = max(2, (verticalSpan * ratio * 0.32f).roundToInt())
            for (y in intArrayOf(vertexY, vertexY - halfGap, vertexY + halfGap)) {
                val white = whiteAt(whiteness, width, height, x, y, 1)
                total += (1f - ((white - 135f) / 105f).coerceIn(0f, 1f))
                count++
            }
        }
        val quietLeft = whiteAt(
            whiteness,
            width,
            height,
            vertexX - max(3, horizontalSpan / 5),
            vertexY,
            1,
        )
        total += 1f - ((quietLeft - 135f) / 105f).coerceIn(0f, 1f)
        count++
        return total / count
    }

    private fun isolationScore(
        whiteness: IntArray,
        width: Int,
        height: Int,
        vertexX: Int,
        vertexY: Int,
        horizontalSpan: Int,
        verticalSpan: Int,
    ): Float {
        val margin = max(3, horizontalSpan / 8)
        val strokeRadius = max(2f, horizontalSpan * 0.09f)
        var brightPixels = 0
        var offShapePixels = 0
        for (y in max(0, vertexY - verticalSpan - margin)..min(height - 1, vertexY + verticalSpan + margin)) {
            for (x in max(0, vertexX - margin)..min(width - 1, vertexX + horizontalSpan + margin)) {
                // Only nearly neutral-white pixels count as isolation violations.
                // Camera bloom around the orange background can be bright, but is
                // softer and more chromatic than either the arrow or a white grid.
                if (whiteness[y * width + x] < 232) continue
                brightPixels++
                val dx = x - vertexX
                val expectedOffset = verticalSpan * dx.toFloat() / max(1, horizontalSpan)
                val onUpper = dx in -2..horizontalSpan + 2 && abs(y - (vertexY - expectedOffset)) <= strokeRadius
                val onLower = dx in -2..horizontalSpan + 2 && abs(y - (vertexY + expectedOffset)) <= strokeRadius
                if (!onUpper && !onLower) offShapePixels++
            }
        }
        if (brightPixels == 0) return 0f
        return (1f - offShapePixels.toFloat() / brightPixels).coerceIn(0f, 1f)
    }

    private fun whiteAt(
        whiteness: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        radius: Int,
    ): Int {
        var best = 0
        for (y in max(0, centerY - radius)..min(height - 1, centerY + radius)) {
            for (x in max(0, centerX - radius)..min(width - 1, centerX + radius)) {
                best = max(best, whiteness[y * width + x])
            }
        }
        return best
    }

    private const val MIN_SIZE = 24
    private const val COARSE_POSITION_STEP = 4
    private const val MAX_FULL_EVALUATIONS = 512
    private val SLOPES = floatArrayOf(0.78f, 0.90f, 1.00f, 1.12f, 1.25f)
}
