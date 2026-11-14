package pk.raheeloptics.orders.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun OrdersScreen(state:UiState,model:OrdersViewModel,onEdit:(Order)->Unit,onComplete:(Order)->Unit,onDelete:(Order)->Unit) {
    val list=rememberLazyListState()
    var filters by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var readyCandidate by remember {mutableStateOf<Order?>(null)}
    var pickupMessage by remember {mutableStateOf<Order?>(null)}
    var readyUndo by remember {mutableStateOf<Order?>(null)}
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while(true) {now=System.currentTimeMillis();delay(60000)}
    } }
    LaunchedEffect(state.query,state.conflictPageNumber) {list.scrollToItem(0)}
    LazyColumn(state=list,contentPadding=PaddingValues(start=Ui.Gutter,end=Ui.Gutter,top=Ui.Tight,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Your orders",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")),style=MaterialTheme.typography.labelMedium,color=Muted)
        }}
        state.syncProblem?.let {problem -> item {Text(problem,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)} }
        readyUndo?.let {previous ->item {Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Marked Ready for pickup",Modifier.weight(1f))
            TextButton(onClick={model.undoReady(previous);readyUndo=null}) {Text("Undo")}
        }}}
        item {DebouncedSearchField(state.query.search,"Search name, phone or order…",40,{model.updateQuery(state.query.copy(search=it))})}
        item {Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight),modifier=Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            val counts=state.snapshot.orderStatusCounts
            listOf(OrderView.ALL,OrderView.ACTIVE,OrderView.READY,OrderView.COMPLETE,OrderView.CANCEL).forEach {view ->
                val count=(view.statuses ?: OrderStatus.entries.toSet()).sumOf {counts[it] ?: 0}
                FilterChip(selected=state.query.view==view,onClick={model.updateQuery(state.query.copy(view=view,orderStatus=null))},label={Text("${view.label} $count",maxLines=1)})
            }
        }}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text("${state.query.view.label} orders",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            TextButton(onClick={filters=true}) {Icon(AppIcons.Tune,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Filter")}
            Box {
            TextButton(onClick={sortMenu=true}) {Text("Sort");Icon(AppIcons.ExpandMore,null)}
            DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                listOf(OrderSort.Newest,OrderSort.Oldest,OrderSort.Deadline,OrderSort.LatestDeadline,OrderSort.CustomerName,OrderSort.Balance,OrderSort.Latest).forEach {sort ->
                    DropdownMenuItem(text={Text(sort.label)},onClick={model.updateQuery(state.query.copy(sort=sort));sortMenu=false})
                }
            }
            }
        }}
        items(state.snapshot.pending.filter {it.state=="conflict"}.distinctBy {it.operation.todo.id},key={"conflict-${it.operation.id}"}) { conflict ->
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer),shape=RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Resolve a change",fontWeight=FontWeight.Bold)
                    Text(conflict.operation.todo.title)
                    Row {TextButton(onClick={model.resolveConflict(conflict,false)},enabled=!state.busy) {Text("Keep latest")};TextButton(onClick={model.resolveConflict(conflict,true)},enabled=!state.busy) {Text("Save a copy")}}
                }
            }
        }
        if(state.snapshot.conflictCount>ListRules.PageSize) item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(onClick=model::previousConflicts,enabled=state.conflictPageNumber>1) {Text("Previous conflicts")}
                TextButton(onClick=model::loadMoreConflicts,enabled=state.conflictPageNumber*ListRules.PageSize<state.snapshot.conflictCount) {Text("Next conflicts")}
            }
        }
        if(state.todos.isEmpty() && !state.todosLoading) item {
            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxWidth()) {
                val filtered=state.query.deadline!=DeadlineFilter.Any || state.query.search.isNotBlank() || state.query.payment!=PaymentFilter.ANY || state.query.orderedFrom!=null || state.query.orderedUntil!=null || state.query.promisedFrom!=null || state.query.promisedUntil!=null
                EmptyState("No orders",if(filtered) "No ${state.query.view.label.lowercase()} orders match your search or filters." else when(state.query.view) {OrderView.ACTIVE -> "No active orders. Tap + to add an optical order.";OrderView.READY -> "Orders marked ready for pickup will appear here.";OrderView.COMPLETE -> "Completed orders will appear here.";else -> "Cancelled orders will appear here."})
                if(filtered) FilledTonalButton(onClick={model.updateQuery(OrderQuery(sort=state.query.sort,view=state.query.view))}) {
                    Icon(AppIcons.FilterAltOff,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Remove filters")
                }
            }
        }
        items(state.todos,key={it.id}) {todo -> OrderCard(todo,now,state.busy,onEdit,onComplete,onDelete,{swiped ->
            if(swiped.orderStatus==OrderStatus.READY_FOR_PICKUP) pickupMessage=swiped else readyCandidate=swiped
        },Modifier.animateItem())}
        item {LoadMoreFooter(state.todosMore,state.todosLoading && state.todos.isNotEmpty(),"Load more orders",model::loadMoreTodos)}
        if(state.todosLoading && state.todos.isEmpty()) item {LogoLoader(Modifier.fillMaxWidth().padding(vertical=48.dp),"Loading orders")}
    }
    if(filters) FilterSheet(state.query,{filters=false}) {model.updateQuery(it);filters=false}
    readyCandidate?.let {order ->AlertDialog(onDismissRequest={readyCandidate=null},title={Text("Ready for pickup?")},text={Text("Confirm that ${order.title}'s glasses are ready.")},
        confirmButton={TextButton(enabled=!state.busy,onClick={model.save(order.copy(orderStatus=OrderStatus.READY_FOR_PICKUP,updatedAt=System.currentTimeMillis()),order) {readyUndo=order};readyCandidate=null}) {Text("Mark ready")}},
        dismissButton={TextButton(onClick={readyCandidate=null}) {Text("Keep active")}})}
    pickupMessage?.let {order ->CustomerMessageDialog(order,CustomerMessage.READY) {pickupMessage=null}}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OrderCard(todo:Order,now:Long,busy:Boolean,onEdit:(Order)->Unit,onComplete:(Order)->Unit,onDelete:(Order)->Unit,onReady:(Order)->Unit,modifier:Modifier=Modifier) {
    val expanded=false
    val tint=when(todo.orderStatus) {OrderStatus.ACTIVE -> Violet;OrderStatus.READY_FOR_PICKUP -> Amber;OrderStatus.COMPLETE -> Mint;OrderStatus.CANCEL -> Muted}
    val canReady=todo.orderStatus.canMarkReady
    val canMessage=todo.orderStatus==OrderStatus.READY_FOR_PICKUP
    val swipe=rememberSwipeToDismissBoxState(confirmValueChange={target ->
        if(target==SwipeToDismissBoxValue.EndToStart || target==SwipeToDismissBoxValue.StartToEnd) {if((canReady || canMessage) && !busy) onReady(todo);false} else true
    })
    SwipeToDismissBox(state=swipe,enableDismissFromStartToEnd=canReady || canMessage,enableDismissFromEndToStart=false,backgroundContent={
        Box(Modifier.fillMaxSize().background(if(canMessage) Ui.WhatsApp else Mint,RoundedCornerShape(22.dp)).padding(horizontal=24.dp),contentAlignment=Alignment.CenterStart) {Text(if(canMessage) "WhatsApp customer" else "Mark ready",color=Color.White,fontWeight=FontWeight.Bold)}
    }) {Card(onClick={onEdit(todo)},modifier=modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=1.dp),border=androidx.compose.foundation.BorderStroke(1.dp,tint.copy(alpha=.16f))) {
        Column(Modifier.drawBehind {drawRect(tint,Offset.Zero,Size(3.dp.toPx(),size.height))}.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(todo.title,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium,maxLines=if(expanded) Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
                    if(todo.customerMobile.isNotBlank()) Text("${todo.customerMobile} · ${todo.orderNumber}",color=Muted,style=MaterialTheme.typography.bodySmall)
                    if(todo.notes.isNotBlank() && !expanded) Text(todo.notes,maxLines=1,overflow=TextOverflow.Ellipsis,color=Muted,style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Surface(color=tint.copy(alpha=.1f),shape=CircleShape) {Text(todo.orderStatus.label,Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=tint)}
                        if(todo.notes.isNotBlank()) Icon(AppIcons.Notes,null,Modifier.size(15.dp),tint=Muted)
                    }
                }
                Icon(AppIcons.ChevronRight,"View order",tint=Muted)
            }
            if(todo.dueAt!=null && !todo.orderStatus.isTerminal) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Icon(AppIcons.Schedule,null,Modifier.size(15.dp),tint=Muted)
                    Text(date(todo.dueAt),Modifier.padding(start=6.dp).weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
                    Text(remaining(todo.dueAt,now),style=MaterialTheme.typography.labelMedium,color=if(todo.dueAt<now) Coral else Violet)
                }
            }
            if(expanded) {
                Text("OD  SPH ${todo.prescription.distanceRight.sphere.ifBlank {"—"}}   ·   OS  SPH ${todo.prescription.distanceLeft.sphere.ifBlank {"—"}}",style=MaterialTheme.typography.bodyMedium)
                Text("Total PKR ${"%,d".format(todo.amounts.totalPaisa/100)} · Due PKR ${"%,d".format((todo.amounts.totalPaisa-todo.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)/100)}",fontWeight=FontWeight.SemiBold,color=Violet)
                if(todo.notes.isNotBlank()) Text(todo.notes,color=Muted,style=MaterialTheme.typography.bodyMedium)
                todo.reminderAt?.let {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(AppIcons.Alarm,null,Modifier.size(16.dp),tint=Muted);Text("Reminder · ${date(it)}",style=MaterialTheme.typography.labelMedium,color=Muted)
                }}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(todo.orderStatus==OrderStatus.READY_FOR_PICKUP) {
                        Button(onClick={onComplete(todo)},enabled=!busy) {Text(OrderStatus.COMPLETE.label)}
                        TextButton(onClick={onReady(todo)},enabled=!busy) {Text("WhatsApp")}
                    }
                    else if(canReady) Text("Swipe right when ready",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
                    if(todo.orderStatus==OrderStatus.ACTIVE) TextButton(onClick={onDelete(todo)},enabled=!busy) {Text(OrderStatus.CANCEL.label,color=MaterialTheme.colorScheme.error)}
                }
            }
        }
    }}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSheet(query:OrderQuery,dismiss:()->Unit,apply:(OrderQuery)->Unit) {
    var deadline by rememberSaveable {mutableStateOf(query.deadline)}
    var payment by rememberSaveable {mutableStateOf(query.payment)}
    var orderedFrom by remember {mutableStateOf(query.orderedFrom)}
    var orderedUntil by remember {mutableStateOf(query.orderedUntil)}
    var promisedFrom by remember {mutableStateOf(query.promisedFrom)}
    var promisedUntil by remember {mutableStateOf(query.promisedUntil)}
    var error by remember {mutableStateOf<String?>(null)}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(modifier=Modifier.navigationBarsPadding(),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Order filters",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={deadline=DeadlineFilter.Any;payment=PaymentFilter.ANY;orderedFrom=null;orderedUntil=null;promisedFrom=null;promisedUntil=null}) {Text("Reset")}}}
            item {Text("Promised date",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DeadlineFilter.entries.forEach {value -> FilterChip(selected=deadline==value,onClick={deadline=value},label={Text(value.label)})}
            }}
            item {Text("Payment");FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {PaymentFilter.entries.forEach {value ->FilterChip(selected=payment==value,onClick={payment=value},label={Text(value.label)})}}}
            item {OrderDateRange("Order date",orderedFrom,orderedUntil) {a,b ->orderedFrom=a;orderedUntil=b}}
            item {OrderDateRange("Promised date range",promisedFrom,promisedUntil) {a,b ->promisedFrom=a;promisedUntil=b}}
            error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
            item {Button(onClick={if((orderedFrom!=null && orderedUntil!=null && orderedFrom!!>=orderedUntil!!) || (promisedFrom!=null && promisedUntil!=null && promisedFrom!!>=promisedUntil!!)) error="Choose an end date on or after the start date." else apply(query.copy(priority=null,deadline=deadline,payment=payment,orderedFrom=orderedFrom,orderedUntil=orderedUntil,promisedFrom=promisedFrom,promisedUntil=promisedUntil))},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("Apply filters")}}
        }
    }
}
