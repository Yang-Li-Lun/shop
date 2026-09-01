package com.example.csc.vision

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.WeakHashMap

data class VisualMatch(
    val centerX: Float,
    val centerY: Float,
    val score: Float,
    val width: Float,
    val height: Float,
    val clickCenterX: Float = centerX,
    val clickCenterY: Float = centerY,
    val templateScale: Float = 1f,
    val scoreGap: Float = 0f,
    val foregroundCoverage: Float = 0f,
)

/**
 * Lightweight, local template matching intended for UI icons and buttons.
 * It compares luminance and edge structure at three nearby scales.
 */
object TemplateMatcher {
    private const val MAX_SCREEN_WIDTH = 600
    private val TEMPLATE_SCALES = floatArrayOf(
        0.70f, 0.75f, 0.80f, 0.85f, 0.90f, 0.95f, 1.00f,
        1.05f, 1.10f, 1.15f, 1.20f, 1.25f, 1.30f,
    )
    private val COARSE_SCALES = setOf(70, 90, 110, 130)
    private val templateCache = WeakHashMap<Bitmap, MutableMap<Int, List<TemplateFeatures>>>()
    private val preferredScaleCache = WeakHashMap<Bitmap, Float>()

    class PreparedScreen internal constructor(
        private val original: Bitmap,
        private val scaled: Bitmap,
        internal val width: Int,
        internal val height: Int,
        internal val downScale: Float,
        internal val gray: IntArray,
        internal val edges: IntArray,
    ) : AutoCloseable {
        fun findBest(reference: Bitmap): VisualMatch? = TemplateMatcher.findBestPrepared(this, reference)

        override fun close() {
            if (scaled !== original && !scaled.isRecycled) scaled.recycle()
        }
    }

    private data class TemplateFeatures(
        val width: Int,
        val height: Int,
        val gray: IntArray,
        val edges: IntArray,
        val foreground: ForegroundFeature?,
        val scale: Float,
        val safeCenterX: Float,
        val safeCenterY: Float,
        val foregroundCoverage: Float,
    )

    fun find(screen: Bitmap, reference: Bitmap, threshold: Float): VisualMatch? {
        return findBest(screen, reference)?.takeIf { it.score >= threshold }
    }

    fun findBest(screen: Bitmap, reference: Bitmap): VisualMatch? {
        return prepare(screen).use { it.findBest(reference) }
    }

    /**
     * Prepares a captured region once so every reference image in that region can reuse the
     * same scaled pixels, luminance data, and edge map.
     */
    fun prepare(screen: Bitmap): PreparedScreen {
        if (screen.width < 2 || screen.height < 2) {
            return PreparedScreen(
                original = screen,
                scaled = screen,
                width = screen.width,
                height = screen.height,
                downScale = 1f,
                gray = bitmapToGray(screen),
                edges = IntArray(screen.width * screen.height),
            )
        }

        val downScale = min(1f, MAX_SCREEN_WIDTH.toFloat() / screen.width)
        val screenWidth = max(2, (screen.width * downScale).roundToInt())
        val screenHeight = max(2, (screen.height * downScale).roundToInt())
        val scaledScreen = if (screenWidth == screen.width && screenHeight == screen.height) {
            screen
        } else {
            Bitmap.createScaledBitmap(screen, screenWidth, screenHeight, true)
        }

        val screenGray = bitmapToGray(scaledScreen)
        val screenEdges = edges(screenGray, screenWidth, screenHeight)
        return PreparedScreen(
            original = screen,
            scaled = scaledScreen,
            width = screenWidth,
            height = screenHeight,
            downScale = downScale,
            gray = screenGray,
            edges = screenEdges,
        )
    }

