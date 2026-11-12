package pk.raheeloptics.orders.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shop-time date text used on invoices and order details: `2026-11-12 08-09 AM`. */
object DateText {
    private val zone = ZoneId.of("Asia/Karachi")
    private val full = DateTimeFormatter.ofPattern("yyyy-MM-dd hh-mm a", Locale.US)
    private val day = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
    fun dateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(full)
    fun date(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone).format(day)
}

data class InvoiceLine(val label: String, val value: String, val strong: Boolean = false, val alert: Boolean = false)
data class InvoiceSection(val title: String, val lines: List<InvoiceLine>)
data class Invoice(
    val shopName: String, val shopContact: String, val shopAddress: String,
    val customerName: String, val customerMobile: String, val issued: String,
    val sections: List<InvoiceSection>, val totals: List<InvoiceLine>, val footer: String,
)

/** Builds the invoice that is drawn as a picture and sent on WhatsApp. Cancelled orders are never invoiced. */
object InvoiceBuilder {
    private fun due(o: Order) = (o.amounts.totalPaisa - o.payments.sumOf { it.amountPaisa }).coerceAtLeast(0)
    private fun paid(o: Order) = o.payments.sumOf { it.amountPaisa }.coerceAtLeast(0)
    private fun method(m: PaymentMethod) = m.name.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)

    fun build(shop: ShopSchedule, customerName: String, customerMobile: String, orders: List<Order>, now: Long = System.currentTimeMillis()): Invoice {
        val open = orders.filter { it.orderStatus != OrderStatus.CANCEL }
        val sections = open.map { o ->
            val frame = listOf(o.frame.brand, o.frame.model).filter { it.isNotBlank() }.joinToString(" ")
            val lens = o.lens.material.ifBlank { o.lens.coating }
            InvoiceSection("Order ${o.orderNumber} · ${o.orderStatus.label}", buildList {
                if (frame.isNotBlank()) add(InvoiceLine("Frame", frame))
                if (lens.isNotBlank()) add(InvoiceLine("Lens", lens))
                add(InvoiceLine("Order total", Money.pkr(o.amounts.totalPaisa)))
                add(InvoiceLine("Paid", Money.pkr(paid(o))))
                add(InvoiceLine("Outstanding", Money.pkr(due(o)), strong = true, alert = due(o) > 0))
                o.dueAt?.let { add(InvoiceLine(if (o.orderStatus == OrderStatus.READY_FOR_PICKUP) "Ready since" else "Time to receive", DateText.dateTime(it))) }
                o.payments.forEach { add(InvoiceLine("${if (it.amountPaisa < 0) "Refund" else "Payment"} · ${method(it.method)} · ${DateText.date(it.receivedAt)}", Money.pkr(kotlin.math.abs(it.amountPaisa)))) }
            })
        }
        val totals = listOf(
            InvoiceLine("Total amount", Money.pkr(open.sumOf { it.amounts.totalPaisa })),
            InvoiceLine("Total paid", Money.pkr(open.sumOf { paid(it) })),
            InvoiceLine("Outstanding dues", Money.pkr(open.sumOf { due(it) }), strong = true, alert = open.sumOf { due(it) } > 0),
        )
        return Invoice(shop.name, shop.phone, shop.address, customerName, customerMobile, DateText.dateTime(now), sections, totals,
            if (shop.phone.isBlank()) "Thank you for choosing ${shop.name}." else "Questions? WhatsApp ${shop.phone}. Thank you for choosing ${shop.name}.")
    }
}
