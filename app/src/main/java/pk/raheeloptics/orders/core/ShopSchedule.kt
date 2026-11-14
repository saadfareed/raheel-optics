package pk.raheeloptics.orders.core

import kotlinx.serialization.Serializable
import java.time.*
import java.time.format.DateTimeFormatter

@Serializable data class OpeningInterval(val start:Int=600,val end:Int=1200) {
    fun label()="${LocalTime.of(start/60,start%60).format(DateTimeFormatter.ofPattern("h:mm a",java.util.Locale.US))} – ${LocalTime.of(end/60,end%60).format(DateTimeFormatter.ofPattern("h:mm a",java.util.Locale.US))}${if(end<=start) " (+1 day)" else ""}"
}
@Serializable enum class PaymentAccountType(val label:String) {JAZZCASH("JazzCash"),EASYPAISA("Easypaisa"),BANK("Bank account")}

/** Where customers can send money. Wallets use the mobile number; a bank account needs the bank, title and number or IBAN. */
@Serializable data class PaymentAccount(
    val method:PaymentAccountType=PaymentAccountType.JAZZCASH,val bank:String="",val title:String="",val number:String="",
    /** Listed in pickup messages; switched off accounts are kept but not shared. */
    val shared:Boolean=true
) {
    val name get()=if(method==PaymentAccountType.BANK) bank.trim() else method.label
    fun validate() {
        if(method==PaymentAccountType.BANK) {
            require(bank.trim().length in 2..60) {"Enter the bank name."}
            require(title.trim().length in 2..80) {"Enter the bank account title."}
            require(Regex("[0-9]{6,24}|PK[0-9]{2}[A-Z]{4}[0-9]{16}").matches(number.uppercase().replace(Regex("[\\s-]"),""))) {"Enter a valid account number or IBAN."}
        } else {
            require(PakistanPhone.normalize(number)!=null) {"Enter the ${method.label} mobile number."}
            require(title.length<=80) {"Account title is too long."}
        }
    }
    fun line()=buildString {
        append("• $name ${number.trim()}")
        if(title.isNotBlank()) append(" · ${title.trim()}")
    }
}

