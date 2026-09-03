package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyTriggerStatsTest {
    @Test
    fun incrementsPerDayAndDropsExpiredOrMalformedEntries() {
        val today = LocalDate.of(2026, 8, 27)
        val raw = "2026-08-27=2\n2026-08-26=5\n2025-01-01=99\nbad=4"
        val updated = incrementDailyCounts(raw, today, retentionDays = 30)
        assertEquals(3, updated["2026-08-27"])
        assertEquals(5, updated["2026-08-26"])
        assertFalse(updated.containsKey("2025-01-01"))
        assertEquals(updated, decodeDailyCounts(encodeDailyCounts(updated)))
    }

    @Test
    fun pruneKeepsTodayYesterdayAndDayBeforeAndRemovesFutureData() {
        val today = LocalDate.of(2026, 9, 3)
        val raw = """
            2026-09-03=101
            2026-09-02=97
            2026-09-01=103
            2026-08-31=76
            2026-09-04=9
            2026-02-30=7
            2026-08-30=-1
        """.trimIndent()

        val pruned = pruneDailyCounts(raw, today)

        assertEquals(mapOf(
            "2026-09-01" to 103,
            "2026-09-02" to 97,
            "2026-09-03" to 101,
        ), pruned)
        assertEquals(
            listOf(
                DailyTriggerCount("2026-09-03", 101),
                DailyTriggerCount("2026-09-02", 97),
                DailyTriggerCount("2026-09-01", 103),
            ),
            dailyCountsForDays(pruned, today),
        )
    }

    @Test
    fun pruneHandlesMonthAndYearBoundaries() {
        val today = LocalDate.of(2025, 1, 1)
        val raw = "2025-01-01=1\n2024-12-31=2\n2024-12-30=3\n2024-12-29=4"

        val pruned = pruneDailyCounts(raw, today)

        assertTrue(pruned.containsKey("2025-01-01"))
        assertTrue(pruned.containsKey("2024-12-31"))
        assertTrue(pruned.containsKey("2024-12-30"))
        assertFalse(pruned.containsKey("2024-12-29"))
    }

    @Test
    fun incrementOnlyChangesTheRequestedDay() {
        val today = LocalDate.of(2026, 9, 3)
        val updated = incrementDailyCounts("2026-09-01=103\n2026-09-02=97\n2026-09-03=103", today)

        assertEquals(104, updated["2026-09-03"])
        assertEquals(103, updated["2026-09-01"])
        assertEquals(97, updated["2026-09-02"])
    }
}
