package com.example.csc.automation

import kotlin.math.abs

/** Color evidence is about ink, not a few matching background pixels. */
internal fun matchesNumberInk(pixel: Int, target: Int, tolerance: Int): Boolean {
    val pr = (pixel ushr 16) and 255
    val pg = (pixel ushr 8) and 255
    val pb = pixel and 255
    val tr = (target ushr 16) and 255
    val tg = (target ushr 8) and 255
    val tb = target and 255
    val limit = tolerance.coerceIn(0, 255)
    if ((pr-tr)*(pr-tr) + (pg-tg)*(pg-tg) + (pb-tb)*(pb-tb) > limit*limit*3) return false
    val tmin = minOf(tr, tg, tb)
    val pmin = minOf(pr, pg, pb)
    val tc = maxOf(tr, tg, tb) - tmin
    val pc = maxOf(pr, pg, pb) - pmin
    if (tc < 30) return pc <= maxOf(24, tc + limit / 2)
    if (pc < maxOf(20, tc / 3)) return false
    return abs((pr-pmin).toDouble()/pc - (tr-tmin).toDouble()/tc) <= 0.28 &&
        abs((pg-pmin).toDouble()/pc - (tg-tmin).toDouble()/tc) <= 0.28 &&
        abs((pb-pmin).toDouble()/pc - (tb-tmin).toDouble()/tc) <= 0.28
}

/** Reject background fills and sparse flecks; require strokes across the glyph height. */
internal fun classifyNumberInk(mask: BooleanArray, width: Int, height: Int): Int {
    if (width < 1 || height < 1 || mask.size != width * height) return 0
    val count = mask.count { it }
    if (count < 2) return -1
    val coverage = count.toDouble() / mask.size
    var border = 0
    var coloredBorder = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (x == 0 || y == 0 || x == width-1 || y == height-1) {
            border++
            if (mask[y*width+x]) coloredBorder++
        }
    }
    // White text punched out of a target-colored panel is background, not matching ink.
    if (coloredBorder > border * 0.65) return -1
    val rows = (0 until height).count { y -> (0 until width).any { x -> mask[y * width + x] } }
    return if (coverage in 0.06..0.85 && rows >= height * 0.55) 1 else -1
}

/** Restore only an actual compact ink mark between digit boxes, never guess from the value. */
internal fun recoverNumberDecimalPoints(
    elements: List<NumberTextElement>,
    inkAt: (Int, Int) -> Boolean,
): List<NumberTextElement> {
    val ordered = elements.sortedBy { it.bounds.left }
    val result = mutableListOf<NumberTextElement>()
    ordered.forEachIndexed { index, second ->
        val first = ordered.getOrNull(index - 1)
        if (first != null && first.text.all(Char::isDigit) && second.text.all(Char::isDigit)) {
            val height = minOf(first.bounds.bottom-first.bounds.top, second.bounds.bottom-second.bounds.top)
            val left = kotlin.math.ceil(first.bounds.right).toInt()
            val right = second.bounds.left.toInt()
            val baseline = minOf(first.bounds.bottom, second.bounds.bottom)
            if (height >= 8 && right-left in 2..maxOf(2, (height*0.6f).toInt()) &&
                abs(first.bounds.bottom-second.bounds.bottom) <= height*0.2f) {
                val points = mutableListOf<Pair<Int, Int>>()
                for (y in (baseline-height*0.35f).toInt() until baseline.toInt()+1)
                    for (x in left until right) if (inkAt(x,y)) points += x to y
                if (points.isNotEmpty()) {
                    val x0 = points.minOf { it.first }; val x1 = points.maxOf { it.first }
                    val y0 = points.minOf { it.second }; val y1 = points.maxOf { it.second }
                    val w = x1-x0+1; val h = y1-y0+1
                    if (w <= height*0.3f && h <= height*0.3f && w.toFloat()/h in 0.4f..2.5f &&
                        points.size >= w*h*0.6f && y1 >= baseline-height*0.2f) {
                        result += NumberTextElement(".", ClickBounds(x0.toFloat(), y0.toFloat(), (x1+1).toFloat(), (y1+1).toFloat()))
                    }
                }
            }
        }
        result += second
    }
    return result
}