@Serializable data class ShopDay(val open:Boolean=true,val intervals:List<OpeningInterval> = listOf(OpeningInterval()))
@Serializable data class ShopSchedule(
    val name:String="Raheel Optics",val phone:String="",val address:String="",
    val latitude:Double?=null,val longitude:Double?=null,
    val days:List<ShopDay> = List(7) {ShopDay(open=it!=6)},
    val exceptions:Map<String,ShopDay> = emptyMap(),
    val paymentAccounts:List<PaymentAccount> = emptyList(),
    /** Where completed-order messages send customers to leave a Google review; blank leaves the request out. */
    val reviewUrl:String=DEFAULT_REVIEW_URL
) {
    companion object {
        /** Raheel Optics, Pakki Thatti: the Google listing with its write-a-review panel open. */
        const val DEFAULT_REVIEW_URL="https://www.google.com/search?q=raheel+optics+pakki+thatti#lrd=0x391903fe02fee3ff:0x49e567e7c3b3887d,3"
    }
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
        require(paymentAccounts.size<=6) {"Add up to six payment accounts."}
        paymentAccounts.forEach {it.validate()}
        require(reviewUrl.isBlank() || Regex("https://\\S{4,490}").matches(reviewUrl.trim())) {"Enter the review link starting with https://"}
    }
    fun effective(date:LocalDate)=exceptions[date.toString()] ?: days[date.dayOfWeek.value-1]
    fun pickupHours(date:LocalDate):String=effective(date).let {if(!it.open) "Closed on ${date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}" else it.intervals.joinToString("; ") {span ->span.label()}}
    /**
     * Customer statement for WhatsApp: every order with a balance (invoice lines), the total due, and a
     * ready-for-delivery notice for orders already marked Ready for pickup. Cancelled orders are ignored.
     */
    fun duesMessage(customerName:String,orders:List<Order>,date:LocalDate=LocalDate.now(ZoneId.of("Asia/Karachi"))):String = buildString {
        fun due(o:Order)=(o.amounts.totalPaisa-o.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)
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
    /**
     * Pickup windows on [date]: the day's opening hours cut into [minutes]-long slots. On today only slots that have
     * not ended by [now] are offered.
     */
    fun pickupSlots(date:LocalDate,now:LocalDateTime?=null,minutes:Int=120):List<OpeningInterval> {
        val day=effective(date)
        if(!day.open) return emptyList()
        val cutoff=now?.takeIf {it.toLocalDate()==date}?.let {it.hour*60+it.minute}
        return day.intervals.sortedBy {it.start}.flatMap {span ->
            val end=span.end+if(span.end<=span.start) 1440 else 0
            generateSequence(span.start) {it+minutes}.takeWhile {it<end}.map {start ->start to minOf(start+minutes,end)}
                .filter {cutoff==null || it.second>cutoff}.map {(a,b)->OpeningInterval(a%1440,b%1440)}.toList()
        }
    }
    private fun due(o:Order)=(o.amounts.totalPaisa-o.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)
    private val contactLine get()=if(phone.isBlank()) "" else "\nQuestions? Call or WhatsApp $phone"

    /** WhatsApp text sent when an order is placed: greeting, what was ordered, the money, when it should be ready and the prescription. */
    fun placedMessage(order:Order):String = buildString {
        val paid=order.payments.sumOf {it.amountPaisa}.coerceAtLeast(0)
        append("Hello ${order.title}, thank you for choosing $name!\n\n")
        append("Your order ${order.orderNumber} has been placed.\n")
        val frame=listOf(order.frame.brand,order.frame.model).filter {it.isNotBlank()}.joinToString(" ")
        val lens=order.lens.material.ifBlank {order.lens.coating}
        listOf("Frame" to frame,"Lens" to lens).filter {it.second.isNotBlank()}.takeIf {it.isNotEmpty()}
            ?.let {append(it.joinToString(" · ") {(k,v)->"$k: $v"}).append("\n")}
        if(order.amounts.totalPaisa>0) {
            append("Total: ${Money.pkr(order.amounts.totalPaisa)}")
            if(paid>0) append(" · Paid: ${Money.pkr(paid)}")
            append(if(due(order)>0) " · Due: ${Money.pkr(due(order))}\n" else " · Fully paid\n")
        }
        order.dueAt?.let {append("Expected ready: ${Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Karachi")).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a",java.util.Locale.US))}\n")}
        prescriptionLines(order.prescription).takeIf {it.isNotEmpty()}?.let {append("\nPrescription\n").append(it.joinToString("\n")).append("\n")}
        append("\nWe will message you as soon as your glasses are ready.")
        append(contactLine)
    }

    /** Only what was entered: distance eyes, near eyes, ADD and PD. */
    private fun prescriptionLines(rx:Prescription)=listOfNotNull(
        rx.distanceRight.summary()?.let {"Right (OD): $it"},rx.distanceLeft.summary()?.let {"Left (OS): $it"},
        rx.nearRight?.summary()?.let {"Near right: $it"},rx.nearLeft?.summary()?.let {"Near left: $it"},
        listOf(rx.addRight,rx.addLeft).takeIf {a ->a.any {!it.isNullOrBlank()}}?.let {(r,l)->"ADD: ${r?.ifBlank {null} ?: "—"} / ${l?.ifBlank {null} ?: "—"}"},
        rx.pd?.takeIf {it.isNotBlank()}?.let {"PD: $it mm"})

    /** WhatsApp text sent when an order is completed: thanks, a request for feedback and, if set, the Google review link. */
    fun completedMessage(order:Order):String = buildString {
        append("Hello ${order.title}, thank you for collecting your glasses (order ${order.orderNumber}) from $name. We hope you love them!\n\n")
        append("How was your experience? Just reply here, we read every message.")
        if(reviewUrl.isNotBlank()) append("\n\nIf you are happy with us, please leave a quick Google review:\n${reviewUrl.trim()}")
        append("\n\nThank you!")
    }

    /** Short WhatsApp text for an order marked Ready: when to collect ([slot] is a shop slot or a custom range), what is due and, if anything is, where to pay. */
    fun readyMessage(order:Order,date:LocalDate,slot:OpeningInterval?):String = buildString {
        val due=due(order)
        append("Hello ${order.title}, your glasses (order ${order.orderNumber}) are ready at $name.\n\n")
        append("Pickup: ${date.format(DateTimeFormatter.ofPattern("EEE d MMM",java.util.Locale.US))}, ${slot?.label() ?: pickupHours(date)}\n")
        append(if(due>0) "Amount due: ${Money.pkr(due)}\n" else "Amount due: none, fully paid\n")
        val accounts=paymentAccounts.filter {it.shared}
        if(due>0 && accounts.isNotEmpty()) {
            append("\nPay in advance to:\n")
            accounts.forEach {append(it.line()).append("\n")}
            append("Please send the payment screenshot here.\n")
        }
        append(contactLine)
        append("\nThank you!")
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
