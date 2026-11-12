package com.daymark.todo.core

import kotlinx.serialization.Serializable
import java.time.*
import java.time.format.DateTimeFormatter

@Serializable data class OpeningInterval(val start:Int=600,val end:Int=1200) {
    fun label()="${LocalTime.of(start/60,start%60).format(DateTimeFormatter.ofPattern("h:mm a",java.util.Locale.US))} – ${LocalTime.of(end/60,end%60).format(DateTimeFormatter.ofPattern("h:mm a",java.util.Locale.US))}${if(end<=start) " (+1 day)" else ""}"
}
@Serializable data class ShopDay(val open:Boolean=true,val intervals:List<OpeningInterval> = listOf(OpeningInterval()))
@Serializable data class ShopSchedule(
    val name:String="Raheel Optics",val phone:String="",val address:String="",
    val latitude:Double?=null,val longitude:Double?=null,
    val days:List<ShopDay> = List(7) {ShopDay(open=it!=6)},
    val exceptions:Map<String,ShopDay> = emptyMap()
) {
    fun validate() {
        require(name.trim().length in 2..120) {"Enter the shop name."}
        require(phone.isBlank() || PakistanPhone.normalize(phone)!=null) {"Enter a Pakistani mobile number."}
        require(days.size==7) {"Set all seven days."}
        val week=10080
        val segments=mutableListOf<Pair<Int,Int>>()
        fun checkDay(day:ShopDay) {
            if(!day.open) return
            require(day.intervals.size in 1..4) {"Open days need one to four intervals."}
            day.intervals.forEach {require(it.start in 0..1439 && it.end in 0..1439 && it.start!=it.end) {"Choose different opening and closing times."}}
            val sorted=day.intervals.map {it.start to (it.end+if(it.end<it.start) 1440 else 0)}.sortedBy {it.first}
            require(sorted.zipWithNext().all {it.first.second<=it.second.first}) {"Opening intervals overlap."}
        }
        days.forEachIndexed {index,day ->checkDay(day);if(day.open) day.intervals.forEach {
            val start=index*1440+it.start;val end=index*1440+it.end+if(it.end<it.start) 1440 else 0
            segments+=start to minOf(end,week)
            if(end>week) segments+=0 to (end-week)
        }}
        require(segments.sortedBy {it.first}.zipWithNext().all {it.first.second<=it.second.first}) {"Opening intervals overlap across days."}
        exceptions.forEach {(date,day)->LocalDate.parse(date);checkDay(day)}
    }
    fun effective(date:LocalDate)=exceptions[date.toString()] ?: days[date.dayOfWeek.value-1]
    fun pickupHours(date:LocalDate):String=effective(date).let {if(!it.open) "Closed on ${date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}" else it.intervals.joinToString("; ") {span ->span.label()}}
    /**
     * Customer statement for WhatsApp: every order with a balance (invoice lines), the total due, and a
     * ready-for-delivery notice for orders already marked Ready for pickup. Cancelled orders are ignored.
     */
    fun duesMessage(customerName:String,orders:List<Todo>,date:LocalDate=LocalDate.now(ZoneId.of("Asia/Karachi"))):String = buildString {
        fun due(o:Todo)=(o.amounts.totalPaisa-o.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)
        val open=orders.filter {it.orderStatus!=OrderStatus.CANCEL}
        val owing=open.filter {due(it)>0}
        val ready=open.filter {it.orderStatus==OrderStatus.READY_FOR_PICKUP}
        append("Hello $customerName, this is $name.\n")
        if(owing.isNotEmpty()) {
            append("\nOutstanding dues:\n")
            owing.forEach {append("• ${it.orderNumber}: total PKR ${Money.format(it.amounts.totalPaisa)}, paid PKR ${Money.format(it.payments.sumOf {p ->p.amountPaisa}.coerceAtLeast(0))}, due PKR ${Money.format(due(it))}\n")}
            append("Total outstanding: PKR ${Money.format(owing.sumOf {due(it)})}\n")
        }
        if(ready.isNotEmpty()) {
            append("\nReady for delivery: ${ready.joinToString(", ") {it.orderNumber}}. Your glasses can be collected from $name.\n")
            append("Pickup hours: ${pickupHours(date)}.\n")
        }
        if(owing.isEmpty() && ready.isEmpty()) append("\nYou have no outstanding dues. Thank you for choosing $name.\n")
        append("\n${if(phone.isBlank()) "Please contact the shop if you need assistance." else "For any questions contact us on $phone."} Thank you.")
    }
    fun readyMessage(order:Todo,pickupOverride:String="",showBalance:Boolean=true,date:LocalDate=LocalDate.now(ZoneId.of("Asia/Karachi"))):String = buildString {
        append("Hello ${order.title}, your glasses for order ${order.orderNumber} are ready for pickup from $name.\n")
        append("Pickup hours: ${pickupOverride.ifBlank {pickupHours(date)}}.\n")
        if(showBalance) append("Amount due: PKR ${Money.format((order.amounts.totalPaisa-order.payments.sumOf {it.amountPaisa}).coerceAtLeast(0))}.\n")
        append("If the shop is closed or you need assistance, ${if(phone.isBlank()) "please contact the shop" else "contact us at $phone"}. Thank you.")
    }
}

object Money {
    fun parse(value:String):Long {
        if(value.isBlank()) return 0
        val amount=value.trim().toBigDecimalOrNull() ?: error("Enter a valid PKR amount.")
        require(amount.signum()>=0) {"Amounts cannot be negative."}
        return amount.movePointRight(2).longValueExact()
    }
    /** Display form with thousands separators; whole rupees drop the ".00". */
    fun pkr(paisa:Long):String="PKR "+if(paisa%100==0L) "%,d".format(paisa/100) else "%,.2f".format(java.math.BigDecimal.valueOf(paisa,2))
    fun format(paisa:Long)=java.math.BigDecimal.valueOf(paisa,2).toPlainString()
    fun payments(previous:List<Payment>,paid:Long,now:Long):List<Payment> {
        val old=previous.sumOf {it.amountPaisa}
        require(paid>=old) {"Paid amount cannot be reduced here. Record a refund separately."}
        return if(paid==old) previous else previous+Payment(java.util.UUID.randomUUID().toString(),paid-old,receivedAt=now)
    }
}

object WhatsAppLink {
    /** wa.me link for a Pakistani mobile (digits only, with country code); null if the number is invalid. */
    fun url(mobile:String,message:String):String? {
        val digits=(PakistanPhone.normalize(mobile) ?: return null).filter(Char::isDigit)
        return "https://wa.me/$digits?text=${java.net.URLEncoder.encode(message,"UTF-8").replace("+","%20")}"
    }
}