    private fun findBestPrepared(screen: PreparedScreen, reference: Bitmap): VisualMatch? {
        if (screen.width < 2 || screen.height < 2 || reference.width < 2 || reference.height < 2) {
            return null
        }

        var best: ArrayMatch? = null
        var bestTemplate: TemplateFeatures? = null
        var secondBestScore = 0f
        val templates = templatesFor(reference, screen.downScale)
        val preferred = synchronized(preferredScaleCache) { preferredScaleCache[reference] }
        val coarse = templates
            .filter { (it.scale * 100).roundToInt() in COARSE_SCALES || preferred?.let { value -> kotlin.math.abs(it.scale - value) < 0.01f } == true }
            .sortedBy { preferred?.let { value -> kotlin.math.abs(it.scale - value) } ?: kotlin.math.abs(it.scale - 1f) }

        fun evaluate(template: TemplateFeatures) {
            if (template.width >= screen.width || template.height >= screen.height) return
            val candidate = GrayTemplateMatcher.find(
                screenGray = screen.gray,
                screenEdges = screen.edges,
                screenWidth = screen.width,
                screenHeight = screen.height,
                templateGray = template.gray,
                templateEdges = template.edges,
                templateWidth = template.width,
                templateHeight = template.height,
                templateForeground = template.foreground,
            )
            val currentBest = best
            if (candidate != null && (currentBest == null || candidate.score > currentBest.score)) {
                secondBestScore = currentBest?.score ?: secondBestScore
                best = candidate
                bestTemplate = template
            } else if (candidate != null && candidate.score > secondBestScore) {
                secondBestScore = candidate.score
            }
        }

        coarse.forEach(::evaluate)
        val coarseWinner = bestTemplate?.scale ?: 1f
        templates
            .filter { template -> template !in coarse && kotlin.math.abs(template.scale - coarseWinner) <= 0.11f }
            .forEach(::evaluate)

        val match = best ?: return null
        val winningTemplate = bestTemplate ?: return null
        synchronized(preferredScaleCache) { preferredScaleCache[reference] = winningTemplate.scale }
        return VisualMatch(
            centerX = (match.x + match.width / 2f) / screen.downScale,
            centerY = (match.y + match.height / 2f) / screen.downScale,
            score = match.score,
            width = match.width / screen.downScale,
            height = match.height / screen.downScale,
            clickCenterX = (match.x + winningTemplate.safeCenterX) / screen.downScale,
            clickCenterY = (match.y + winningTemplate.safeCenterY) / screen.downScale,
            templateScale = winningTemplate.scale,
            scoreGap = (match.score - secondBestScore).coerceAtLeast(0f),
            foregroundCoverage = winningTemplate.foregroundCoverage,
        )
    }

    @Synchronized
    private fun templatesFor(reference: Bitmap, downScale: Float): List<TemplateFeatures> {
        val scaleKey = java.lang.Float.floatToIntBits(downScale)
        val variants = templateCache.getOrPut(reference) { mutableMapOf() }
        return variants.getOrPut(scaleKey) {
            TEMPLATE_SCALES.map { templateScale ->
                val width = max(2, (reference.width * downScale * templateScale).roundToInt())
                val height = max(2, (reference.height * downScale * templateScale).roundToInt())
                val templateBitmap = Bitmap.createScaledBitmap(reference, width, height, true)
                try {
                    val gray = bitmapToGray(templateBitmap)
                    val foreground = GrayTemplateMatcher.detectForeground(gray, width, height)
                    val safePoint = foregroundSafePoint(foreground, width, height)
                    TemplateFeatures(
                        width,
                        height,
                        gray,
                        edges(gray, width, height),
                        foreground,
                        templateScale,
                        safePoint.first,
                        safePoint.second,
                        foreground?.mask?.count { it }?.toFloat()?.div(width * height) ?: 0f,
                    )
                } finally {
                    if (templateBitmap !== reference) templateBitmap.recycle()
                }
            }
        }
    }

    internal fun foregroundSafePoint(
        foreground: ForegroundFeature?,
        width: Int,
        height: Int,
    ): Pair<Float, Float> {
        val mask = foreground?.mask ?: return width / 2f to height / 2f
        var sumX = 0L
        var sumY = 0L
        var count = 0
        mask.forEachIndexed { index, selected ->
            if (selected) {
                sumX += index % width
                sumY += index / width
                count++
            }
        }
        if (count == 0) return width / 2f to height / 2f
        val centerX = sumX.toFloat() / count
        val centerY = sumY.toFloat() / count
        var closestIndex = -1
        var closestDistance = Float.MAX_VALUE
        mask.forEachIndexed { index, selected ->
            if (!selected) return@forEachIndexed
            val dx = index % width - centerX
            val dy = index / width - centerY
            val distance = dx * dx + dy * dy
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }
        return if (closestIndex >= 0) {
            (closestIndex % width + 0.5f) to (closestIndex / width + 0.5f)
        } else {
            width / 2f to height / 2f
        }
    }

