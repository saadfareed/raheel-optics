package pk.raheeloptics.orders.core

import java.time.*

enum class OrderAnalyticsPeriod(val label:String) {TODAY("Today"),WEEK("This week"),MONTH("This month"),QUARTER("This quarter"),CUSTOM("Custom range")}
data class OrderAnalyticsQuery(val period:OrderAnalyticsPeriod=OrderAnalyticsPeriod.MONTH,val from:LocalDate=LocalDate.now(ZoneId.of("Asia/Karachi")),val through:LocalDate=from) {
    fun bounds(today:LocalDate=LocalDate.now(ZoneId.of("Asia/Karachi"))):Pair<Long,Long> {
        val start=when(period) {OrderAnalyticsPeriod.TODAY->today;OrderAnalyticsPeriod.WEEK->today.minusDays((today.dayOfWeek.value-1).toLong());OrderAnalyticsPeriod.MONTH->today.withDayOfMonth(1);OrderAnalyticsPeriod.QUARTER->today.withMonth((today.monthValue-1)/3*3+1).withDayOfMonth(1);OrderAnalyticsPeriod.CUSTOM->from}
        val last=if(period==OrderAnalyticsPeriod.CUSTOM) through else today
        require(!last.isBefore(start)) {"The end date must be on or after the start date."}
        val zone=ZoneId.of("Asia/Karachi")
        return start.atStartOfDay(zone).toInstant().toEpochMilli() to last.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}
data class OrderAnalyticsSummary(val sales:Long=0,val collected:Long=0,val outstanding:Long=0,val orders:Int=0,val statuses:Map<OrderStatus,Int> = emptyMap(),val overdue:Int=0) {
    val average:Long get()=if(orders==0) 0 else sales/orders
}

class OrderAnalyticsAccumulator(private val from:Long,private val until:Long,private val now:Long=System.currentTimeMillis()) {
    private var sales=0L;private var collected=0L;private var outstanding=0L;private var orders=0;private var overdue=0
    private val counts=mutableMapOf<OrderStatus,Int>()
    fun add(order:Order) {
        counts[order.orderStatus]=(counts[order.orderStatus] ?: 0)+1
        val paid=order.payments.sumOf {it.amountPaisa}
        if(order.orderStatus!=OrderStatus.CANCEL) {
            outstanding=Math.addExact(outstanding,(order.amounts.totalPaisa-paid).coerceAtLeast(0))
            if(order.orderedAt>=from && order.orderedAt<until) {sales=Math.addExact(sales,order.amounts.totalPaisa);orders++}
        }
        order.payments.filter {it.receivedAt>=from && it.receivedAt<until}.forEach {collected=Math.addExact(collected,it.amountPaisa)}
        if(!order.orderStatus.isTerminal && order.dueAt?.let {it<now}==true) overdue++
    }
    fun result()=OrderAnalyticsSummary(sales,collected,outstanding,orders,counts.toMap(),overdue)
}
