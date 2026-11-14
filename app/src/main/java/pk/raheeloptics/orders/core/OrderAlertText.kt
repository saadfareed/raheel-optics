package pk.raheeloptics.orders.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notification wording for order reminders: who, which order, when it was promised, whether it is ready and what is owed. */
data class OrderAlertText(val title: String, val text: String, val details: String, val phone: String?)

object OrderAlerts {
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val day = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun text(order: Order, kind: AlertKind, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): OrderAlertText {
        val name = order.title.trim().ifEmpty { "Customer" }
        val number = order.orderNumber.trim().takeIf { it.isNotEmpty() }?.let { "Order $it" } ?: "Their order"
        val ready = order.orderStatus == OrderStatus.READY_FOR_PICKUP
        val balance = (order.amounts.totalPaisa - order.payments.sumOf { it.amountPaisa }).coerceAtLeast(0)
        val money = if (balance > 0) "${Money.pkr(balance)} due" else "Fully paid"
        val promised = order.dueAt?.let { moment(it, now, zone) }
        val overdue = order.dueAt != null && order.dueAt <= now
        val title = when {
            ready && overdue -> "$name hasn't collected their glasses"
            ready -> "$name's glasses are ready"
            overdue -> "$name's glasses are due now"
            promised != null -> "$name's glasses are due $promised"
            else -> "Follow up with $name"
        }
        val state = if (ready) "Ready for pickup" else "Not ready yet"
        val phone = order.customerMobile.trim().takeIf { it.isNotEmpty() }
            ?: order.normalizedMobile.takeIf { it.isNotEmpty() }?.let(PakistanPhone::display)
        val next = when {
            ready && balance > 0 -> "Call to arrange pickup and collect ${Money.pkr(balance)}."
            ready -> "Call to arrange pickup."
            overdue -> "Check with the lab, or call to agree a new time."
            kind == AlertKind.Reminder -> "Make sure it is ready on time."
            else -> null
        }
        val details = listOfNotNull(
            "$number · $state",
            promised?.let { "Promised: $it" },
            if (balance > 0) "Balance: ${Money.pkr(balance)}" else "Fully paid",
            phone?.let { "Phone: $it" },
            next,
        ).joinToString("\n")
        return OrderAlertText(title, "$number · $state · $money", details, phone)
    }

    /** "today 4:00 PM", "tomorrow 11:30 AM", "yesterday 5:00 PM" or "Fri 13 Nov, 4:00 PM". */
    fun moment(at: Long, now: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val date: LocalDate = time.toLocalDate()
        val prefix = when (date) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            today.minusDays(1) -> "yesterday"
            else -> null
        }
        return if (prefix != null) "$prefix ${clock.format(time)}" else "${day.format(time)}, ${clock.format(time)}"
    }
}
