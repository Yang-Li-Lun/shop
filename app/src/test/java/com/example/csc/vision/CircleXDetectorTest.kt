package com.example.csc.vision

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

class CircleXDetectorTest {
    @Test
    fun replaysAllBundledCircleXSamplesAgainstArrowNegatives() {
        val directory = sequenceOf(
            File("app/src/main/assets/profile_images"),
            File("src/main/assets/profile_images"),
        ).first { it.isDirectory }
        val positiveScores = (1..5).map { index ->
            val image = ImageIO.read(File(directory, "x_$index.jpg"))
            CircleXDetector.find(toGray(image), image.width, image.height)?.score ?: 0f
        }
        val negativeScores = (1..5).map { index ->
            val image = ImageIO.read(File(directory, "return_$index.jpg"))
            CircleXDetector.find(toGray(image), image.width, image.height)?.score ?: 0f
        }
        val weakestTarget = positiveScores.minOrNull() ?: 0f
        val strongestNegative = negativeScores.maxOrNull() ?: 1f
        assertTrue("positive scores=$positiveScores", positiveScores.all { it in 0.40f..0.994f })
        assertTrue(
            "positive=$positiveScores, arrow negatives=$negativeScores",
            weakestTarget >= strongestNegative + 0.08f,
        )
    }

    @Test
    fun realSamplesRemainDetectableAcrossBrightnessChanges() {
        val directory = sequenceOf(
            File("app/src/main/assets/profile_images"),
            File("src/main/assets/profile_images"),
        ).first { it.isDirectory }
        for (index in 1..5) {
            val image = ImageIO.read(File(directory, "x_$index.jpg"))
            for (brightness in floatArrayOf(0.72f, 1f, 1.20f)) {
                val score = CircleXDetector.find(toGray(image, brightness), image.width, image.height)?.score ?: 0f
                assertTrue("x_$index brightness=$brightness score=$score", score >= 0.34f)
                assertTrue("x_$index must not saturate", score < 0.995f)
            }
        }
    }

    @Test
    fun recognizesBundledRealCircleXWithoutSaturating() {
        val file = sequenceOf(
            File("app/src/main/assets/profile_images/x_1.jpg"),
            File("src/main/assets/profile_images/x_1.jpg"),
        ).first { it.isFile }
        val image = ImageIO.read(file)
        val gray = IntArray(image.width * image.height) { index ->
            val color = image.getRGB(index % image.width, index / image.width)
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            (red * 77 + green * 150 + blue * 29) shr 8
        }
        val match = CircleXDetector.find(gray, image.width, image.height)
        val grid = background(image.width, image.height)
        drawGrid(grid, image.width, image.height)
        val gridMatch = CircleXDetector.find(grid, image.width, image.height)
        assertNotNull(match)
        assertNotNull(gridMatch)
        assertTrue("real symbol scored ${match!!.score}", match.score >= 0.40f)
        assertTrue("real symbol must not saturate", match.score < 0.995f)
        assertTrue(
            "real=${match.score}/${match.width}, complex=${gridMatch!!.score}/${gridMatch.width}",
            match.score >= gridMatch.score + 0.10f,
        )
    }

    @Test
    fun findsCompleteCircleXAndRejectsPartialShapes() {
        val width = 140
        val height = 180
        val centerX = 73
        val centerY = 92
        val radius = 23
        val complete = background(width, height)
        val circleOnly = background(width, height)
        val xOnly = background(width, height)
        val denseGrid = background(width, height)
        drawCircle(complete, width, height, centerX, centerY, radius)
        drawX(complete, width, height, centerX, centerY, radius)
        drawCircle(circleOnly, width, height, centerX, centerY, radius)
        drawX(xOnly, width, height, centerX, centerY, radius)
        drawGrid(denseGrid, width, height)

        val completeMatch = CircleXDetector.find(complete, width, height)
        val circleMatch = CircleXDetector.find(circleOnly, width, height)
        val xMatch = CircleXDetector.find(xOnly, width, height)
        val textureMatch = CircleXDetector.find(background(width, height), width, height)
        val gridMatch = CircleXDetector.find(denseGrid, width, height)

        assertNotNull(completeMatch)
        assertNotNull(circleMatch)
        assertNotNull(xMatch)
        assertNotNull(textureMatch)
        assertNotNull(gridMatch)
        assertTrue(abs(completeMatch!!.centerX - centerX) <= 3)
        assertTrue(abs(completeMatch.centerY - centerY) <= 3)
        assertTrue(completeMatch.score > 0.72f)
        assertTrue(completeMatch.score > circleMatch!!.score + 0.12f)
        assertTrue(completeMatch.score > xMatch!!.score + 0.12f)
        assertTrue(completeMatch.score > textureMatch!!.score + 0.18f)
        assertTrue("grid scored ${gridMatch!!.score}", gridMatch.score < 0.70f)
        assertTrue("complete=${completeMatch.score}, grid=${gridMatch.score}", completeMatch.score > gridMatch.score + 0.18f)
    }

    private fun background(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        45 + ((x * 11 + y * 7 + x * y) % 38)
    }

    private fun toGray(image: java.awt.image.BufferedImage, brightness: Float = 1f): IntArray =
        IntArray(image.width * image.height) { index ->
            val color = image.getRGB(index % image.width, index / image.width)
            val red = color shr 16 and 0xff
            val green = color shr 8 and 0xff
            val blue = color and 0xff
            (((red * 77 + green * 150 + blue * 29) shr 8) * brightness).toInt().coerceIn(0, 255)
        }

    private fun drawCircle(gray: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val distance = sqrt(((x - cx) * (x - cx) + (y - cy) * (y - cy)).toDouble())
                if (abs(distance - radius) <= 1.5) gray[y * width + x] = 245
            }
        }
    }

    private fun drawX(gray: IntArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int) {
        val half = (radius * 0.58f).toInt()
        for (offset in -half..half) {
            for (thickness in -1..1) {
                gray[(cy + offset) * width + cx + offset + thickness] = 245
                gray[(cy - offset) * width + cx + offset + thickness] = 245
            }
        }
    }

    private fun drawGrid(gray: IntArray, width: Int, height: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x % 12 <= 2 || y % 12 <= 2) gray[y * width + x] = 235
            }
        }
    }
}
