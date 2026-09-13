package com.example.csc.automation

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyStatsOverlayStateTest {
    @Test fun onlyTodayIsDisplayedAndStoredHistoryIsRetained() {
        val today = LocalDate.of(2026, 9, 13)
        val counts = pruneDailyCounts("2026-09-13=12\n2026-09-12=35\n2026-09-11=28", today)
        assertEquals("今日 12 次", DailyStatsOverlayState(today, dailyCountsForDays(counts, today).first().count).text)
        assertEquals(3, counts.size)
        val tomorrow = today.plusDays(1)
        assertEquals("今日 0 次", DailyStatsOverlayState(tomorrow, dailyCountsForDays(counts, tomorrow).first().count).text)
    }

    @Test fun zeroLargeCountsAndUnavailableAreDistinct() {
        for (count in listOf(0, 9, 10, 99, 100, 999, 1000, Int.MAX_VALUE)) {
            assertEquals("今日 $count 次", DailyStatsOverlayState(LocalDate.of(2026, 1, 1), count).text)
        }
        assertEquals("統計暫不可用", DailyStatsOverlayState(LocalDate.of(2026, 1, 1), null).text)
    }

    @Test fun panelRespectsSafeAreaAndActualMeasuredSize() {
        val safe = DailyStatsRect(0f, 80f, 1080f, 2200f)
        for (w in listOf(90f, 200f, 700f)) {
            val panel = dailyStatsPanel(safe, w, 60f, 8f)!!
            assertEquals(1072f, panel.right)
            assertEquals(88f, panel.top)
            assertEquals(w, panel.right - panel.left)
        }
        assertNull(dailyStatsPanel(safe, 1080f, 60f, 8f))
        assertNull(dailyStatsPanel(safe, 90f, Float.NaN, 8f))
        assertNull(dailyStatsPanel(DailyStatsRect(0f, 0f, 50f, 40f), 90f, 60f, 8f))
    }

    @Test fun overlapIncludesContainingRegionsButNotAdjacentEdges() {
        val panel = DailyStatsRect(800f, 80f, 1000f, 140f)
        assertTrue(panel.intersects(DailyStatsRect(0f, 0f, 1080f, 2400f)))
        assertTrue(panel.intersects(DailyStatsRect(900f, 100f, 950f, 120f)))
        assertFalse(panel.intersects(DailyStatsRect(0f, 140f, 1080f, 2400f)))
        assertFalse(panel.intersects(DailyStatsRect(0f, 0f, 799f, 2400f)))
    }
}
