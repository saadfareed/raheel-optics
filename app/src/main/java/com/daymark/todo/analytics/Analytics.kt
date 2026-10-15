package com.daymark.todo.analytics

import com.daymark.todo.core.*
import java.time.*

object Analytics {
    fun completionTrend(history: List<HistoryEntry>, days: Int, today: LocalDate, zone: ZoneId): List<Pair<LocalDate, Int>> {
        val counts = history.distinctBy { it.id }.filter { it.action == "complete" }
            .groupingBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }.eachCount()
        return (days - 1 downTo 0).map { today.minusDays(it.toLong()).let { date -> date to (counts[date] ?: 0) } }
    }
    fun progress(totals: Totals): Float? = (totals.active + totals.completed).takeIf { it > 0 }?.let { totals.completed.toFloat() / it }
    fun onTime(history: List<HistoryEntry>): Float? {
        val completed = history.distinctBy { it.id }.filter { it.action == "complete" && it.todo.dueAt != null }
        return completed.takeIf { it.isNotEmpty() }?.let { rows -> rows.count { it.occurredAt <= it.todo.dueAt!! }.toFloat() / rows.size }
    }
}
