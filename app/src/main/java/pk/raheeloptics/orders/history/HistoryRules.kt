package pk.raheeloptics.orders.history

import pk.raheeloptics.orders.core.HistoryEntry

object HistoryRules {
    fun merge(cached: List<HistoryEntry>, loaded: List<HistoryEntry>): List<HistoryEntry> =
        (cached + loaded).distinctBy { it.id }.sortedWith(compareByDescending<HistoryEntry> { it.occurredAt }.thenByDescending { it.id })

    // Keep the event ledger intact; only the list groups transitions into one row per todo.
    fun latest(entries: List<HistoryEntry>): List<HistoryEntry> = entries.groupBy { it.todo.id }.values.map { events ->
        events.maxWith(compareBy<HistoryEntry> { it.pending }.thenBy { it.todo.revision }.thenBy { it.occurredAt }.thenBy { it.id })
    }.sortedWith(compareByDescending<HistoryEntry> { it.occurredAt }.thenByDescending { it.todo.id })

    fun filter(history: List<HistoryEntry>, filter: String): List<HistoryEntry> = history.filter {
        filter == "All" || (filter == "Completed" && it.action == "complete") || (filter == "Deleted" && it.action == "delete")
    }
}
