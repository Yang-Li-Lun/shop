package com.example.csc.automation

import java.time.LocalDate

/** A local-date snapshot; null means unavailable, never a fabricated zero. */
internal data class DailyStatsOverlayState(val date: LocalDate, val count: Int?) {
    val text: String get() = count?.let { "今日 $it 次" } ?: "統計暫不可用"
}

internal data class DailyStatsRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: DailyStatsRect): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

internal fun dailyStatsPanel(safe: DailyStatsRect, width: Float, height: Float, margin: Float): DailyStatsRect? {
    if (!listOf(safe.left, safe.top, safe.right, safe.bottom, width, height, margin).all { it.isFinite() } ||
        width <= 0f || height <= 0f || margin < 0f ||
        width + margin * 2 > safe.right - safe.left || height + margin * 2 > safe.bottom - safe.top) return null
    return DailyStatsRect(safe.right - margin - width, safe.top + margin, safe.right - margin, safe.top + margin + height)
}
