package com.example.csc.automation

import android.content.Context
import java.time.LocalDate

data class DailyTriggerCount(val date: String, val count: Int)

object DailyTriggerStats {
    private const val PREFS = "daily_trigger_stats"
    private const val KEY_COUNTS = "counts_v1"
    private const val RETENTION_DAYS = 120

    fun recordCompletedSwipe(context: Context, date: LocalDate = LocalDate.now()): Int {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = incrementDailyCounts(preferences.getString(KEY_COUNTS, null), date, RETENTION_DAYS)
        preferences.edit().putString(KEY_COUNTS, encodeDailyCounts(updated)).apply()
        return updated[date.toString()] ?: 0
    }

    fun recent(context: Context, limit: Int = 14): List<DailyTriggerCount> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COUNTS, null)
        return decodeDailyCounts(raw)
            .entries
            .sortedByDescending { it.key }
            .take(limit.coerceIn(1, 120))
            .map { DailyTriggerCount(it.key, it.value) }
    }

    fun today(context: Context): Int = recent(context, RETENTION_DAYS)
        .firstOrNull { it.date == LocalDate.now().toString() }
        ?.count
        ?: 0
}

internal fun incrementDailyCounts(
    raw: String?,
    date: LocalDate,
    retentionDays: Int = 120,
): Map<String, Int> {
    val oldest = date.minusDays((retentionDays - 1).coerceAtLeast(0).toLong())
    val counts = decodeDailyCounts(raw)
        .filterKeys { key -> runCatching { LocalDate.parse(key) >= oldest }.getOrDefault(false) }
        .toMutableMap()
    val key = date.toString()
    counts[key] = (counts[key] ?: 0) + 1
    return counts.toSortedMap()
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
