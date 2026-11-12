package pk.raheeloptics.orders.core

import java.time.LocalDate
import java.time.ZoneId

enum class DeadlineFilter(val label: String) {
    Any("Any time"), Overdue("Overdue"), Today("Today"), Week("Next 7 days"), None("No promised date")
}
enum class TodoSort(val label: String) {
    Deadline("Promised soonest"), LatestDeadline("Promised latest"), Latest("Recently updated"), Priority("Urgency"), Newest("Newest orders"), Oldest("Oldest orders"), CustomerName("Customer A–Z"), Balance("Highest balance")
}
/** Order list tabs, one per status; the list opens on ACTIVE. ALL is only used for exports and internal queries. */
enum class OrderView(val label:String,val statuses:Set<OrderStatus>?) {
    ACTIVE("Active",setOf(OrderStatus.ACTIVE)),
    READY("Ready",setOf(OrderStatus.READY_FOR_PICKUP)),
    COMPLETE("Completed",setOf(OrderStatus.COMPLETE)),
    CANCEL("Cancelled",setOf(OrderStatus.CANCEL)),
    ALL("All",null)
}
enum class PaymentFilter(val label:String) {ANY("Any payment"),UNPAID("Unpaid"),PARTIAL("Partially paid"),PAID("Paid")}
data class TodoQuery(val priority: String? = null, val deadline: DeadlineFilter = DeadlineFilter.Any, val sort: TodoSort = TodoSort.Newest, val search: String = "", val orderStatus: OrderStatus? = null,
    val view: OrderView = OrderView.ALL,
    val payment:PaymentFilter=PaymentFilter.ANY,val orderedFrom:Long?=null,val orderedUntil:Long?=null,val promisedFrom:Long?=null,val promisedUntil:Long?=null)
data class ListCursor(val value: Long, val id: String,val textValue:String?=null)
data class TodoPage(val items: List<Order>, val next: ListCursor?)
data class HistoryPage(val items: List<HistoryEntry>, val next: ListCursor?)

object ListRules {
    const val PageSize = 10
    fun phoneSearch(value: String): String? {
        if(value.any {it.isLetter()}) return null
        val digits=value.filter {it.isDigit()}
        val national=when {
            digits.startsWith("0092") -> digits.drop(4)
            digits.startsWith("92") -> digits.drop(2)
            digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        return national.takeIf {it.length>=3}
    }
    fun timeBounds(now: Long, zone: ZoneId): Pair<Long, Long> {
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return today.atStartOfDay(zone).toInstant().toEpochMilli() to today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    fun sortKey(todo: Order, sort: TodoSort): Long = when(sort) {
        TodoSort.Deadline -> todo.dueAt ?: Long.MAX_VALUE
        TodoSort.LatestDeadline -> todo.dueAt?.let { -it } ?: Long.MAX_VALUE
        TodoSort.Latest -> -todo.updatedAt
        TodoSort.Newest -> -todo.orderedAt
        TodoSort.Oldest -> todo.orderedAt
        TodoSort.CustomerName -> 0L
        TodoSort.Balance -> -(todo.amounts.totalPaisa-todo.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)
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

data class ServerChange(val id: String, val todo: Order, val accountRevision: Long)

@kotlinx.serialization.Serializable
data class HistoryAnalytics(val trend: List<Int>, val withDeadline: Int, val onTime: Int, val revision: Long,
    val priorityTrends: Map<String, List<Int>> = emptyMap())
