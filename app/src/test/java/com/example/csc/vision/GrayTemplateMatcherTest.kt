package com.example.csc.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GrayTemplateMatcherTest {
    @Test
    fun findsExactPatternInsideScreen() {
        val screenWidth = 20
        val screenHeight = 16
        val templateWidth = 5
        val templateHeight = 4
        val screen = IntArray(screenWidth * screenHeight) { 20 }
        val template = IntArray(templateWidth * templateHeight) { index ->
            ((index * 37) + 40) % 256
        }
        val expectedX = 9
        val expectedY = 7
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                screen[(expectedY + y) * screenWidth + expectedX + x] = template[y * templateWidth + x]
            }
        }

        val result = GrayTemplateMatcher.find(
            screen, edges(screen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight,
        )

        assertNotNull(result)
        assertEquals(expectedX, result!!.x)
        assertEquals(expectedY, result.y)
        assertTrue(result.score > 0.99f)
    }

    @Test
    fun returnsNullWhenTemplateIsLargerThanScreen() {
        val result = GrayTemplateMatcher.find(
            IntArray(9), IntArray(9), 3, 3,
            IntArray(16), IntArray(16), 4, 4,
        )
        assertEquals(null, result)
    }

    @Test
    fun findsShapeAfterBackgroundBrightnessChanges() {
        val screenWidth = 24
        val screenHeight = 20
        val templateWidth = 6
        val templateHeight = 6
        val template = IntArray(templateWidth * templateHeight) { index ->
            val x = index % templateWidth
            val y = index / templateWidth
            if (x == y || x + y == templateWidth - 1) 235 else 45
        }
        val screen = IntArray(screenWidth * screenHeight) { 120 }
        val expectedX = 11
        val expectedY = 8
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                val source = template[y * templateWidth + x]
                screen[(expectedY + y) * screenWidth + expectedX + x] = (source * 0.55f + 20f).toInt()
            }
        }

        val result = GrayTemplateMatcher.find(
            screen, edges(screen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight,
        )

        assertNotNull(result)
        assertEquals(expectedX, result!!.x)
        assertEquals(expectedY, result.y)
        assertTrue(result.score > 0.9f)
    }

    @Test
    fun keepsThinSymbolStableAtNonGridPosition() {
        val screenWidth = 140
        val screenHeight = 110
        val templateWidth = 42
        val templateHeight = 42
        val template = IntArray(templateWidth * templateHeight) { index ->
            val x = index % templateWidth
            val y = index / templateWidth
            val background = 65 + ((x * 7 + y * 11) % 35)
            if (isBackArrowPixel(x, y)) 242 else background
        }
        val screen = IntArray(screenWidth * screenHeight) { index ->
            val x = index % screenWidth
            val y = index / screenWidth
            105 + ((x * 13 + y * 17) % 45)
        }
        val expectedX = 53
        val expectedY = 37
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                if (isBackArrowPixel(x, y)) {
                    screen[(expectedY + y) * screenWidth + expectedX + x] = 238
                }
            }
        }

        val foreground = GrayTemplateMatcher.detectForeground(template, templateWidth, templateHeight)
        assertNotNull(foreground)
        val result = GrayTemplateMatcher.find(
            screen, edges(screen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight,
            foreground,
        )

        assertNotNull(result)
        assertTrue(kotlin.math.abs(result!!.x - expectedX) <= 1)
        assertTrue(kotlin.math.abs(result.y - expectedY) <= 1)
        assertTrue(result.score > 0.55f)
    }

    @Test
    fun ignoresChangedBackgroundAndFindsForegroundSymbol() {
        val screenWidth = 172
        val screenHeight = 126
        val templateWidth = 48
        val templateHeight = 48
        val template = IntArray(templateWidth * templateHeight) { index ->
            val x = index % templateWidth
            val y = index / templateWidth
            val background = 35 + ((x * 19 + y * 23 + x * y) % 90)
            if (isBackArrowPixel(x - 3, y - 3)) 248 else background
        }
        val screen = IntArray(screenWidth * screenHeight) { index ->
            val x = index % screenWidth
            val y = index / screenWidth
            95 + ((x * 31 + y * 7 + x * y * 3) % 120)
        }
        val expectedX = 79
        val expectedY = 51
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                if (isBackArrowPixel(x - 3, y - 3)) {
                    screen[(expectedY + y) * screenWidth + expectedX + x] = 250
                }
            }
        }

        val foreground = GrayTemplateMatcher.detectForeground(template, templateWidth, templateHeight)
        assertNotNull(foreground)
        assertTrue(foreground!!.mask.count { it } in 20..250)
        val result = GrayTemplateMatcher.find(
            screen, edges(screen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight,
            foreground,
        )

        assertNotNull(result)
        assertTrue(kotlin.math.abs(result!!.x - expectedX) <= 2)
        assertTrue(kotlin.math.abs(result.y - expectedY) <= 2)
        assertTrue(result.score > 0.6f)
    }

    @Test
    fun safePointIsInsideDetectedForeground() {
        val width = 24
        val height = 24
        val mask = BooleanArray(width * height)
        for (y in 7..16) {
            for (x in 5..18) {
                if (x == 5 || y == 7 || y == 16) mask[y * width + x] = true
            }
        }
        val feature = ForegroundFeature(mask, 40, 1, 180f)

        val point = TemplateMatcher.foregroundSafePoint(feature, width, height)
        val x = point.first.toInt().coerceIn(0, width - 1)
        val y = point.second.toInt().coerceIn(0, height - 1)

        assertTrue(mask[y * width + x])
    }

    @Test
    fun foregroundCombinesDisconnectedCircleAndX() {
        val width = 48
        val height = 48
        val gray = IntArray(width * height) { 35 }
        for (y in 5..42) {
            for (x in 5..42) {
                val dx = x - 24
                val dy = y - 24
                val radiusSquared = dx * dx + dy * dy
                val ring = radiusSquared in 310..390
                val cross = kotlin.math.abs(dx - dy) <= 1 || kotlin.math.abs(dx + dy) <= 1
                if (ring || (cross && kotlin.math.abs(dx) <= 11 && kotlin.math.abs(dy) <= 11)) {
                    gray[y * width + x] = 245
                }
            }
        }

        val feature = GrayTemplateMatcher.detectForeground(gray, width, height)

        assertNotNull(feature)
        assertTrue(feature!!.mask[24 * width + 24])
        assertTrue(feature.mask[6 * width + 24])
        assertTrue(feature.mask.count { it } > 150)
    }

    @Test
    fun symbolScoreSeparatesTargetFromBrightTexture() {
        val templateWidth = 48
        val templateHeight = 48
        val template = circleX(templateWidth, templateHeight)
        val feature = GrayTemplateMatcher.detectForeground(template, templateWidth, templateHeight)
        assertNotNull(feature)
        val screenWidth = 130
        val screenHeight = 90
        val targetScreen = texturedScreen(screenWidth, screenHeight)
        val distractorScreen = texturedScreen(screenWidth, screenHeight)
        val expectedX = 61
        val expectedY = 23
        for (y in 0 until templateHeight) {
            for (x in 0 until templateWidth) {
                if (feature!!.mask[y * templateWidth + x]) {
                    targetScreen[(expectedY + y) * screenWidth + expectedX + x] = 248
                }
            }
        }

        val target = GrayTemplateMatcher.find(
            targetScreen, edges(targetScreen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight, feature,
        )
        val distractor = GrayTemplateMatcher.find(
            distractorScreen, edges(distractorScreen, screenWidth, screenHeight), screenWidth, screenHeight,
            template, edges(template, templateWidth, templateHeight), templateWidth, templateHeight, feature,
        )

        assertNotNull(target)
        assertNotNull(distractor)
        assertTrue(target!!.score > distractor!!.score + 0.18f)
        assertTrue(target.score > 0.72f)
    }

    private fun circleX(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val dx = x - width / 2
        val dy = y - height / 2
        val radiusSquared = dx * dx + dy * dy
        val ring = radiusSquared in 310..390
        val cross = (kotlin.math.abs(dx - dy) <= 1 || kotlin.math.abs(dx + dy) <= 1) &&
            kotlin.math.abs(dx) <= 11 && kotlin.math.abs(dy) <= 11
        if (ring || cross) 245 else 35
    }

    private fun texturedScreen(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        35 + ((x * 37 + y * 19 + x * y * 5) % 155)
    }

    private fun isBackArrowPixel(x: Int, y: Int): Boolean {
        val shaft = x in 18..33 && y in 19..22
        val upper = x in 9..20 && kotlin.math.abs((x - 9) - (20 - y)) <= 1
        val lower = x in 9..20 && kotlin.math.abs((x - 9) - (y - 21)) <= 1
        return shaft || upper || lower
    }

    private fun edges(gray: IntArray, width: Int, height: Int): IntArray {
        val output = IntArray(gray.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                output[i] = minOf(
                    255,
                    kotlin.math.abs(gray[i + 1] - gray[i - 1]) +
                        kotlin.math.abs(gray[i + width] - gray[i - width]),
                )
            }
        }
        return output
    }
}