    private fun bitmapToGray(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val color = pixels[i]
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            pixels[i] = (red * 77 + green * 150 + blue * 29) shr 8
        }
        return pixels
    }

    private fun edges(gray: IntArray, width: Int, height: Int): IntArray {
        val result = IntArray(gray.size)
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val horizontal = gray[row + x + 1] - gray[row + x - 1]
                val vertical = gray[row + width + x] - gray[row - width + x]
                result[row + x] = min(255, kotlin.math.abs(horizontal) + kotlin.math.abs(vertical))
            }
        }
        return result
    }
}

data class ArrayMatch(
    val x: Int,
    val y: Int,
    val score: Float,
    val width: Int,
    val height: Int,
)

internal data class ForegroundFeature(
    val mask: BooleanArray,
    val backgroundGray: Int,
    val polarity: Int,
    val averageContrast: Float,
)

private data class ForegroundComponent(
    val pixels: IntArray,
    val score: Double,
    val polarity: Int,
    val centerX: Double,
    val centerY: Double,
    val touchesBorder: Boolean,
)

/** Pure-array core so the scoring behavior can be unit tested without Android bitmaps. */
object GrayTemplateMatcher {
    private const val COARSE_CANDIDATE_LIMIT = 4

    internal fun detectForeground(
        templateGray: IntArray,
        templateWidth: Int,
        templateHeight: Int,
    ): ForegroundFeature? {
        require(templateGray.size == templateWidth * templateHeight)
        val area = templateGray.size
        if (templateWidth < 8 || templateHeight < 8 || area < 64) return null

        val borderThickness = max(1, min(templateWidth, templateHeight) / 12)
        val borderValues = ArrayList<Int>()
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                if (
                    x < borderThickness || x >= templateWidth - borderThickness ||
                    y < borderThickness || y >= templateHeight - borderThickness
                ) {
                    borderValues += templateGray[y * templateWidth + x]
                }
            }
        }
        if (borderValues.isEmpty()) return null
        borderValues.sort()
        val backgroundGray = borderValues[borderValues.size / 2]
        val contrasts = IntArray(area) { index -> kotlin.math.abs(templateGray[index] - backgroundGray) }
        val sortedContrasts = contrasts.copyOf().apply { sort() }
        val adaptiveContrast = sortedContrasts[(sortedContrasts.lastIndex * 0.82f).roundToInt()]
        val contrastThreshold = adaptiveContrast.coerceIn(30, 80)
        val candidates = BooleanArray(area) { contrasts[it] >= contrastThreshold }
        val visited = BooleanArray(area)
        val queue = IntArray(area)
        val components = mutableListOf<ForegroundComponent>()
        val minimumPixels = max(8, area / 450)

        for (start in 0 until area) {
            if (!candidates[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var sumX = 0L
            var sumY = 0L
            var contrastSum = 0L
            var touchesBorder = false
            while (head < tail) {
                val index = queue[head++]
                val x = index % templateWidth
                val y = index / templateWidth
                sumX += x
                sumY += y
                contrastSum += contrasts[index]
                if (x == 0 || y == 0 || x == templateWidth - 1 || y == templateHeight - 1) {
                    touchesBorder = true
                }
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until templateWidth || ny !in 0 until templateHeight) continue
                        val neighbor = ny * templateWidth + nx
                        if (candidates[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
            if (tail < minimumPixels || tail > area * 0.45f) continue
            val centerX = sumX.toDouble() / tail
            val centerY = sumY.toDouble() / tail
            val distanceX = (centerX - templateWidth / 2.0) / templateWidth
            val distanceY = (centerY - templateHeight / 2.0) / templateHeight
            val normalizedDistance = kotlin.math.sqrt(distanceX * distanceX + distanceY * distanceY)
            val centerWeight = 1.6 - normalizedDistance.coerceIn(0.0, 0.8)
            val borderWeight = if (touchesBorder) 0.35 else 1.0
            val componentScore = contrastSum.toDouble() * centerWeight * borderWeight
            val pixels = queue.copyOf(tail)
            val componentMean = pixels.sumOf { templateGray[it].toLong() }.toDouble() / tail
            components += ForegroundComponent(
                pixels = pixels,
                score = componentScore,
                polarity = if (componentMean >= backgroundGray) 1 else -1,
                centerX = centerX,
                centerY = centerY,
                touchesBorder = touchesBorder,
            )
        }

        val primary = components.maxByOrNull { it.score } ?: return null
        val secondary = components
            .asSequence()
            .filter { it !== primary }
            .filter { !it.touchesBorder && it.polarity == primary.polarity }
            .filter { it.score >= primary.score * 0.08 }
            .filter {
                kotlin.math.abs(it.centerX - templateWidth / 2.0) <= templateWidth * 0.30 &&
                    kotlin.math.abs(it.centerY - templateHeight / 2.0) <= templateHeight * 0.30
            }
            .maxByOrNull { it.score }
        val selected = if (secondary == null) {
            primary.pixels
        } else {
            primary.pixels + secondary.pixels
        }
        val mask = BooleanArray(area)
        var foregroundSum = 0L
        selected.forEach { index ->
            mask[index] = true
            foregroundSum += templateGray[index]
        }
        val foregroundMean = foregroundSum.toFloat() / selected.size
        val polarity = primary.polarity
        val averageContrast = selected.sumOf { index -> contrasts[index].toLong() }.toFloat() / selected.size
        if (averageContrast < 24f) return null
        return ForegroundFeature(mask, backgroundGray, polarity, averageContrast)
    }

    internal fun find(
        screenGray: IntArray,
        screenEdges: IntArray,
        screenWidth: Int,
        screenHeight: Int,
        templateGray: IntArray,
        templateEdges: IntArray,
        templateWidth: Int,
        templateHeight: Int,
        templateForeground: ForegroundFeature? = null,
    ): ArrayMatch? {
        require(screenGray.size == screenWidth * screenHeight)
        require(screenEdges.size == screenGray.size)
        require(templateGray.size == templateWidth * templateHeight)
        require(templateEdges.size == templateGray.size)
        if (templateWidth > screenWidth || templateHeight > screenHeight) return null

        val sampleStep = max(1, min(templateWidth, templateHeight) / 24)
        val moveStep = max(3, min(templateWidth, templateHeight) / 12)
        val maxOffsetX = screenWidth - templateWidth
        val maxOffsetY = screenHeight - templateHeight
        val coarseCandidates = mutableListOf<ArrayMatch>()

        for (y in steppedPositions(maxOffsetY, moveStep)) {
            for (x in steppedPositions(maxOffsetX, moveStep)) {
                val score = scoreAt(
                    screenGray, screenEdges, screenWidth,
                    templateGray, templateEdges, templateWidth, templateHeight,
                    x, y, sampleStep, templateForeground,
                )
                retainCoarseCandidate(
                    coarseCandidates,
                    ArrayMatch(x, y, score, templateWidth, templateHeight),
                    moveStep,
                )
            }
        }

        var best = coarseCandidates.maxByOrNull { it.score } ?: return null
        val refinementStep = max(1, moveStep / 3)
        for (coarse in coarseCandidates) {
            val minX = max(0, coarse.x - moveStep)
            val maxX = min(maxOffsetX, coarse.x + moveStep)
            val minY = max(0, coarse.y - moveStep)
            val maxY = min(maxOffsetY, coarse.y + moveStep)
            for (y in steppedRange(minY, maxY, refinementStep)) {
                for (x in steppedRange(minX, maxX, refinementStep)) {
                    val score = scoreAt(
                        screenGray, screenEdges, screenWidth,
                        templateGray, templateEdges, templateWidth, templateHeight,
                        x, y, sampleStep, templateForeground,
                    )
                    if (score > best.score) {
                        best = ArrayMatch(x, y, score, templateWidth, templateHeight)
                    }
                }
            }
        }

        val nearbyBest = best
        for (y in max(0, nearbyBest.y - refinementStep)..min(maxOffsetY, nearbyBest.y + refinementStep)) {
            for (x in max(0, nearbyBest.x - refinementStep)..min(maxOffsetX, nearbyBest.x + refinementStep)) {
                val score = scoreAt(
                    screenGray, screenEdges, screenWidth,
                    templateGray, templateEdges, templateWidth, templateHeight,
                    x, y, sampleStep, templateForeground,
                )
                if (score > best.score) {
                    best = ArrayMatch(x, y, score, templateWidth, templateHeight)
                }
            }
        }

        // Re-score the winning neighborhood with denser pixels. This keeps thin icon strokes
        // from landing between sparse samples and makes the percentage much steadier frame-to-frame.
        val fineSampleStep = max(1, sampleStep / 2)
        var fineBest = ArrayMatch(best.x, best.y, -1f, templateWidth, templateHeight)
        for (y in max(0, best.y - 2)..min(maxOffsetY, best.y + 2)) {
            for (x in max(0, best.x - 2)..min(maxOffsetX, best.x + 2)) {
                val score = scoreAt(
                    screenGray, screenEdges, screenWidth,
                    templateGray, templateEdges, templateWidth, templateHeight,
                    x, y, fineSampleStep, templateForeground,
                )
                if (score > fineBest.score) {
                    fineBest = ArrayMatch(x, y, score, templateWidth, templateHeight)
                }
            }
        }
        return fineBest.copy(score = fineBest.score.coerceIn(0f, 1f))
    }

    private fun steppedRange(start: Int, end: Int, step: Int): IntArray {
        val positions = steppedPositions(end - start, step)
        for (index in positions.indices) positions[index] += start
        return positions
    }

    private fun steppedPositions(maxOffset: Int, step: Int): IntArray {
        if (maxOffset <= 0) return intArrayOf(0)
        val values = ArrayList<Int>(maxOffset / step + 2)
        var value = 0
        while (value <= maxOffset) {
            values += value
            value += step
        }
        if (values.last() != maxOffset) values += maxOffset
        return values.toIntArray()
    }

    private fun retainCoarseCandidate(
        candidates: MutableList<ArrayMatch>,
        candidate: ArrayMatch,
        moveStep: Int,
    ) {
        val nearbyIndex = candidates.indexOfFirst {
            kotlin.math.abs(it.x - candidate.x) <= moveStep &&
                kotlin.math.abs(it.y - candidate.y) <= moveStep
        }
        if (nearbyIndex >= 0) {
            if (candidate.score > candidates[nearbyIndex].score) candidates[nearbyIndex] = candidate
        } else {
            candidates += candidate
        }
        candidates.sortByDescending { it.score }
        if (candidates.size > COARSE_CANDIDATE_LIMIT) {
            candidates.subList(COARSE_CANDIDATE_LIMIT, candidates.size).clear()
        }
    }

    private fun scoreAt(
        screenGray: IntArray,
        screenEdges: IntArray,
        screenWidth: Int,
        templateGray: IntArray,
        templateEdges: IntArray,
        templateWidth: Int,
        templateHeight: Int,
        offsetX: Int,
        offsetY: Int,
        sampleStep: Int,
        templateForeground: ForegroundFeature?,
    ): Float {
        var screenGraySum = 0.0
        var templateGraySum = 0.0
        var screenEdgeSum = 0.0
        var templateEdgeSum = 0.0
        var grayProductSum = 0.0
        var edgeProductSum = 0.0
        var screenGraySquareSum = 0.0
        var templateGraySquareSum = 0.0
        var screenEdgeSquareSum = 0.0
        var templateEdgeSquareSum = 0.0
        var count = 0

        val startX = if (templateWidth > 2) 1 else 0
        val startY = if (templateHeight > 2) 1 else 0
        val endX = if (templateWidth > 2) templateWidth - 1 else templateWidth
        val endY = if (templateHeight > 2) templateHeight - 1 else templateHeight
        var y = startY
        while (y < endY) {
            val screenRow = (offsetY + y) * screenWidth + offsetX
            val templateRow = y * templateWidth
            var x = startX
            while (x < endX) {
                val screenGrayValue = screenGray[screenRow + x].toDouble()
                val templateGrayValue = templateGray[templateRow + x].toDouble()
                val screenEdgeValue = screenEdges[screenRow + x].toDouble()
                val templateEdgeValue = templateEdges[templateRow + x].toDouble()
                screenGraySum += screenGrayValue
                templateGraySum += templateGrayValue
                screenEdgeSum += screenEdgeValue
                templateEdgeSum += templateEdgeValue
                grayProductSum += screenGrayValue * templateGrayValue
                edgeProductSum += screenEdgeValue * templateEdgeValue
                screenGraySquareSum += screenGrayValue * screenGrayValue
                templateGraySquareSum += templateGrayValue * templateGrayValue
                screenEdgeSquareSum += screenEdgeValue * screenEdgeValue
                templateEdgeSquareSum += templateEdgeValue * templateEdgeValue
                count++
                x += sampleStep
            }
            y += sampleStep
        }

        if (count == 0) return 0f
        val graySimilarity = normalizedCorrelation(
            count,
            screenGraySum,
            templateGraySum,
            grayProductSum,
            screenGraySquareSum,
            templateGraySquareSum,
        )
        val edgeSimilarity = normalizedCorrelation(
            count,
            screenEdgeSum,
            templateEdgeSum,
            edgeProductSum,
            screenEdgeSquareSum,
            templateEdgeSquareSum,
        )
        val baseSimilarity = (edgeSimilarity * 0.72f + graySimilarity * 0.28f).coerceIn(0f, 1f)
        val foregroundSimilarity = templateForeground?.let { feature ->
            foregroundScoreAt(
                screenGray,
                screenWidth,
                templateWidth,
                templateHeight,
                offsetX,
                offsetY,
                feature,
            )
        } ?: return baseSimilarity
        // When a symbol foreground is available, do not let whole-image background
        // correlation become a score floor. The foreground mask must both be present and
        // remain distinctive from its negative space; background correlation is only a
        // small stabilizer.
        val symbolSimilarity = foregroundSimilarity * 0.75f + edgeSimilarity * 0.20f + graySimilarity * 0.05f
        return symbolSimilarity.coerceIn(0f, 1f)
    }

    private fun foregroundScoreAt(
        screenGray: IntArray,
        screenWidth: Int,
        templateWidth: Int,
        templateHeight: Int,
        offsetX: Int,
        offsetY: Int,
        feature: ForegroundFeature,
    ): Float {
        val borderStep = max(1, min(templateWidth, templateHeight) / 18)
        var borderSum = 0L
        var borderCount = 0
        for (x in 0 until templateWidth step borderStep) {
            borderSum += screenGray[offsetY * screenWidth + offsetX + x]
            borderSum += screenGray[(offsetY + templateHeight - 1) * screenWidth + offsetX + x]
            borderCount += 2
        }
        for (y in borderStep until templateHeight - borderStep step borderStep) {
            borderSum += screenGray[(offsetY + y) * screenWidth + offsetX]
            borderSum += screenGray[(offsetY + y) * screenWidth + offsetX + templateWidth - 1]
            borderCount += 2
        }
        if (borderCount == 0) return 0f
        val screenBackground = borderSum.toFloat() / borderCount
        val requiredContrast = max(18f, feature.averageContrast * 0.50f)
        val detectionThreshold = requiredContrast * 0.55f
        var truePositive = 0
        var falsePositive = 0
        var foregroundCount = 0
        var contrastStrength = 0f
        val inset = max(1, min(templateWidth, templateHeight) / 24)
        for (y in inset until templateHeight - inset) {
            for (x in inset until templateWidth - inset) {
                val index = y * templateWidth + x
                val observedContrast = feature.polarity *
                    (screenGray[(offsetY + y) * screenWidth + offsetX + x] - screenBackground)
                val observedForeground = observedContrast >= detectionThreshold
                if (feature.mask[index]) {
                    foregroundCount++
                    if (observedForeground) truePositive++
                    contrastStrength += (observedContrast / requiredContrast).coerceIn(0f, 1f)
                } else if (observedForeground) {
                    falsePositive++
                }
            }
        }
        if (foregroundCount == 0) return 0f
        val recall = truePositive.toFloat() / foregroundCount
        // Background texture is tolerated, but extra bright strokes still reduce confidence.
        val precision = truePositive / max(1f, truePositive + falsePositive * 0.30f)
        val f1 = if (precision + recall <= 0f) 0f else 2f * precision * recall / (precision + recall)
        val contrast = contrastStrength / foregroundCount
        return (f1 * 0.78f + contrast * 0.22f).coerceIn(0f, 1f)
    }

    private fun normalizedCorrelation(
        count: Int,
        firstSum: Double,
        secondSum: Double,
        productSum: Double,
        firstSquareSum: Double,
        secondSquareSum: Double,
    ): Float {
        val numerator = count * productSum - firstSum * secondSum
        val firstVariance = count * firstSquareSum - firstSum * firstSum
        val secondVariance = count * secondSquareSum - secondSum * secondSum
        val denominator = kotlin.math.sqrt(firstVariance.coerceAtLeast(0.0) * secondVariance.coerceAtLeast(0.0))
        if (denominator < 1e-6) return 0f
        return (numerator / denominator).toFloat().coerceIn(0f, 1f)
    }
}
