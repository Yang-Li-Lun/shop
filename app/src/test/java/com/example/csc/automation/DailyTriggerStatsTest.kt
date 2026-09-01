package com.example.csc.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
