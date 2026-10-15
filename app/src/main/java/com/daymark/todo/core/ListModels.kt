package com.daymark.todo.core

import java.time.LocalDate
import java.time.ZoneId

enum class DeadlineFilter(val label: String) {
    Any("Any time"), Overdue("Overdue"), Today("Today"), Week("Next 7 days"), None("No deadline")
}
enum class TodoSort(val label: String) {
    Deadline("Due soonest"), LatestDeadline("Due latest"), Latest("Recently updated"), Priority("Priority")
}
data class TodoQuery(val priority: String? = null, val deadline: DeadlineFilter = DeadlineFilter.Any, val sort: TodoSort = TodoSort.Latest)
data class ListCursor(val value: Long, val id: String)
data class TodoPage(val items: List<Todo>, val next: ListCursor?)
data class HistoryPage(val items: List<HistoryEntry>, val next: ListCursor?)

object ListRules {
    const val PageSize = 25
    fun timeBounds(now: Long, zone: ZoneId): Pair<Long, Long> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun sortKey(todo: Todo, sort: TodoSort): Long = when(sort) {
        TodoSort.Deadline -> todo.dueAt ?: Long.MAX_VALUE
        TodoSort.LatestDeadline -> todo.dueAt?.let { -it } ?: Long.MAX_VALUE
        TodoSort.Latest -> -todo.updatedAt
        TodoSort.Priority -> when(todo.priority) { "High" -> 0L; "Medium" -> 1L; else -> 2L }
    }
}

object PasswordRules {
    fun validate(current: String, replacement: String) {
        require(current.isNotBlank()) { "Enter your current password." }
        require(replacement.length >= 12) { "Use at least 12 characters." }
        require(replacement != current) { "Choose a different password." }
    }
}

object RegistrationRules {
    fun validate(password:String,confirmation:String) {
        require(password.length>=12) { "Use at least 12 characters." }
        require(password==confirmation) { "Passwords don't match." }
    }
}

data class ServerChange(val id: String, val todo: Todo, val accountRevision: Long)

@kotlinx.serialization.Serializable
data class HistoryAnalytics(val trend: List<Int>, val withDeadline: Int, val onTime: Int, val revision: Long,
    val priorityTrends: Map<String, List<Int>> = emptyMap())
