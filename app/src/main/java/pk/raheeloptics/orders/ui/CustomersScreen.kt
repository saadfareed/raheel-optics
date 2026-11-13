package pk.raheeloptics.orders.ui

import androidx.core.content.edit
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.core.*
import java.time.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun CustomersScreen(state:UiState,model:OrdersViewModel,newOrder:(Order)->Unit,openOrder:(Order)->Unit) {
    val query=state.customerQuery
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var sortMenu by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Customer?>(null)}
    var viewing by remember {mutableStateOf<CustomerDirectoryRow?>(null)}
    val list=rememberLazyListState()
    LaunchedEffect(query.search,query.sort) {list.scrollToItem(0)}
    fun whatsApp(row:CustomerDirectoryRow) {scope.launch {
        val customer=row.latestOrder
        try {
            val orders=model.ordersForCustomer(row)
            val shop=ShopPreferences.load(context)
            val mobile=customer.customerMobile.ifBlank {customer.normalizedMobile}
            // The picture lists orders that still owe money or are ready for pickup; settled history stays out of the statement.
            val statement=orders.filter {it.orderStatus==OrderStatus.READY_FOR_PICKUP || (it.orderStatus!=OrderStatus.CANCEL && it.amounts.totalPaisa>it.payments.sumOf {p ->p.amountPaisa})}
            WhatsAppShare.send(context,mobile,shop.duesMessage(customer.title,orders),InvoiceBuilder.build(shop,customer.title,mobile,statement.ifEmpty {orders}))
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {android.widget.Toast.makeText(context,e.message ?: "Couldn't open WhatsApp.",android.widget.Toast.LENGTH_LONG).show()}
    }}
    LazyColumn(state=list,contentPadding=PaddingValues(start=Ui.Gutter,end=Ui.Gutter,top=Ui.Tight,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
        item {DebouncedSearchField(query.search,"Search name or mobile",80,{model.updateCustomers(query.copy(search=it,page=1,loaded=1))})}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
            Text("Sorted by ${query.sort.label.lowercase()}",Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=Muted)
            Box {
                TipIconButton(AppIcons.Tune,"Sort customers",{sortMenu=true})
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    CustomerSort.entries.forEach {sort ->DropdownMenuItem(text={Text(sort.label,fontWeight=if(sort==query.sort) FontWeight.Bold else null)},onClick={model.updateCustomers(query.copy(sort=sort,page=1,loaded=1));sortMenu=false})}
                }
            }
        }}
        if(!state.customersLoaded) item {LogoLoader(Modifier.fillMaxWidth().padding(vertical=48.dp),"Loading customers")}
        else if(state.customers.isEmpty()) item {
            EmptyState("No customers found",if(query.search.isNotBlank()) "Try another name or mobile number." else "Your first saved order adds a customer here.",AppIcons.Person)
        }
        items(state.customers,key={it.customer?.id ?: it.latestOrder.normalizedMobile}) {row ->
            val customer=row.latestOrder
            val conflict=row.customer?.id in state.customerConflicts
            AppCard(onClick={model.dismissMessage();viewing=row}) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(Ui.Item)) {
                    Box(Modifier.size(44.dp).background(Violet.copy(alpha=.12f),CircleShape),contentAlignment=Alignment.Center) {
                        Text(customer.title.trim().take(1).uppercase().ifBlank {"?"},color=Violet,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(customer.title,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(customer.customerMobile,style=MaterialTheme.typography.bodyMedium,color=Muted,maxLines=1)
                        Text(if(row.orderCount==0) "No orders yet" else "${row.orderCount} order${if(row.orderCount==1) "" else "s"} · ${shortDate(row.lastOrderAt)}",style=MaterialTheme.typography.labelSmall,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text("Balance",style=MaterialTheme.typography.labelSmall,color=Muted)
                        Text(if(row.balancePaisa>0) Money.pkr(row.balancePaisa) else "Clear",style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,color=if(row.balancePaisa>0) Coral else Mint)
                    }
                    TipIconButton(AppIcons.Chat,"Send dues on WhatsApp",{whatsApp(row)},Modifier.size(48.dp).testTag("customer-whatsapp"),tint=Ui.WhatsApp)
                }
                if(conflict) Text("Customer changes need review",color=Coral,style=MaterialTheme.typography.labelMedium)
            }
        }
        item {LoadMoreFooter(state.customersMore,state.busy,"Load more customers") {model.updateCustomers(query.copy(loaded=query.loaded+1))}}
    }
    viewing?.let {row ->CustomerOrdersDialog(row,model,{viewing=null},openOrder={viewing=null;openOrder(it)},newOrder={viewing=null;newOrder(row.latestOrder)},
        edit={row.customer?.let {c ->viewing=null;model.dismissMessage();editing=c}},whatsApp={whatsApp(row)})}
    editing?.let {customer ->CustomerDetailsSheet(customer,state.busy,state.message,{editing=null;model.dismissMessage()},
        resolveConflict=if(customer.id in state.customerConflicts) {keep ->model.resolveCustomerConflict(customer.id,keep) {editing=null}} else null
    ) {updated ->model.saveCustomer(updated) {editing=null}}}
}

