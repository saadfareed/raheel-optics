package com.daymark.todo.ui

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
import com.daymark.todo.core.*
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
@Composable internal fun OrderDetailScreen(order:Todo,busy:Boolean,close:()->Unit,edit:()->Unit,
    change:(Todo)->Unit,complete:()->Unit,cancel:()->Unit,reorder:()->Unit) {
    var payment by remember {mutableStateOf("")}
    var refund by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var sharing by remember {mutableStateOf(false)}
    val paid=order.payments.sumOf {it.amountPaisa}
    val balance=(order.amounts.totalPaisa-paid).coerceAtLeast(0)
    val zone=java.time.ZoneId.of("Asia/Karachi")
    val dateTime=java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd hh-mm a",java.util.Locale.US)
    fun at(millis:Long)=java.time.Instant.ofEpochMilli(millis).atZone(zone).format(dateTime)
    val statusTint=when(order.orderStatus) {OrderStatus.ACTIVE -> MaterialTheme.colorScheme.primary;OrderStatus.READY_FOR_PICKUP -> androidx.compose.ui.graphics.Color(0xFFB77A12);OrderStatus.COMPLETE -> MaterialTheme.colorScheme.secondary;OrderStatus.CANCEL -> MaterialTheme.colorScheme.onSurfaceVariant}
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    fun eyeLine(eye:EyePrescription)=listOfNotNull("SPH ${eye.sphere.ifBlank {"—"}}",eye.cylinder?.takeIf {it.isNotBlank()}?.let {"CYL $it"},eye.axis?.let {"AXIS $it"},eye.prism?.takeIf {it.isNotBlank()}?.let {"PRISM $it"},eye.base?.let {"BASE ${it.name}"}).joinToString("  ·  ")
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
            item {FlowRow(horizontalArrangement=Arrangement.spacedBy(Ui.Tight),verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                if(!order.orderStatus.isTerminal) OutlinedButton(onClick=edit,enabled=!busy,shape=Ui.Field) {Text("Edit order")}
                if(order.orderStatus==OrderStatus.ACTIVE) Button(enabled=!busy,shape=Ui.Field,onClick={change(order.copy(orderStatus=OrderStatus.READY_FOR_PICKUP,updatedAt=System.currentTimeMillis()))}) {Text("Ready for pickup")}
                if(order.orderStatus==OrderStatus.READY_FOR_PICKUP) {Button(onClick=complete,enabled=!busy,shape=Ui.Field) {Text("Complete")};OutlinedButton(onClick={sharing=true},shape=Ui.Field) {Icon(DaymarkIcons.Chat,null,Modifier.size(18.dp),tint=Ui.WhatsApp);Spacer(Modifier.width(6.dp));Text("WhatsApp")}}
                OutlinedButton(onClick=reorder,enabled=!busy,shape=Ui.Field) {Text("Repeat order")}
                if(order.orderStatus==OrderStatus.ACTIVE) TextButton(onClick=cancel,enabled=!busy) {Text("Cancel order",color=MaterialTheme.colorScheme.error)}
            }}
        }
    }
    if(sharing) PickupMessageDialog(order,{sharing=false})
}

@Composable internal fun PickupMessageDialog(order:Todo,close:()->Unit) {
    val context=LocalContext.current
    val shop=remember {ShopPreferences.load(context)}
    var window by remember {mutableStateOf("")}
    var balance by remember {mutableStateOf(true)}
    var message by remember(window,balance) {mutableStateOf(shop.readyMessage(order,window,balance))}
    var error by remember {mutableStateOf<String?>(null)}
    FullScreenForm("Pickup message",close,"Open WhatsApp",save={try {
        val mobile=order.customerMobile.ifBlank {order.normalizedMobile}
        WhatsAppShare.send(context,mobile,message,InvoiceBuilder.build(shop,order.title,mobile,listOf(order)));close()
    } catch(e:Exception) {error=e.message ?: "WhatsApp could not be opened."}}) {padding ->
        LazyColumn(Modifier.padding(padding),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            item {AppTextField(window,{window=it.take(80)},"One-time pickup window · optional")}
            item {Row {Checkbox(balance,{balance=it});Text("Include outstanding balance")}}
            item {AppTextField(message,{message=it.take(1500)},"Review message",singleLine=false,minLines=6,supporting="${message.length}/1500")}
            error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    }
}
