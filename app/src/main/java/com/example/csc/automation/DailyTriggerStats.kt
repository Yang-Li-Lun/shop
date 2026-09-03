package com.example.csc.automation

import android.content.Context
import java.time.LocalDate

data class DailyTriggerCount(val date: String, val count: Int)

object DailyTriggerStats {
    private const val PREFS = "daily_trigger_stats"
    private const val KEY_COUNTS = "counts_v1"
    private const val RETENTION_DAYS = 3
    private val preferencesLock = Any()

    fun recordCompletedSwipe(context: Context, date: LocalDate = LocalDate.now()): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(preferencesLock) {
            val updated = incrementDailyCounts(preferences.getString(KEY_COUNTS, null), date, RETENTION_DAYS)
            preferences.edit().putString(KEY_COUNTS, encodeDailyCounts(updated)).apply()
            updated[date.toString()] ?: 0
        }
    }

    fun recent(context: Context, limit: Int = 14): List<DailyTriggerCount> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(preferencesLock) {
            val counts = readAndPrune(preferences, LocalDate.now())
            counts.entries
                .sortedByDescending { it.key }
                .take(limit.coerceAtLeast(0))
                .map { DailyTriggerCount(it.key, it.value) }
        }
    }

    /** Returns today, yesterday and the day before, including zero-count days. */
    fun lastThreeDays(context: Context, today: LocalDate = LocalDate.now()): List<DailyTriggerCount> {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(preferencesLock) {
            dailyCountsForDays(readAndPrune(preferences, today), today, RETENTION_DAYS)
        }
    }

    fun today(context: Context): Int = lastThreeDays(context).first().count

    private fun readAndPrune(preferences: android.content.SharedPreferences, today: LocalDate): Map<String, Int> {
        val raw = preferences.getString(KEY_COUNTS, null)
        val pruned = pruneDailyCounts(raw, today, RETENTION_DAYS)
        val encoded = encodeDailyCounts(pruned)
        if (raw != encoded) preferences.edit().putString(KEY_COUNTS, encoded).apply()
        return pruned
    }
}

internal fun incrementDailyCounts(
    raw: String?,
    date: LocalDate,
    retentionDays: Int = 3,
): Map<String, Int> {
    val counts = pruneDailyCounts(raw, date, retentionDays).toMutableMap()
    val key = date.toString()
    counts[key] = (counts[key] ?: 0) + 1
    return counts.toSortedMap()
}

internal fun pruneDailyCounts(
    raw: String?,
    today: LocalDate,
    retentionDays: Int = 3,
): Map<String, Int> {
    if (retentionDays <= 0) return emptyMap()
    val oldest = today.minusDays((retentionDays - 1).toLong())
    return decodeDailyCounts(raw)
        .mapNotNull { (key, count) ->
            val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
            if (date in oldest..today) key to count else null
        }
        .toMap()
        .toSortedMap()
}

internal fun dailyCountsForDays(
    counts: Map<String, Int>,
    today: LocalDate,
    days: Int = 3,
): List<DailyTriggerCount> = (0 until days.coerceAtLeast(0)).map { offset ->
    val date = today.minusDays(offset.toLong()).toString()
    DailyTriggerCount(date, counts[date] ?: 0)
}

internal fun decodeDailyCounts(raw: String?): Map<String, Int> = raw
    .orEmpty()
    .lineSequence()
    .mapNotNull { line ->
        val parts = line.split('=', limit = 2)
        val date = parts.getOrNull(0)?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) } ?: return@mapNotNull null
        val count = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        date to count
    }
    .toMap()

internal fun encodeDailyCounts(counts: Map<String, Int>): String =
    counts.toSortedMap().entries.joinToString("\n") { (date, count) -> "$date=$count" }
