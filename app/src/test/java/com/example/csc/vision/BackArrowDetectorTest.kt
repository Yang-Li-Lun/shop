package com.example.csc.vision

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs

class BackArrowDetectorTest {
    @Test
    fun recognizesAllBundledReturnSamplesWithoutReferenceMatching() {
        val directory = sequenceOf(
            File("app/src/main/assets/profile_images"),
            File("src/main/assets/profile_images"),
        ).first { it.isDirectory }
        val results = (1..5).map { index ->
            val image = ImageIO.read(File(directory, "return_$index.jpg"))
            BackArrowDetector.find(toColors(image), image.width, image.height)
        }

        assertTrue("matches=$results", results.all { it != null && it.score >= 0.88f })
        assertTrue("scores=${results.map { it?.score }}", results.all { it!!.score < 0.995f })
        results.forEach { match ->
            assertTrue(match!!.clickCenterX in 20f..65f)
            assertTrue(match.clickCenterY in 20f..65f)
        }
    }

    @Test
    fun findsCompleteLeftChevronAndRejectsIncompleteOrWrongDirectionShapes() {
        val width = 120
        val height = 120
        val complete = background(width, height)
        val upperOnly = background(width, height)
        val rightChevron = background(width, height)
        val grid = background(width, height)
        drawLeftChevron(complete, width, 35, 60, 30)
        drawArm(upperOnly, width, 35, 60, 30, -1)
        drawRightChevron(rightChevron, width, 85, 60, 30)
        drawGrid(grid, width, height)

        val completeMatch = BackArrowDetector.find(complete, width, height)
        val incompleteMatch = BackArrowDetector.find(upperOnly, width, height)
        val wrongDirectionMatch = BackArrowDetector.find(rightChevron, width, height)
        val gridMatch = BackArrowDetector.find(grid, width, height)

        assertNotNull(completeMatch)
        assertTrue(abs(completeMatch!!.centerX - 50f) <= 5f)
        assertTrue(abs(completeMatch.centerY - 60f) <= 4f)
        assertTrue("complete=${completeMatch.score}", completeMatch.score >= 0.86f)
        assertTrue("incomplete=${incompleteMatch?.score}", completeMatch.score >= (incompleteMatch?.score ?: 0f) + 0.18f)
        assertTrue("right=${wrongDirectionMatch?.score}", completeMatch.score >= (wrongDirectionMatch?.score ?: 0f) + 0.18f)
        assertTrue("grid=${gridMatch?.score}", (gridMatch?.score ?: 0f) < 0.65f)
    }

    private fun toColors(image: java.awt.image.BufferedImage): IntArray =
        IntArray(image.width * image.height) { index ->
            image.getRGB(index % image.width, index / image.width)
        }

    private fun background(width: Int, height: Int): IntArray = IntArray(width * height) { index ->
        val x = index % width
        val y = index / width
        val red = 185 + ((x * 7 + y * 3) % 50)
        val green = 72 + ((x * 3 + y * 5) % 70)
        val blue = 8 + ((x * 5 + y * 7) % 28)
        -0x1000000 or (red.coerceAtMost(255) shl 16) or (green.coerceAtMost(255) shl 8) or blue
    }

    private fun drawLeftChevron(colors: IntArray, width: Int, vertexX: Int, vertexY: Int, span: Int) {
        drawArm(colors, width, vertexX, vertexY, span, -1)
        drawArm(colors, width, vertexX, vertexY, span, 1)
    }

    private fun drawArm(
        colors: IntArray,
        width: Int,
        vertexX: Int,
        vertexY: Int,
        span: Int,
        verticalDirection: Int,
    ) {
        for (offset in 0..span) {
            for (thickness in -1..1) {
                val x = vertexX + offset + thickness
                val y = vertexY + verticalDirection * offset
                colors[y * width + x] = -0x1
            }
        }
    }

    private fun drawRightChevron(colors: IntArray, width: Int, vertexX: Int, vertexY: Int, span: Int) {
        for (offset in 0..span) {
            for (thickness in -1..1) {
                colors[(vertexY - offset) * width + vertexX - offset + thickness] = -0x1
                colors[(vertexY + offset) * width + vertexX - offset + thickness] = -0x1
            }
        }
    }

    private fun drawGrid(colors: IntArray, width: Int, height: Int) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (x % 14 <= 2 || y % 14 <= 2) colors[y * width + x] = -0x1
            }
        }
    }
}