@Composable
internal fun CustomerOrdersDialog(row:CustomerDirectoryRow,model:OrdersViewModel,dismiss:()->Unit,openOrder:(Order)->Unit,newOrder:()->Unit,edit:()->Unit,whatsApp:()->Unit) {
    val customer=row.latestOrder
    val orders by produceState<List<Order>?>(null,row.latestOrder.id,row.orderCount) {value=runCatching {model.ordersForCustomer(row)}.getOrDefault(emptyList())}
    AppDialog(customer.title,dismiss,"New order",newOrder) {
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            KeyValueRow("Mobile",customer.customerMobile)
            KeyValueRow("Orders",row.orderCount.toString())
            KeyValueRow("Balance due",if(row.balancePaisa>0) "${Money.pkr(row.balancePaisa)}" else "Clear",emphasis=if(row.balancePaisa>0) Coral else Mint)
            Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                OutlinedButton(onClick=whatsApp,shape=Ui.Field,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.weight(1f)) {Icon(AppIcons.Chat,null,Modifier.size(18.dp),tint=Ui.WhatsApp);Spacer(Modifier.width(6.dp));Text("WhatsApp",maxLines=1,softWrap=false)}
                if(row.customer!=null) OutlinedButton(onClick=edit,shape=Ui.Field,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.weight(1f)) {Text("Edit details",maxLines=1,softWrap=false)}
            }
            SectionLabel("Orders")
            when {
                orders==null -> LogoLoader(Modifier.fillMaxWidth().padding(Ui.Item),"Loading orders",48.dp)
                orders!!.isEmpty() -> Text("No orders for this customer yet.",style=MaterialTheme.typography.bodySmall,color=Muted)
                else -> orders!!.forEach {order ->
                    val due=(order.amounts.totalPaisa-order.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)
                    Surface(onClick={openOrder(order)},shape=Ui.Field,color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.5f),modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Ui.Item),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(order.orderNumber,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text("${shortDate(order.orderedAt)} · Total ${Money.pkr(order.amounts.totalPaisa)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                StatusPill(order.orderStatus.label,when(order.orderStatus) {OrderStatus.ACTIVE -> Violet;OrderStatus.READY_FOR_PICKUP -> Amber;OrderStatus.COMPLETE -> Mint;OrderStatus.CANCEL -> Muted})
                                Spacer(Modifier.weight(1f))
                                if(due>0 && order.orderStatus!=OrderStatus.CANCEL) Text("Due ${Money.pkr(due)}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.SemiBold,color=Coral)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomerDetailsSheet(customer:Customer,busy:Boolean,serverError:String?,dismiss:()->Unit,resolveConflict:((Boolean)->Unit)?=null,save:(Customer)->Unit) {
    var name by rememberSaveable(customer.id) {mutableStateOf(customer.name)}
    var mobile by rememberSaveable(customer.id) {mutableStateOf(customer.mobile)}
    var notes by rememberSaveable(customer.id) {mutableStateOf(customer.notes)}
    var prescription by remember(customer.id) {mutableStateOf(customer.latestPrescription ?: Prescription())}
    var error by remember {mutableStateOf<String?>(null)}
    ModalBottomSheet(onDismissRequest={if(!busy) dismiss()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(modifier=Modifier.imePadding().navigationBarsPadding().testTag("customer-profile-form"),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            item {Text("Customer details",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
            item {Text("These details are used for future orders. Previous orders keep their original prescription.",style=MaterialTheme.typography.bodySmall,color=Muted)}
            if(resolveConflict!=null) item {Column {
                Text("The cloud profile changed or this phone belongs to another customer. Refresh Customers while online to compare versions. You can also correct the details below and Save to retry.",color=MaterialTheme.colorScheme.error)
                TextButton(enabled=!busy,onClick={resolveConflict(false)}) {Text("Use cloud version")}
                TextButton(enabled=!busy,onClick={resolveConflict(true)}) {Text("Retry my saved changes")}
            }}
            item {AppTextField(name,{name=it.take(FieldRules.NameMax);error=null},"Customer name",error=FieldRules.name(name)!=null,supporting=FieldRules.name(name) ?: "${name.length}/${FieldRules.NameMax}")}
            item {AppTextField(mobile,{mobile=it.filter {c ->c.isDigit() || c in "+ -()"}.take(20);error=null},"Mobile number",keyboard=KeyboardOptions(keyboardType=KeyboardType.Phone),error=FieldRules.mobile(mobile)!=null,supporting=FieldRules.mobile(mobile))}
            item {SectionLabel("Latest prescription")}
            item {EyePrescriptionEditor("OD · Right eye",prescription.distanceRight) {prescription=prescription.copy(distanceRight=it)}}
            item {EyePrescriptionEditor("OS · Left eye",prescription.distanceLeft) {prescription=prescription.copy(distanceLeft=it)}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                AppTextField(prescription.addRight.orEmpty(),{prescription=prescription.copy(addRight=FieldRules.decimalInput(it.replace("+",""),6).ifBlank {null})},"ADD OD",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=FieldRules.add(prescription.addRight.orEmpty(),"ADD OD")!=null,supporting=FieldRules.add(prescription.addRight.orEmpty(),"ADD OD"))
                AppTextField(prescription.addLeft.orEmpty(),{prescription=prescription.copy(addLeft=FieldRules.decimalInput(it.replace("+",""),6).ifBlank {null})},"ADD OS",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=FieldRules.add(prescription.addLeft.orEmpty(),"ADD OS")!=null,supporting=FieldRules.add(prescription.addLeft.orEmpty(),"ADD OS"))
            }}
            item {AppTextField(prescription.pd.orEmpty(),{prescription=prescription.copy(pd=FieldRules.decimalInput(it,5).ifBlank {null})},"PD (mm)",keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=FieldRules.pd(prescription.pd.orEmpty())!=null,supporting=FieldRules.pd(prescription.pd.orEmpty()))}
            item {AppTextField(notes,{notes=it.take(FieldRules.NotesMax)},"Customer notes",singleLine=false,minLines=2,error=FieldRules.notes(notes)!=null,supporting=FieldRules.notes(notes) ?: "${notes.length}/${FieldRules.NotesMax}")}
            (error ?: serverError)?.let {message ->item {Text(message,color=MaterialTheme.colorScheme.error)}}
            item {Button(enabled=!busy,onClick={try {
                FieldRules.name(name)?.let {error(it)};FieldRules.mobile(mobile)?.let {error(it)}
                listOf(FieldRules.add(prescription.addRight.orEmpty(),"ADD OD"),FieldRules.add(prescription.addLeft.orEmpty(),"ADD OS"),FieldRules.pd(prescription.pd.orEmpty()),FieldRules.notes(notes)).firstNotNullOfOrNull {it}?.let {error(it)}
                val normalized=PakistanPhone.normalize(mobile)!!
                val updated=customer.copy(name=name.trim(),mobile=mobile.trim(),normalizedMobile=normalized,notes=notes.trim(),latestPrescription=prescription,
                    previousMobiles=(customer.previousMobiles+customer.normalizedMobile).distinct().filter {it!=normalized})
                OpticalRules.validateCustomer(updated);OpticalRules.validateEye(prescription.distanceRight);OpticalRules.validateEye(prescription.distanceLeft)
                save(updated)
            } catch(e:Exception) {error=e.message}},modifier=Modifier.fillMaxWidth()) {Text(if(busy) "Saving…" else "Save customer")}}
            item {TextButton(onClick=dismiss,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("Close")}}
        }
    }
}
