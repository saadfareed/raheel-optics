package pk.raheeloptics.orders.ui

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun FramePreview(uri:String?,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,uri) {
        value=withContext(Dispatchers.IO) {runCatching {uri?.let {context.contentResolver.openInputStream(android.net.Uri.parse(it))?.use {stream ->android.graphics.BitmapFactory.decodeStream(stream,null,android.graphics.BitmapFactory.Options().apply {inSampleSize=4})}}}.getOrNull()}
    }
    if(bitmap!=null) Image(bitmap!!.asImageBitmap(),"Glasses frame",modifier)
    else if(uri!=null) Text("Frame photo unavailable on this device",style=MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OrderDetailScreen(order:Order,busy:Boolean,close:()->Unit,edit:()->Unit,
    change:(Order)->Unit,complete:()->Unit,cancel:()->Unit) {
    var payment by remember {mutableStateOf("")}
    var refund by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var sharing by remember {mutableStateOf<CustomerMessage?>(null)}
    val paid=order.payments.sumOf {it.amountPaisa}
    val balance=(order.amounts.totalPaisa-paid).coerceAtLeast(0)
    val zone=java.time.ZoneId.of("Asia/Karachi")
    val dateTime=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh-mm a",java.util.Locale.US)
    fun at(millis:Long)=java.time.Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)
    val statusTint=when(order.orderStatus) {OrderStatus.ACTIVE -> MaterialTheme.colorScheme.primary;OrderStatus.READY_FOR_PICKUP -> androidx.compose.ui.graphics.Color(0xFFB77A12);OrderStatus.COMPLETE -> MaterialTheme.colorScheme.secondary;OrderStatus.CANCEL -> MaterialTheme.colorScheme.onSurfaceVariant}
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    fun eyeLine(eye:EyePrescription)=eye.summary("  ·  ") ?: "—"
    FullScreenForm("Order ${order.orderNumber}",close,busy=busy) {padding ->
        LazyColumn(modifier=Modifier.padding(padding),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            item {AppCard {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(order.title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    StatusPill(order.orderStatus.label,statusTint)
                }
                Text(order.customerMobile,color=muted)
                KeyValueRow("Ordered",at(order.orderedAt))
                order.dueAt?.let {KeyValueRow("Promised",at(it))}
                order.reminderAt?.let {KeyValueRow("Reminder",at(it))}
            }}
            if(order.frame.localImageUri!=null) item {AppCard {FramePreview(order.frame.localImageUri,Modifier.fillMaxWidth().heightIn(max=220.dp))}}
            item {AppCard {
                SectionLabel("Prescription")
                KeyValueRow("OD · Right",eyeLine(order.prescription.distanceRight));KeyValueRow("OS · Left",eyeLine(order.prescription.distanceLeft))
                order.prescription.nearRight?.let {KeyValueRow("Near OD",eyeLine(it))};order.prescription.nearLeft?.let {KeyValueRow("Near OS",eyeLine(it))}
                KeyValueRow("ADD OD / OS","${order.prescription.addRight ?: "—"} / ${order.prescription.addLeft ?: "—"}")
                KeyValueRow("PD",order.prescription.pd?.let {"$it mm"} ?: "—")
            }}
            item {AppCard {
                SectionLabel("Frame & lenses")
                KeyValueRow("Frame",listOf(order.frame.brand,order.frame.model).filter {it.isNotBlank()}.joinToString(" ").ifBlank {"—"})
                KeyValueRow("Lens",order.lens.material.ifBlank {order.lens.coating}.ifBlank {"—"})
                if(order.notes.isNotBlank()) {HorizontalDivider();Text(order.notes,style=MaterialTheme.typography.bodyMedium)}
            }}
            item {AppCard {
                SectionLabel("Payment")
                KeyValueRow("Order total","${Money.pkr(order.amounts.totalPaisa)}")
                KeyValueRow("Paid","${Money.pkr(paid)}",emphasis=MaterialTheme.colorScheme.secondary)
                KeyValueRow("Balance","${Money.pkr(balance)}",emphasis=if(balance>0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                if(order.payments.isNotEmpty()) {
                    HorizontalDivider()
                    order.payments.forEach {entry ->KeyValueRow("${if(entry.amountPaisa<0) "Refund" else "Payment"} · ${java.time.Instant.ofEpochMilli(entry.receivedAt).atZone(zone).toLocalDate()}","${if(entry.amountPaisa<0) "−" else ""}${Money.pkr(kotlin.math.abs(entry.amountPaisa))}")}
                }
                if(order.orderStatus!=OrderStatus.CANCEL) {
                    HorizontalDivider()
                    val amountError=payment.takeIf {it.isNotBlank()}?.let {FieldRules.amount(it,"Amount") ?: Money.parse(it).let {amount ->when {
                        amount<=0 -> "Enter an amount greater than zero."
                        refund && amount>paid -> "Refund can't be more than the paid amount (${Money.pkr(paid)})."
                        !refund && amount>balance -> "Payment can't be more than the balance (${Money.pkr(balance)})."
                        else -> null
                    }}}
                    AppTextField(payment,{payment=FieldRules.wholeInput(it);error=null},if(refund) "Refund amount · PKR" else "Payment amount · PKR",keyboard=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Number),error=amountError!=null,supporting=amountError)
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(refund,{refund=it});Text("Record as refund")}
                    Button(enabled=!busy,shape=Ui.Field,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={try {
                        FieldRules.amount(payment,"Amount")?.let {throw IllegalArgumentException(it)}
                        val amount=Money.parse(payment);require(amount>0) {"Enter an amount greater than zero."}
                        require(if(refund) amount<=paid else amount<=order.amounts.totalPaisa-paid) {"Amount exceeds the ${if(refund) "paid amount" else "balance"}."}
                        change(order.copy(payments=order.payments+Payment(java.util.UUID.randomUUID().toString(),if(refund) -amount else amount),updatedAt=System.currentTimeMillis()));payment=""
                    } catch(e:Exception) {error=e.message}}) {Text("Record ${if(refund) "refund" else "payment"}")}
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                }
            }}
            // One full-width next step, then Edit and the WhatsApp message side by side, then Cancel.
            val message=when(order.orderStatus) {
                OrderStatus.ACTIVE -> CustomerMessage.PLACED to "WhatsApp"
                OrderStatus.READY_FOR_PICKUP -> CustomerMessage.READY to "WhatsApp"
                OrderStatus.COMPLETE -> CustomerMessage.COMPLETED to "Ask for review"
                OrderStatus.CANCEL -> null
            }
            if(message!=null) item {Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                val tall=Modifier.heightIn(min=48.dp)
                when(order.orderStatus) {
                    OrderStatus.ACTIVE -> Button(enabled=!busy,shape=Ui.Field,modifier=tall.fillMaxWidth(),onClick={change(order.copy(orderStatus=OrderStatus.READY_FOR_PICKUP,updatedAt=System.currentTimeMillis()))}) {Text("Ready for pickup")}
                    OrderStatus.READY_FOR_PICKUP -> Button(onClick=complete,enabled=!busy,shape=Ui.Field,modifier=tall.fillMaxWidth()) {Text("Complete")}
                    else -> {}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                    if(!order.orderStatus.isTerminal) OutlinedButton(onClick=edit,enabled=!busy,shape=Ui.Field,modifier=tall.weight(1f)) {Icon(AppIcons.Edit,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Edit order")}
                    OutlinedButton(onClick={sharing=message.first},shape=Ui.Field,modifier=tall.weight(1f)) {Icon(AppIcons.Chat,null,Modifier.size(18.dp),tint=Ui.WhatsApp);Spacer(Modifier.width(6.dp));Text(message.second)}
                }
                if(order.orderStatus==OrderStatus.ACTIVE) TextButton(onClick=cancel,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Cancel order",color=MaterialTheme.colorScheme.error)}
            }}
        }
    }
    sharing?.let {CustomerMessageDialog(order,it) {sharing=null}}
}

/** The WhatsApp message for each stage of an order. */
internal enum class CustomerMessage(val title:String) {PLACED("Order confirmation"),READY("Pickup message"),COMPLETED("Feedback request")}

/**
 * Editable WhatsApp message for [order] at [kind]'s stage. The pickup message also picks a date and time slot.
 * Placed and pickup messages carry the invoice picture; the feedback request is text only. Closing sends nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CustomerMessageDialog(order:Order,kind:CustomerMessage,close:()->Unit) {
    val context=LocalContext.current
    val shop=remember {ShopPreferences.load(context)}
    val now=remember {java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Karachi"))}
    // Start on the first day (within two weeks) that still has a pickup slot.
    var date by remember {mutableStateOf(generateSequence(now.toLocalDate()) {it.plusDays(1)}.take(14).firstOrNull {shop.pickupSlots(it,now).isNotEmpty()} ?: now.toLocalDate())}
    val slots=remember(date) {shop.pickupSlots(date,now)}
    // A custom range (set by the shop, any day) replaces the shop's slots.
    var custom by remember {mutableStateOf<OpeningInterval?>(null)}
    var slot by remember(date) {mutableStateOf(if(custom==null) slots.firstOrNull() else null)}
    var message by remember(date,slot,custom) {mutableStateOf(when(kind) {
        CustomerMessage.PLACED -> shop.placedMessage(order)
        CustomerMessage.READY -> shop.readyMessage(order,date,custom ?: slot)
        CustomerMessage.COMPLETED -> shop.completedMessage(order)
    })}
    var error by remember {mutableStateOf<String?>(null)}
    val due=order.amounts.totalPaisa-order.payments.sumOf {it.amountPaisa}
    FullScreenForm(kind.title,close,"Open WhatsApp",save={try {
        check(kind!=CustomerMessage.READY || slot!=null || custom!=null) {"Choose a time slot or set a custom range."}
        val mobile=order.customerMobile.ifBlank {order.normalizedMobile}
        WhatsAppShare.send(context,mobile,message,if(kind==CustomerMessage.COMPLETED) null else InvoiceBuilder.build(shop,order.title,mobile,listOf(order)));close()
    } catch(e:Exception) {error=e.message ?: "WhatsApp could not be opened."}}) {padding ->
        LazyColumn(Modifier.padding(padding),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            if(kind==CustomerMessage.READY) {
                item {OutlinedButton(onClick={
                    android.app.DatePickerDialog(context,{_,y,m,d ->date=java.time.LocalDate.of(y,m+1,d);error=null},date.year,date.monthValue-1,date.dayOfMonth).apply {
                        datePicker.minDate=now.toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli()
                    }.show()
                },shape=Ui.Field,modifier=Modifier.fillMaxWidth()) {Icon(AppIcons.Event,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp))
                    Text("Pickup date · "+date.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy",java.util.Locale.US)))}}
                item {Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text("Time",style=MaterialTheme.typography.labelLarge)
                    if(slots.isEmpty() && custom==null) Text("The shop is closed or has no slots left on this day. Choose another date or set a custom range.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        slots.forEach {s ->FilterChip(custom==null && s==slot,{slot=s;custom=null;error=null},{Text(s.label())})}
                        FilterChip(custom!=null,{
                            // Pick the start, then the end (defaults to two hours later); the end must come after the start.
                            val first=custom ?: OpeningInterval(((now.hour+1)%24)*60,((now.hour+3)%24)*60)
                            android.app.TimePickerDialog(context,{_,h,m ->
                                val start=h*60+m;val suggested=minOf(start+120,1439)
                                android.app.TimePickerDialog(context,{_,eh,em ->
                                    val end=eh*60+em
                                    if(end<=start) error="The end time must be after the start time." else {custom=OpeningInterval(start,end);slot=null;error=null}
                                },(if(custom!=null && first.end>start) first.end else suggested)/60,(if(custom!=null && first.end>start) first.end else suggested)%60,false).apply {setTitle("Pickup until")}.show()
                            },first.start/60,first.start%60,false).apply {setTitle("Pickup from")}.show()
                        },{Text(custom?.let {"Custom · "+it.label()} ?: "Custom range")},
                            leadingIcon={Icon(AppIcons.Schedule,null,Modifier.size(18.dp))})
                    }
                }}
                if(due>0 && shop.paymentAccounts.none {it.shared}) item {Text("Add JazzCash, Easypaisa or bank details in Settings → Shop details so customers can pay in advance.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            if(kind==CustomerMessage.COMPLETED && shop.reviewUrl.isBlank()) item {Text("Add your Google review link in Settings → Shop details to ask for reviews.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            item {AppTextField(message,{message=it.take(1500)},"Message",singleLine=false,minLines=6,supporting="${message.length}/1500")}
            error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    }
}
