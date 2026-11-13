package pk.raheeloptics.orders.ui

import android.Manifest
import androidx.core.net.toUri
import androidx.core.content.edit
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pk.raheeloptics.orders.DaymarkApp
import pk.raheeloptics.orders.R
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

private val Ink:Color @Composable get()=MaterialTheme.colorScheme.onSurface
private val Violet:Color @Composable get()=MaterialTheme.colorScheme.primary
private val Paper:Color @Composable get()=MaterialTheme.colorScheme.background
private val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
private val Mint:Color @Composable get()=MaterialTheme.colorScheme.secondary
private val Coral:Color @Composable get()=MaterialTheme.colorScheme.error
private val Amber:Color @Composable get()=if(MaterialTheme.colorScheme.background.red<.2f) Color(0xFFFFCE80) else Color(0xFF8A5F0E)
private val lightScheme=lightColorScheme(primary=Color(0xFF075A91),onPrimary=Color.White,secondary=Color(0xFF087F67),background=Color(0xFFF5F9FC),
    surface=Color.White,surfaceVariant=Color(0xFFE5F0F7),onSurface=Color(0xFF102A3A),onBackground=Color(0xFF102A3A),onSurfaceVariant=Color(0xFF587080),error=Color(0xFFB64032))
private val darkScheme=darkColorScheme(primary=Color(0xFF78C7F5),secondary=Color(0xFF69D8B8),background=Color(0xFF071923),
    surface=Color(0xFF102833),surfaceVariant=Color(0xFF193A49),onSurface=Color(0xFFE7F3F9),onBackground=Color(0xFFE7F3F9),onSurfaceVariant=Color(0xFFAEC5D1),error=Color(0xFFFFB4A8))
private val LocalThemeChoice=staticCompositionLocalOf {"System"}
private val LocalSetTheme=staticCompositionLocalOf<(String)->Unit> {{}}
private val LocalBrand=staticCompositionLocalOf {pk.raheeloptics.orders.branding.BrandAppearance(pk.raheeloptics.orders.branding.BrandConfig("Raheel Optics","Clear vision. Personal care."))}
@Composable internal fun BrandLogo(modifier:Modifier) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val image=@Composable {m:Modifier -> Image(painterResource(R.drawable.raheel_optics_logo),LocalBrand.current.config.name+" logo",m)}
    if(dark) Box(modifier.background(Color.White,CircleShape).padding(4.dp),contentAlignment=Alignment.Center) {image(Modifier.fillMaxSize())} else image(modifier)
}
private val codec=Json { ignoreUnknownKeys=true; encodeDefaults=true }
private fun shortDate(time:Long):String=java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.of("Asia/Karachi")).format(DateTimeFormatter.ofPattern("d MMM yyyy",java.util.Locale.US))
private fun date(time:Long?):String=time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d · h:mm a"))
} ?: "No promised date"
private fun remaining(time:Long,now:Long):String {
    val minutes=(time-now)/60000
    return when { time<now -> "Overdue"; minutes<1 -> "Due now"; minutes<60 -> "${minutes}m left"; minutes<1440 -> "${minutes/60}h left"; else -> "${minutes/1440}d left" }
}

@Composable
fun DaymarkTheme(content:@Composable ()->Unit) {
    val context=LocalContext.current
    val themePrefs=remember {context.getSharedPreferences("appearance",android.content.Context.MODE_PRIVATE)}
    var themeChoice by remember {mutableStateOf(themePrefs.getString("theme","System") ?: "System")}
    val dark=when(themeChoice) {"Dark"->true;"Light"->false;else->androidx.compose.foundation.isSystemInDarkTheme()}
    SideEffect {
        (context as? android.app.Activity)?.let {activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView).apply {
                isAppearanceLightStatusBars=!dark
                isAppearanceLightNavigationBars=!dark
            }
        }
    }
    CompositionLocalProvider(LocalThemeChoice provides themeChoice,LocalSetTheme provides {choice ->
        themePrefs.edit {putString("theme",choice)};themeChoice=choice
        syncLaunchTheme(context)
        if(Build.VERSION.SDK_INT<31) (context as? android.app.Activity)?.recreate()
    }) {MaterialTheme(colorScheme=if(dark) darkScheme else lightScheme,content=content)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaymarkScreen(target:String?,consumeTarget:()->Unit,model:TodoViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    androidx.activity.compose.ReportDrawnWhen {!state.loading && (state.session==null || !state.todosLoading)}
    val context=LocalContext.current
    val app=context.applicationContext as DaymarkApp
    val appearance by app.branding.state.collectAsStateWithLifecycle()
    val introduction by app.onboarding.required.collectAsStateWithLifecycle()
    val introductionScope=rememberCoroutineScope()
    var introductionBusy by remember {mutableStateOf(false)}
    var introductionError by remember {mutableStateOf<String?>(null)}
    val finishIntroduction:()->Unit={
        if(!introductionBusy) {
            introductionBusy=true;introductionError=null
            introductionScope.launch {
                try {app.onboarding.complete()}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) {introductionError="Couldn't save that. Please try again."}
                finally {introductionBusy=false}
            }
        }
    }
    var tab by rememberSaveable { mutableStateOf("Orders") }
    val todoSaver=remember { Saver<Order?,String>(save={it?.let(codec::encodeToString) ?: ""},restore={if(it.isBlank()) null else codec.decodeFromString<Order>(it)}) }
    var editor by rememberSaveable(stateSaver=todoSaver) { mutableStateOf<Order?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var repeatDraft by rememberSaveable(stateSaver=todoSaver) {mutableStateOf<Order?>(null)}
    var deleting by remember { mutableStateOf<Order?>(null) }
    var details by rememberSaveable(stateSaver=todoSaver) {mutableStateOf<Order?>(null)}
    var logout by remember { mutableStateOf(false) }
    var allowed by remember { mutableStateOf(app.reminders.allowed()) }
    val lifecycleOwner=LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) {allowed=app.reminders.allowed();model.foreground(true)}
            if(event==Lifecycle.Event.ON_STOP) model.foreground(false)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed=app.reminders.allowed() }
    val notificationSettings:()->Unit={
        val prefs=context.getSharedPreferences("notification-permission",android.content.Context.MODE_PRIVATE)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED && !prefs.getBoolean("requested",false)) {
            prefs.edit { putBoolean("requested",true) }; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))
    }
    LaunchedEffect(state.session?.userId,state.loading) {
        if(state.session==null && !state.loading) { tab="Orders"; editor=null; adding=false; repeatDraft=null; deleting=null; logout=false }
    }
    LaunchedEffect(tab,state.session?.userId) { if(tab=="Customers" && state.session!=null) model.openCustomers() }
    DisposableEffect(tab,state.session?.userId) {onDispose {if(tab=="Customers") model.closeCustomers(); if(tab=="Analytics") model.closeAnalytics()}}
    LaunchedEffect(target,state.loading) {
        if(target!=null && !state.loading && state.session!=null) {
            val todo=model.notificationTarget(target)
            if(todo!=null) { tab="Orders"; editor=todo } else tab="Inbox"
            consumeTarget()
        }
    }
    CompositionLocalProvider(LocalBrand provides appearance) {
        if(state.loading && state.session==null) SplashScreen()
        else if(introduction && state.session==null) OnboardingScreen(introductionBusy,introductionError,finishIntroduction)
        else if(state.session==null) AccountScreen(state,model)
        else Scaffold(containerColor=Paper,
            topBar={TopAppBar(title={
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(tab=="Orders") BrandLogo(Modifier.size(40.dp))
                    Text(if(tab=="Orders") LocalBrand.current.config.name else tab,fontWeight=FontWeight.Bold)
                }
            },actions={Tip("Notifications") {IconButton(onClick={tab="Inbox"}) {
                BadgedBox(badge={if(state.snapshot.inbox.any { !it.read }) Badge(containerColor=Coral)}) {Icon(DaymarkIcons.Notifications,"Notifications")}
            }}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Paper))},
            bottomBar={BottomNavigation(tab,{tab=it},{model.dismissMessage();adding=true;editor=null;repeatDraft=null},state.busy || state.loading)}
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.loading) LogoLoader(Modifier.fillMaxSize())
                else when(tab) {
                    "Orders" -> HomeScreen(state,model,{model.dismissMessage();details=it},{model.transition(it,"complete")},{deleting=it})
                    "Customers" -> CustomersScreen(state,model,{source ->repeatDraft=RepeatOrder.draft(source);editor=null;adding=true;model.dismissMessage()},{model.dismissMessage();details=it})
                    "Analytics" -> OpticalAnalyticsScreen(state,model)
                    "Inbox" -> InboxScreen(state.snapshot,allowed,notificationSettings,model) { id -> model.openTask(id) {todo -> editor=todo; if(todo!=null) tab="Orders"} }
                    else -> SettingsScreen(state,allowed,notificationSettings,model) {logout=true}
                }
            }
        }
        if(state.session!=null && (adding || editor!=null)) TodoEditor(editor,state.busy,state.message,{adding=false;editor=null;repeatDraft=null;model.dismissMessage()},repeatDraft,lookupCustomer={model.customerForMobile(it)}) {todo ->
            model.save(todo,editor) {adding=false;editor=null;repeatDraft=null}
        }
        if(state.session!=null) details?.let {order ->OrderDetailScreen(order,state.busy,{details=null},
            edit={details=null;editor=order},change={updated ->model.save(updated,order) {model.openOrder(order.id) {details=it}}},
            complete={model.transition(order,"complete");details=null},cancel={details=null;deleting=order},
            reorder={details=null;repeatDraft=RepeatOrder.draft(order);editor=null;adding=true})}
        deleting?.let { todo -> AlertDialog(onDismissRequest={deleting=null},title={Text("Cancel order?")},
            text={Text("The order will stay in your records with status Cancel.")},confirmButton={TextButton(onClick={model.transition(todo,"delete");deleting=null},enabled=!state.busy) {Text("Cancel order",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={deleting=null}) {Text("Keep order")}}) }
        if(logout) AlertDialog(onDismissRequest={logout=false},title={Text(if(state.session?.localOnly==true) "Leave guest mode?" else "Log out?")},
            text={Text(if(state.session?.localOnly==true) "Your guest orders will move to your account when you sign in." else if(state.snapshot.queuedCount>0) "Pending changes will resume after you sign in." else "Reminders pause until you sign in.")},
            confirmButton={TextButton(onClick={logout=false;model.logout()},enabled=!state.busy) {Text(if(state.session?.localOnly==true) "Continue to sign in" else "Log out")}},
            dismissButton={TextButton(onClick={logout=false}) {Text("Cancel")}})
    }
}

@Composable
private fun BottomNavigation(tab:String,onSelect:(String)->Unit,onAdd:()->Unit,disabled:Boolean) {
    val largeText=LocalDensity.current.fontScale>1.3f
    Surface(color=MaterialTheme.colorScheme.surface,shadowElevation=6.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=8.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
            if(largeText) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    NavigationDestination("Orders",tab,onSelect,Modifier.fillMaxWidth(),true)
                    NavigationDestination("Customers",tab,onSelect,Modifier.fillMaxWidth(),true)
                }
                AddTaskButton(onAdd,disabled)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    NavigationDestination("Analytics",tab,onSelect,Modifier.fillMaxWidth(),true)
                    NavigationDestination("Settings",tab,onSelect,Modifier.fillMaxWidth(),true)
                }
            } else {
                listOf("Orders","Customers","Add","Analytics","Settings").forEach {name ->
                    if(name=="Add") Box(Modifier.weight(1f),contentAlignment=Alignment.Center) {AddTaskButton(onAdd,disabled)}
                    else NavigationDestination(name,tab,onSelect,Modifier.weight(1f),false)
                }
            }
        }
    }
}

@Composable private fun AddTaskButton(onAdd:()->Unit,disabled:Boolean) {
    Tip("Add order") {FilledIconButton(onClick=onAdd,enabled=!disabled,
        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Violet,contentColor=MaterialTheme.colorScheme.onPrimary),
        modifier=Modifier.size(56.dp),shape=CircleShape) {Icon(DaymarkIcons.Add,"Add order")}}
}

@Composable private fun NavigationDestination(name:String,tab:String,onSelect:(String)->Unit,modifier:Modifier,compact:Boolean) {
    val active=tab==name
    Column(modifier.heightIn(min=48.dp).clickable {onSelect(name)}.semantics {contentDescription=name;selected=active},horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(if(compact) 32.dp else 48.dp).clip(RoundedCornerShape(16.dp))
            .background(if(active) Violet.copy(alpha=.1f) else Color.Transparent),contentAlignment=Alignment.Center) {
            Icon(when(name) {"Orders"->DaymarkIcons.GridView;"Customers"->DaymarkIcons.Person;"Analytics"->DaymarkIcons.DonutLarge;else->DaymarkIcons.Settings},null,tint=if(active) Violet else Muted)
        }
        Text(name,color=if(active) Violet else Muted,style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun HelpTip(detail:String,label:String="More information") {
    val tooltip=rememberTooltipState(isPersistent=true)
    androidx.activity.compose.BackHandler(enabled=tooltip.isVisible) {tooltip.dismiss()}
    val scope=rememberCoroutineScope()
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip {Text(detail)}},state=tooltip) {
        IconButton(onClick={scope.launch {tooltip.show()}},modifier=Modifier.size(40.dp)) {
            Icon(DaymarkIcons.Info,label,modifier=Modifier.size(18.dp),tint=Muted)
        }
    }
}

@Composable private fun Message(text:String,dismiss:()->Unit) {
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(text) {kotlinx.coroutines.delay(5000);currentDismiss()}
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
        Row(Modifier.padding(start=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text,Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
            TipIconButton(DaymarkIcons.Close,"Dismiss message",dismiss)
        }
    }
}
@Composable private fun EmptyState(title:String,detail:String,icon:androidx.compose.ui.graphics.vector.ImageVector=DaymarkIcons.CheckCircle) {
    Column(Modifier.fillMaxWidth().padding(vertical=56.dp,horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(Violet.copy(alpha=.08f),CircleShape),contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(32.dp),tint=Violet)}
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text(detail,style=MaterialTheme.typography.bodyMedium,color=Muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(state:UiState,model:TodoViewModel,onEdit:(Order)->Unit,onComplete:(Order)->Unit,onDelete:(Order)->Unit) {
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
            TextButton(onClick={filters=true}) {Icon(DaymarkIcons.Tune,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Filter")}
            Box {
            TextButton(onClick={sortMenu=true}) {Text("Sort");Icon(DaymarkIcons.ExpandMore,null)}
            DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                listOf(TodoSort.Newest,TodoSort.Oldest,TodoSort.Deadline,TodoSort.LatestDeadline,TodoSort.CustomerName,TodoSort.Balance,TodoSort.Latest).forEach {sort ->
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
                if(filtered) FilledTonalButton(onClick={model.updateQuery(TodoQuery(sort=state.query.sort,view=state.query.view))}) {
                    Icon(DaymarkIcons.FilterAltOff,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Remove filters")
                }
            }
        }
        items(state.todos,key={it.id}) {todo -> TodoCard(todo,now,state.busy,onEdit,onComplete,onDelete,{swiped ->
            if(swiped.orderStatus==OrderStatus.READY_FOR_PICKUP) pickupMessage=swiped else readyCandidate=swiped
        },Modifier.animateItem())}
        item {LoadMoreFooter(state.todosMore,state.todosLoading && state.todos.isNotEmpty(),"Load more orders",model::loadMoreTodos)}
        if(state.todosLoading && state.todos.isEmpty()) item {LogoLoader(Modifier.fillMaxWidth().padding(vertical=48.dp),"Loading orders")}
    }
    if(filters) FilterSheet(state.query,{filters=false}) {model.updateQuery(it);filters=false}
    readyCandidate?.let {order ->AlertDialog(onDismissRequest={readyCandidate=null},title={Text("Ready for pickup?")},text={Text("Confirm that ${order.title}'s glasses are ready.")},
        confirmButton={TextButton(enabled=!state.busy,onClick={model.save(order.copy(orderStatus=OrderStatus.READY_FOR_PICKUP,updatedAt=System.currentTimeMillis()),order) {readyUndo=order};readyCandidate=null}) {Text("Mark ready")}},
        dismissButton={TextButton(onClick={readyCandidate=null}) {Text("Keep active")}})}
    pickupMessage?.let {order ->PickupMessageDialog(order) {pickupMessage=null}}
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TodoCard(todo:Order,now:Long,busy:Boolean,onEdit:(Order)->Unit,onComplete:(Order)->Unit,onDelete:(Order)->Unit,onReady:(Order)->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
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
                        if(todo.notes.isNotBlank()) Icon(DaymarkIcons.Notes,null,Modifier.size(15.dp),tint=Muted)
                    }
                }
                Icon(DaymarkIcons.ChevronRight,"View order",tint=Muted)
            }
            if(todo.dueAt!=null && !todo.orderStatus.isTerminal) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Icon(DaymarkIcons.Schedule,null,Modifier.size(15.dp),tint=Muted)
                    Text(date(todo.dueAt),Modifier.padding(start=6.dp).weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
                    Text(remaining(todo.dueAt,now),style=MaterialTheme.typography.labelMedium,color=if(todo.dueAt<now) Coral else Violet)
                }
            }
            if(expanded) {
                Text("OD  SPH ${todo.prescription.distanceRight.sphere.ifBlank {"—"}}   ·   OS  SPH ${todo.prescription.distanceLeft.sphere.ifBlank {"—"}}",style=MaterialTheme.typography.bodyMedium)
                Text("Total PKR ${"%,d".format(todo.amounts.totalPaisa/100)} · Due PKR ${"%,d".format((todo.amounts.totalPaisa-todo.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)/100)}",fontWeight=FontWeight.SemiBold,color=Violet)
                if(todo.notes.isNotBlank()) Text(todo.notes,color=Muted,style=MaterialTheme.typography.bodyMedium)
                todo.reminderAt?.let {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(DaymarkIcons.Alarm,null,Modifier.size(16.dp),tint=Muted);Text("Reminder · ${date(it)}",style=MaterialTheme.typography.labelMedium,color=Muted)
                }}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    if(todo.orderStatus==OrderStatus.READY_FOR_PICKUP) {
                        Button(onClick={onComplete(todo)},enabled=!busy) {Text(OrderStatus.COMPLETE.label)}
                        TextButton(onClick={
                            val due=(todo.amounts.totalPaisa-todo.payments.sumOf {it.amountPaisa}).coerceAtLeast(0)/100
                            val shop=context.getSharedPreferences("optical-shop",android.content.Context.MODE_PRIVATE)
                            val shopName=shop.getString("name","Raheel Optics") ?: "Raheel Optics"
                            val hours=shop.getString("weekdays","10:00 AM – 8:00 PM") ?: "10:00 AM – 8:00 PM"
                            val phone=shop.getString("phone","").orEmpty()
                            val contact=if(phone.isBlank()) "please contact the shop" else "contact us at $phone"
                            val message="Hello ${todo.title}, your glasses for order ${todo.orderNumber} are ready for pickup from $shopName. Pickup hours: $hours. Amount due: PKR ${"%,d".format(due)}. If the shop is closed or you need assistance, $contact. Thank you."
                            val intent=Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,message).setPackage("com.whatsapp")
                            runCatching {context.startActivity(intent)}.getOrElse {context.startActivity(Intent.createChooser(intent.setPackage(null),"Share ready message"))}
                        }) {Text("WhatsApp")}
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
private fun FilterSheet(query:TodoQuery,dismiss:()->Unit,apply:(TodoQuery)->Unit) {
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

@Composable
private fun CustomersScreen(state:UiState,model:TodoViewModel,newOrder:(Order)->Unit,openOrder:(Order)->Unit) {
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
                TipIconButton(DaymarkIcons.Tune,"Sort customers",{sortMenu=true})
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    CustomerSort.entries.forEach {sort ->DropdownMenuItem(text={Text(sort.label,fontWeight=if(sort==query.sort) FontWeight.Bold else null)},onClick={model.updateCustomers(query.copy(sort=sort,page=1,loaded=1));sortMenu=false})}
                }
            }
        }}
        if(!state.customersLoaded) item {LogoLoader(Modifier.fillMaxWidth().padding(vertical=48.dp),"Loading customers")}
        else if(state.customers.isEmpty()) item {
            EmptyState("No customers found",if(query.search.isNotBlank()) "Try another name or mobile number." else "Your first saved order adds a customer here.",DaymarkIcons.Person)
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
                    TipIconButton(DaymarkIcons.Chat,"Send dues on WhatsApp",{whatsApp(row)},Modifier.size(48.dp).testTag("customer-whatsapp"),tint=Ui.WhatsApp)
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
private fun CustomerOrdersDialog(row:CustomerDirectoryRow,model:TodoViewModel,dismiss:()->Unit,openOrder:(Order)->Unit,newOrder:()->Unit,edit:()->Unit,whatsApp:()->Unit) {
    val customer=row.latestOrder
    val orders by produceState<List<Order>?>(null,row.latestOrder.id,row.orderCount) {value=runCatching {model.ordersForCustomer(row)}.getOrDefault(emptyList())}
    AppDialog(customer.title,dismiss,"New order",newOrder) {
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            KeyValueRow("Mobile",customer.customerMobile)
            KeyValueRow("Orders",row.orderCount.toString())
            KeyValueRow("Balance due",if(row.balancePaisa>0) "${Money.pkr(row.balancePaisa)}" else "Clear",emphasis=if(row.balancePaisa>0) Coral else Mint)
            Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                OutlinedButton(onClick=whatsApp,shape=Ui.Field,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.weight(1f)) {Icon(DaymarkIcons.Chat,null,Modifier.size(18.dp),tint=Ui.WhatsApp);Spacer(Modifier.width(6.dp));Text("WhatsApp",maxLines=1,softWrap=false)}
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


@Composable
private fun InboxScreen(snapshot:Snapshot,allowed:Boolean,notificationSettings:()->Unit,model:TodoViewModel,open:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
        if(!allowed) item {Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                Icon(DaymarkIcons.NotificationsOff,null,tint=Muted)
                Spacer(Modifier.weight(1f))
                HelpTip("Due alerts stay in Inbox when notifications are off.","About notification delivery")
            }
            Text("Notifications off",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
            Text("Due alerts still appear here. Turn on notifications to see them outside the app.",color=Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=notificationSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Open settings")}
        }}}
        if(snapshot.inbox.isEmpty()) item {EmptyState("No reminders","You're all caught up.",DaymarkIcons.NotificationsNone)}
        items(snapshot.inbox,key={it.id}) {alert ->Card(onClick={model.readAlert(alert.id);open(alert.todoId)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=if(alert.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                Icon(DaymarkIcons.Alarm,null,tint=Violet)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(alert.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(date(alert.time),color=Muted,style=MaterialTheme.typography.labelSmall)
                    if(alert.todoId !in snapshot.activeTaskIds) Text("No longer active",color=Muted,style=MaterialTheme.typography.labelSmall)
                }
            }
        }}
    }
}

@Composable
private fun SettingsScreen(state:UiState,allowed:Boolean,requestPermission:()->Unit,model:TodoViewModel,onLogout:()->Unit) {
    val context=LocalContext.current
    var passwordDialog by remember {mutableStateOf(false)}
    var deletionDialog by remember {mutableStateOf(false)}
    var privacyDialog by remember {mutableStateOf(false)}
    var sessionMenu by remember {mutableStateOf(false)}
    var themeDialog by remember {mutableStateOf(false)}
    var clearCache by remember {mutableStateOf(false)}
    var clearLocalHistory by remember {mutableStateOf(false)}
    var clearDevice by remember {mutableStateOf(false)}
    var shopDialog by remember {mutableStateOf(false)}
    var storageExpanded by rememberSaveable {mutableStateOf(false)}
    var exportMenu by remember {mutableStateOf(false)}
    var exportSelection by remember {mutableStateOf(OpticalExportSelection.BACKUP)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {uri -> if(uri!=null) model.exportWorkspace(uri,exportSelection)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> uri?.let(model::previewTaskImport)}
    LaunchedEffect(Unit) {model.loadStorageStats()}
    LazyColumn(contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Section)) {
        // Account card: a guest taps it to sign in; a signed-in user sees their email (Log out is under Account actions).
        item {Surface(onClick=onLogout,enabled=state.session?.localOnly==true && !state.busy,shape=Ui.Card,color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(Ui.Gutter),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(Violet.copy(alpha=.1f),CircleShape),contentAlignment=Alignment.Center) {Icon(DaymarkIcons.Person,null,tint=Violet)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Account",fontWeight=FontWeight.SemiBold)
                Text(if(state.session?.localOnly==true) "Not signed in · tap to sign in" else state.session?.email.orEmpty(),color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(state.session?.localOnly==true) Icon(DaymarkIcons.ChevronRight,null,tint=Muted)
        }}}
        if(pk.raheeloptics.orders.BuildConfig.DEMO_DATA) item {SettingsRow(DaymarkIcons.Storage,"Load demo data","33 customers, about 50 orders (test builds only)",{model.loadDemoData()},enabled=!state.busy)}
        item {SettingsRow(DaymarkIcons.Schedule,"Shop details & timings",ShopPreferences.load(context).phone.let {if(it.isBlank()) "Add your WhatsApp number — used in customer messages" else "WhatsApp $it · hours and address"},{shopDialog=true})}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(if(allowed) DaymarkIcons.NotificationsActive else DaymarkIcons.NotificationsOff,null,tint=if(allowed) Mint else Muted)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text("Notifications",Modifier.weight(1f,fill=false),fontWeight=FontWeight.SemiBold)
                        HelpTip("Android may delay alerts; due alerts stay in Inbox.","About notification delivery")
                    }
                    Text(if(allowed) "Enabled" else "Disabled",style=MaterialTheme.typography.labelMedium,color=if(allowed) Mint else Muted)
                }
                TextButton(onClick=requestPermission) {Text(if(allowed) "Manage" else "Enable")}
            }
        }}}
        if(state.session?.localOnly==false) item {Surface(onClick={model.dismissMessage();passwordDialog=true},enabled=!state.busy,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Row(Modifier.fillMaxWidth().padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Icon(DaymarkIcons.Lock,null,tint=Violet);Text("Change password",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Icon(DaymarkIcons.ChevronRight,null,tint=Muted)
        }}}
        item {SettingsRow(DaymarkIcons.Palette,"Appearance",LocalThemeChoice.current,{themeDialog=true})}
        item {Text("Security & privacy",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item {SettingsRow(DaymarkIcons.PrivacyTip,"Privacy","How your account and orders are stored",{privacyDialog=true})}
        if(state.session?.localOnly==false) item {
            Box {
                SettingsRow(DaymarkIcons.VerifiedUser,"Stay signed in",if(state.sessionIdleDays==0) "Until I sign out" else "${state.sessionIdleDays} days without opening the app",{sessionMenu=true})
                DropdownMenu(expanded=sessionMenu,onDismissRequest={sessionMenu=false}) {
                    SessionPolicy.options.forEach {days -> DropdownMenuItem(text={Text(if(days==0) "Until I sign out" else "$days days of inactivity")},onClick={model.setSessionIdleDays(days);sessionMenu=false},trailingIcon={if(days==state.sessionIdleDays) Icon(DaymarkIcons.Check,null)})}
                }
            }
        }
        item {Text("Storage",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(DaymarkIcons.Storage,null,tint=Violet)
                    Column {Text("On this device",fontWeight=FontWeight.SemiBold);Text("Orders, customers and changes waiting to sync",color=Muted,style=MaterialTheme.typography.bodySmall)}
                }
                state.storageStats?.let {stats ->
                    Text("${stats.activeTasks} open orders · ${storageLabel(stats.allocatedBytes)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.queuedOperations>0) Text("${stats.queuedOperations} changes waiting to sync",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.quarantinedChanges>0) Text("${stats.quarantinedChanges} changes need recovery",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
                TextButton(onClick={storageExpanded=!storageExpanded}) {Text(if(storageExpanded) "Hide options" else "Manage storage");Icon(if(storageExpanded) DaymarkIcons.ExpandLess else DaymarkIcons.ExpandMore,null)}
                if(storageExpanded) {
                if(state.session?.localOnly==false) StorageAction(DaymarkIcons.DeleteOutline,"Clear history cache",
                    "Remove downloaded history from this device.",
                    "Cloud history and pending changes stay safe. Download history again when online.",!state.busy) {clearCache=true}
                else Text("Completed and cancelled orders stay on this device for customer records and analytics. Export a backup regularly.",style=MaterialTheme.typography.bodySmall,color=Muted)
                StorageAction(DaymarkIcons.Download,"Export orders or customers",
                    "Choose active orders, customers, or a combined backup.",
                    "Includes structured prescriptions and amounts, but not frame photo files. Keep customer data private.",!state.busy) {exportMenu=true}
                StorageAction(DaymarkIcons.Upload,"Import orders & customers",
                    "Preview an optical export before adding records.",
                    "Known customer phone numbers are reused. Existing profiles are preserved. Imports are retry-safe; reminders and frame photos must be added again.",!state.busy) {import.launch(arrayOf("*/*"))}
                }
            }
        }}
        item {Text("Account actions",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        if(state.session?.localOnly==false) item {SettingsRow(DaymarkIcons.Logout,"Log out","Your saved orders stay on this device",onLogout,enabled=!state.busy)}
        item {SettingsRow(DaymarkIcons.Storage,"Clear device data","Signs out and clears this account on this device",{clearDevice=true},enabled=!state.busy)}
        if(state.session?.localOnly==false) item {SettingsRow(DaymarkIcons.DeleteOutline,"Delete account","Permanently remove your account and cloud data",{model.dismissMessage();deletionDialog=true},tint=Coral,enabled=!state.busy)}
        item {Text(LocalBrand.current.config.name+" · "+pk.raheeloptics.orders.BuildConfig.VERSION_NAME,modifier=Modifier.fillMaxWidth(),color=Muted,style=MaterialTheme.typography.labelSmall)}
    }
    if(exportMenu) AlertDialog(onDismissRequest={exportMenu=false},title={Text("Export")},text={Column {OpticalExportSelection.entries.forEach {selection ->TextButton(onClick={exportSelection=selection;exportMenu=false;export.launch("raheel-optics-${selection.name.lowercase()}.jsonl")}) {Text(selection.label)}}}},confirmButton={TextButton(onClick={exportMenu=false}) {Text("Close")}})
    if(state.importActiveCount!=null) AlertDialog(onDismissRequest=model::cancelTaskImport,title={Text("Import optical records?")},text={Text("${state.importActiveCount} active orders and ${state.importCustomerCount} customers found. ${state.importSkippedCount} finished orders and diagnostic history will be skipped. Known phone numbers reuse existing customers without overwriting them. Orders get new import numbers; old reminders and frame images are not restored. Reopening the same file safely resumes the import.")},confirmButton={TextButton(onClick=model::applyTaskImport,enabled=!state.busy && (state.importActiveCount>0 || state.importCustomerCount>0)) {Text("Import")}},dismissButton={TextButton(onClick=model::cancelTaskImport) {Text("Cancel")}})
        if(shopDialog) ShopSettingsScreen {shopDialog=false}
    if(clearCache) AlertDialog(onDismissRequest={clearCache=false},title={Text("Clear offline history?")},text={Text("Cloud history and unsynced changes stay safe. You can download history again when online. Android may reuse the freed database space.")},confirmButton={TextButton(onClick={clearCache=false;model.clearHistoryCache()}) {Text("Clear cache")}},dismissButton={TextButton(onClick={clearCache=false}) {Text("Cancel")}})
    if(clearLocalHistory) AlertDialog(onDismissRequest={clearLocalHistory=false},title={Text("Clear local history?")},text={Text("These history entries have no cloud copy. Export device data first if you need them. Active tasks and lifetime counts stay; removed history cannot be undone.")},confirmButton={TextButton(onClick={clearLocalHistory=false;model.clearLocalHistory()}) {Text("Clear history")}},dismissButton={TextButton(onClick={clearLocalHistory=false}) {Text("Cancel")}})
    if(clearDevice) AlertDialog(onDismissRequest={clearDevice=false},title={Text("Clear device data?")},text={Text("This signs you out and clears this account’s local orders and customer records. Cloud data stays in your account. Local-only orders have no cloud copy; export first. Pending changes must be synced or resolved before clearing.")},confirmButton={TextButton(onClick={clearDevice=false;model.clearDeviceData()}) {Text("Clear device data")}},dismissButton={TextButton(onClick={clearDevice=false}) {Text("Cancel")}})
    if(themeDialog) {
        val choice=LocalThemeChoice.current
        val setTheme=LocalSetTheme.current
        AlertDialog(onDismissRequest={themeDialog=false},title={Text("Appearance")},text={Column {
            listOf("System","Light","Dark").forEach {value ->
                Row(Modifier.fillMaxWidth().clickable {setTheme(value);themeDialog=false}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    RadioButton(selected=choice==value,onClick={setTheme(value);themeDialog=false});Text(value)
                }
            }
        }},confirmButton={TextButton(onClick={themeDialog=false}) {Text("Close")}})
    }
    if(privacyDialog) PrivacyDialog {privacyDialog=false}
    if(deletionDialog) {
        var current by remember {mutableStateOf("")}
        AlertDialog(onDismissRequest={if(!state.busy) deletionDialog=false},title={Text("Delete account?")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("This permanently removes your account, orders and customer records. Unsynced changes on this phone will also be removed.")
            PasswordField(current,{current=it},"Current password")
            state.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick={model.deleteAccount(current)},enabled=!state.busy && current.isNotBlank()) {Text(if(state.busy) "Deleting…" else "Delete account")}},dismissButton={TextButton(onClick={deletionDialog=false},enabled=!state.busy) {Text("Cancel")}})
    }
    if(passwordDialog) PasswordDialog(state.busy,state.message,{passwordDialog=false}) {current,replacement ->model.changePassword(current,replacement) {passwordDialog=false}}
}

@Composable private fun StorageAction(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,detail:String,help:String,enabled:Boolean,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(14.dp),color=Color.Transparent,modifier=Modifier.weight(1f)) {
            Row(Modifier.padding(vertical=12.dp,horizontal=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(icon,null,tint=Violet,modifier=Modifier.size(24.dp))
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(title,color=Violet,fontWeight=FontWeight.SemiBold)
                    Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        HelpTip(help,"About $title")
    }
}

private fun storageLabel(bytes:Long):String=if(bytes<1024*1024) "${bytes/1024} KiB" else String.format(java.util.Locale.getDefault(),"%.1f MiB",bytes/1048576.0)

@Composable private fun SettingsRow(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,detail:String,onClick:()->Unit,tint:Color=Violet,enabled:Boolean=true) {
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(tint.copy(alpha=.08f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {Icon(icon,null,tint=tint)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,fontWeight=FontWeight.SemiBold,color=if(tint==Coral) Coral else Ink)
                Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall)
            }
            Icon(DaymarkIcons.ChevronRight,null,tint=Muted)
        }
    }
}

@Composable private fun PrivacyDialog(dismiss:()->Unit) {
    val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
    val policyUrl=pk.raheeloptics.orders.BuildConfig.PRIVACY_POLICY_URL
    val publicPolicy=runCatching { java.net.URI(policyUrl).let {it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null} }.getOrDefault(false)
    AlertDialog(onDismissRequest=dismiss,title={Text("Privacy")},text={Text("Cloud accounts store your email, customers, orders and payments with Supabase. Guest orders stay on this phone. Device databases use app-private storage without separate database encryption; session credentials use Android Keystore protection. Reminders use Android notifications and depend on device permissions. Released builds send crash reports (device model, Android version, app version and the error trace, never customer or order details) to Google Firebase Crashlytics so faults can be fixed. There is no advertising or analytics tracking. Account deletion removes live cloud records, but does not remotely erase other devices or exported files.",modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=dismiss) {Text("Close")}},dismissButton={if(publicPolicy) TextButton(onClick={uriHandler.openUri(policyUrl)}) {Text("Full policy")}})
}

@Composable private fun PasswordDialog(busy:Boolean,serverError:String?,dismiss:()->Unit,save:(String,String)->Unit) {
    // Passwords are intentionally not written to saved instance state.
    var current by remember {mutableStateOf("")}
    var replacement by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest={if(!busy) dismiss()},title={Text("Change password")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        PasswordField(current,{current=it;error=null},"Current password")
        PasswordField(replacement,{replacement=it;error=null},"New password")
        PasswordField(confirm,{confirm=it;error=null},"Confirm new password")
        Text("Use at least 12 characters.",color=Muted,style=MaterialTheme.typography.labelSmall)
        (error ?: serverError)?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
    }},confirmButton={TextButton(onClick={try {PasswordRules.validate(current,replacement);require(confirm==replacement) {"Passwords don't match."};save(current,replacement)} catch(e:Exception) {error=e.message}},enabled=!busy) {Text(if(busy) "Updating…" else "Update password")}},dismissButton={TextButton(onClick=dismiss,enabled=!busy) {Text("Cancel")}})
}
@Composable private fun PasswordField(value:String,change:(String)->Unit,label:String,error:String?=null) {
    var visible by remember {mutableStateOf(false)}
    OutlinedTextField(value,change,label={Text(label)},singleLine=true,shape=Ui.Field,isError=error!=null,supportingText=error?.let {{Text(it)}},
        visualTransformation=if(visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),
        trailingIcon={TipIconButton(if(visible) DaymarkIcons.VisibilityOff else DaymarkIcons.Visibility,if(visible) "Hide password" else "Show password",{visible=!visible})})
}

@Composable private fun AccountScreen(state:UiState,model:TodoViewModel) {
    var forgot by rememberSaveable {mutableStateOf(false)}
    if(state.codeEmail!=null) {
        EmailCodeScreen(state,model)
        return
    }
    if(forgot) {
        ForgotPasswordScreen(state,model) {model.dismissMessage();forgot=false}
        return
    }
    var register by rememberSaveable {mutableStateOf(false)}
    var email by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var confirmation by remember {mutableStateOf("")}
    var signupError by remember {mutableStateOf<String?>(null)}
    var privacy by remember {mutableStateOf(false)}
    if(privacy) PrivacyDialog {privacy=false}
    val emailError=email.takeIf {it.isNotBlank() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(it.trim())}?.let {"Enter a valid email address."}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(contentPadding=PaddingValues(horizontal=Ui.Section,vertical=32.dp),verticalArrangement=Arrangement.spacedBy(Ui.Section),
            horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.imePadding().safeDrawingPadding()) {
            item {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {BrandLogo(Modifier.size(88.dp).padding(8.dp))}
                Text(LocalBrand.current.config.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium)
                Text(LocalBrand.current.config.tagline,color=Muted,style=MaterialTheme.typography.bodyMedium)
            }}
            item {AppCard {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(selected=!register,onClick={register=false;signupError=null;confirmation=""},shape=SegmentedButtonDefaults.itemShape(0,2),enabled=!state.busy) {Text("Sign in")}
                    SegmentedButton(selected=register,onClick={register=true;signupError=null},shape=SegmentedButtonDefaults.itemShape(1,2),enabled=!state.busy) {Text("Create account")}
                }
                Spacer(Modifier.height(4.dp))
                Text(if(register) "Create your shop account" else "Welcome back",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text(if(register) "Keep orders and customers in sync on every phone." else "Sign in to see your shop orders on every phone.",color=Muted,style=MaterialTheme.typography.bodyMedium)
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                AppTextField(email,{email=it.trim().take(254)},"Email",keyboard=KeyboardOptions(keyboardType=KeyboardType.Email),error=emailError!=null,supporting=emailError)
                PasswordField(password,{password=it.take(128);signupError=null},"Password")
                if(register) PasswordField(confirmation,{confirmation=it.take(128);signupError=null},"Confirm password",signupError)
                if(register && signupError==null) Text("Use at least 12 characters.",color=Muted,style=MaterialTheme.typography.bodySmall)
                Button(onClick={if(register) {
                    signupError=runCatching {RegistrationRules.validate(password,confirmation)}.exceptionOrNull()?.message
                    if(signupError==null) model.register(email,password)
                } else model.login(email,password)},enabled=!state.busy && state.config.configured && email.isNotBlank() && emailError==null && password.isNotBlank(),
                    shape=Ui.Field,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(register) "Create account" else "Sign in")}
                TextButton(onClick={model.dismissMessage();forgot=true},enabled=!state.busy,modifier=Modifier.align(Alignment.End)) {Text("Forgot password?")}
            }}
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                HorizontalDivider(Modifier.weight(1f));Text("  or  ",color=Muted,style=MaterialTheme.typography.labelMedium);HorizontalDivider(Modifier.weight(1f))
            }}
            item {Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(Ui.Tight)) {
                OutlinedButton(onClick=model::continueAsGuest,enabled=!state.busy,shape=Ui.Field,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("Continue as guest")}
                Text("Guest orders stay on this phone and move to your account when you sign in.",color=Muted,style=MaterialTheme.typography.bodySmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            }}
            if(!state.config.configured) item {Text("Cloud sign-in is not available yet. You can work as a guest.",color=Muted,style=MaterialTheme.typography.bodySmall)}
            item {TextButton(onClick={privacy=true}) {Text("Privacy")}}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodoEditor(original:Order?,busy:Boolean,serverError:String?,dismiss:()->Unit,repeatDraft:Order?=null,lookupCustomer:suspend (String)->Customer?={null},save:(Order)->Unit) {
    val existing=original ?: repeatDraft
    val context=LocalContext.current
    val imageScope=rememberCoroutineScope()
    val pickerTheme=if(MaterialTheme.colorScheme.background.red<.2f) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    var title by rememberSaveable(existing?.id) {mutableStateOf(existing?.title.orEmpty())}
    var mobile by rememberSaveable(existing?.id) {mutableStateOf(existing?.customerMobile.orEmpty())}
    // Order numbers are never typed: edits keep theirs, new and repeated orders get a fresh one.
    val orderNumber by rememberSaveable(existing?.id) {mutableStateOf(if(original!=null) original.orderNumber.ifBlank {OrderNumbers.generate()} else OrderNumbers.generate())}
    var attempted by remember {mutableStateOf(false)}
    var orderedAt by rememberSaveable(existing?.id) {mutableLongStateOf(existing?.orderedAt ?: System.currentTimeMillis())}
    var notes by rememberSaveable(existing?.id) {mutableStateOf(existing?.notes.orEmpty())}
    var priority by rememberSaveable(existing?.id) {mutableStateOf(existing?.priority ?: "Medium")}
    var due by rememberSaveable(existing?.id) {mutableStateOf(existing?.dueAt)}
    var durationMinutes by rememberSaveable(existing?.id) {mutableIntStateOf(if(existing?.reminderAt!=null && existing.dueAt!=null) (kotlin.math.abs(existing.reminderAt-existing.dueAt)/60000).toInt().coerceIn(1,99999) else 30)}
    var reminderEnabled by rememberSaveable(existing?.id) {mutableStateOf(existing?.reminderAt!=null)}
    var reminderMode by rememberSaveable(existing?.id) {mutableStateOf(if(existing?.reminderAt!=null && existing.dueAt!=null && existing.reminderAt>existing.dueAt) "After promised time" else "Before promised time")}
    var durationPicker by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var rightEye by remember(existing?.id) {mutableStateOf(existing?.prescription?.distanceRight ?: EyePrescription())}
    var leftEye by remember(existing?.id) {mutableStateOf(existing?.prescription?.distanceLeft ?: EyePrescription())}
    var addRight by rememberSaveable(existing?.id) {mutableStateOf(existing?.prescription?.addRight.orEmpty())}
    var addLeft by rememberSaveable(existing?.id) {mutableStateOf(existing?.prescription?.addLeft.orEmpty())}
    var pd by rememberSaveable(existing?.id) {mutableStateOf(existing?.prescription?.pd.orEmpty())}
    var frameUri by rememberSaveable(existing?.id) {mutableStateOf(existing?.frame?.localImageUri)}
    var framePrice by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.framePaisa?.takeIf {it>0}?.let {(it/100).toString()}.orEmpty())}
    var lensPrice by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.lensPaisa?.takeIf {it>0}?.let {(it/100).toString()}.orEmpty())}
    var paid by rememberSaveable(existing?.id) {mutableStateOf(existing?.payments?.sumOf {it.amountPaisa}?.takeIf {it>0}?.let {(it/100).toString()}.orEmpty())}
    var extrasPrice by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.extrasPaisa?.takeIf {it>0}?.let {(it/100).toString()}.orEmpty())}
    var discount by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.discountPaisa?.takeIf {it>0}?.let {(it/100).toString()}.orEmpty())}
    var lensType by rememberSaveable(existing?.id) {mutableStateOf(existing?.lens?.let {it.material.ifBlank {it.coating}}.orEmpty())}
    var frameType by rememberSaveable(existing?.id) {mutableStateOf(existing?.frame?.let {listOf(it.brand,it.model).filter {part ->part.isNotBlank()}.joinToString(" ")}.orEmpty())}
    var nearRight by remember(existing?.id) {mutableStateOf(existing?.prescription?.nearRight)}
    var nearLeft by remember(existing?.id) {mutableStateOf(existing?.prescription?.nearLeft)}
    var linkedCustomerId by rememberSaveable(existing?.id) {mutableStateOf(existing?.customerId)}
    var knownCustomer by remember {mutableStateOf<Customer?>(null)}
    var prefilledFor by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(mobile) {
        knownCustomer=null
        if(original==null && PakistanPhone.normalize(mobile)!=null) {
            kotlinx.coroutines.delay(350)
            try {
                val customer=lookupCustomer(mobile);knownCustomer=customer
                // An existing customer's details and latest prescription fill the form automatically (only into untouched fields).
                if(customer!=null && prefilledFor!=customer.id) {
                    prefilledFor=customer.id;linkedCustomerId=customer.id
                    if(title.isBlank()) title=customer.name
                    val untouched=rightEye==EyePrescription() && leftEye==EyePrescription() && nearRight==null && nearLeft==null && addRight.isBlank() && addLeft.isBlank() && pd.isBlank()
                    customer.latestPrescription?.takeIf {untouched}?.let {rx ->rightEye=rx.distanceRight;leftEye=rx.distanceLeft;nearRight=rx.nearRight;nearLeft=rx.nearLeft;addRight=rx.addRight.orEmpty();addLeft=rx.addLeft.orEmpty();pd=rx.pd.orEmpty()}
                }
            } catch(e:CancellationException) {throw e} catch(_:Exception) { /* Saving still validates and links the mobile atomically. */ }
        }
    }
    var importingPhoto by remember {mutableStateOf(false)}
    fun importPhoto(uri:android.net.Uri) {imageScope.launch {
        importingPhoto=true
        try {frameUri=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {FrameImages.import(context,uri)}}
        catch(e:Exception) {if(e is CancellationException) throw e;error=e.message ?: "Cannot save the photo."}
        finally {importingPhoto=false}
    }}
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->if(uri!=null) importPhoto(uri)}
    val cameraFile=remember {java.io.File(java.io.File(context.cacheDir,"camera").apply {mkdirs()},"${UUID.randomUUID()}.jpg")}
    val cameraUri=remember {androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",cameraFile)}
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {success ->if(success) importPhoto(cameraUri)}
    fun picker(initial:Long?,result:(Long)->Unit) {
        val chosen=Instant.ofEpochMilli(initial ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        DatePickerDialog(context,pickerTheme,{_,year,month,day ->TimePickerDialog(context,pickerTheme,{_,hour,minute ->result(LocalDate.of(year,month+1,day).atTime(hour,minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())},chosen.hour,chosen.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},chosen.year,chosen.monthValue-1,chosen.dayOfMonth).show()
    }
    fun amountOrZero(value:String)=if(FieldRules.amount(value,"")==null) Money.parse(value) else 0L
    val nameError=FieldRules.name(title).takeIf {attempted || title.isNotBlank()}
    val mobileError=FieldRules.mobile(mobile).takeIf {attempted || mobile.isNotBlank()}
    val frameError=FieldRules.amount(framePrice,"Frame price");val lensError=FieldRules.amount(lensPrice,"Lens price")
    val extrasError=FieldRules.amount(extrasPrice,"Extras");val discountFormat=FieldRules.amount(discount,"Discount")
    val paidFormat=FieldRules.amount(paid,"Amount paid")
    val gross=amountOrZero(framePrice)+amountOrZero(lensPrice)+amountOrZero(extrasPrice)
    val total=(gross-amountOrZero(discount)).coerceAtLeast(0)
    val discountError=discountFormat ?: FieldRules.discount(amountOrZero(discount),gross)
    val alreadyPaid=existing?.payments?.sumOf {it.amountPaisa} ?: 0L
    val paidError=paidFormat ?: FieldRules.paid(amountOrZero(paid),total) ?: if(original!=null && amountOrZero(paid)<alreadyPaid) "Paid can't be reduced here. Record a refund from the order details." else null
    val addRightError=FieldRules.add(addRight,"ADD OD");val addLeftError=FieldRules.add(addLeft,"ADD OS");val pdError=FieldRules.pd(pd)
    val frameTypeError=FieldRules.text(frameType,"Frame type",FieldRules.DetailMax)
    val lensTypeError=FieldRules.text(lensType,"Lens type",FieldRules.DetailMax);val notesError=FieldRules.notes(notes)
    val eyeError=FieldRules.eye(rightEye)?.let {"Right eye: $it"} ?: FieldRules.eye(leftEye)?.let {"Left eye: $it"}
        ?: nearRight?.let {FieldRules.eye(it)}?.let {"Near right: $it"} ?: nearLeft?.let {FieldRules.eye(it)}?.let {"Near left: $it"}
    fun submit() {try {
                attempted=true
                listOf(FieldRules.name(title),FieldRules.mobile(mobile),eyeError,addRightError,addLeftError,pdError,frameTypeError,lensTypeError,frameError,lensError,extrasError,discountError,paidError,notesError)
                    .firstNotNullOfOrNull {it}?.let {throw IllegalArgumentException(it)}
                val now=System.currentTimeMillis()
                val at=if(!reminderEnabled) null else when(reminderMode) {
                    "Before promised time" -> Rules.deadlineReminder(due,durationMinutes,true)
                    "After promised time" -> Rules.deadlineReminder(due,durationMinutes,false)
                    else -> null
                }
                val normalized=PakistanPhone.normalize(mobile) ?: throw IllegalArgumentException("Enter a valid Pakistani mobile number.")
                fun paisa(value:String,label:String):Long=if(value.isBlank()) 0 else ((value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a valid $label amount."))*java.math.BigDecimal(100)).longValueExact()
                check(!importingPhoto) {"Wait for the frame photo to finish saving."}
                val framePaisa=Money.parse(framePrice)
                val lensPaisa=Money.parse(lensPrice)
                val paidPaisa=Money.parse(paid)
                val oldPayments=existing?.payments.orEmpty()
                val payments=Money.payments(oldPayments,paidPaisa,now)
                val todo=(existing ?: Order(UUID.randomUUID().toString(),title)).copy(title=title.trim(),customerMobile=mobile.trim(),normalizedMobile=normalized,customerId=linkedCustomerId,orderNumber=orderNumber,
                    prescription=(existing?.prescription ?: Prescription()).copy(distanceRight=rightEye,distanceLeft=leftEye,nearRight=nearRight,nearLeft=nearLeft,addRight=addRight.ifBlank {null},addLeft=addLeft.ifBlank {null},pd=pd.ifBlank {null}),
                    frame=(existing?.frame ?: FrameDetails()).copy(localImageUri=frameUri,brand="",model=frameType.trim()),lens=(existing?.lens ?: LensDetails()).copy(material=lensType.trim(),coating=""),amounts=OrderAmounts(framePaisa,lensPaisa,Money.parse(extrasPrice),Money.parse(discount),0),payments=payments,
                    notes=notes.trim(),priority=priority,orderedAt=orderedAt,dueAt=due,reminderAt=at,updatedAt=now)
                nearRight?.let(OpticalRules::validateEye);nearLeft?.let(OpticalRules::validateEye)
                Rules.validate(todo,now,existing==null || at!=existing.reminderAt,existing==null || due!=existing.dueAt);save(todo)
            } catch(e:Exception) {error=e.message}}
    if(durationPicker) DurationPicker(durationMinutes,{durationPicker=false}) {durationMinutes=it;durationPicker=false}
    FullScreenForm(if(original==null) "New order" else "Edit order",dismiss,"Save order",busy || importingPhoto,{submit()}) {padding ->
        LazyColumn(contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item),modifier=Modifier.padding(padding).testTag("order-form")) {
            item {Text("Customer",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {AppTextField(title,{title=it.take(FieldRules.NameMax);error=null},"Customer name",error=nameError!=null,supporting=nameError ?: "${title.length}/${FieldRules.NameMax}")}
            item {AppTextField(mobile,{mobile=it.filter {c ->c.isDigit() || c in "+ -()"}.take(20);error=null},"Mobile number",placeholder="0300 1234567",keyboard=KeyboardOptions(keyboardType=KeyboardType.Phone),error=mobileError!=null,supporting=mobileError)}
            if(original!=null) item {Text("Order ${orderNumber}",style=MaterialTheme.typography.bodySmall,color=Muted)}
            item {Text("Distance prescription",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {EyePrescriptionEditor("OD · Right eye",rightEye,{rightEye=it})}
            item {EyePrescriptionEditor("OS · Left eye",leftEye,{leftEye=it})}
            item {TextButton(onClick={if(nearRight==null && nearLeft==null) {nearRight=EyePrescription();nearLeft=EyePrescription()} else {nearRight=null;nearLeft=null}}) {Text(if(nearRight==null) "+ Near prescription" else "Remove near prescription")}}
            nearRight?.let {eye ->item {EyePrescriptionEditor("Near · OD",eye) {nearRight=it}}}
            nearLeft?.let {eye ->item {EyePrescriptionEditor("Near · OS",eye) {nearLeft=it}}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                AppTextField(addRight,{addRight=FieldRules.decimalInput(it.replace("+",""),6)},"ADD OD",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=addRightError!=null,supporting=addRightError)
                AppTextField(addLeft,{addLeft=FieldRules.decimalInput(it.replace("+",""),6)},"ADD OS",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=addLeftError!=null,supporting=addLeftError)
            }}
            item {AppTextField(pd,{pd=FieldRules.decimalInput(it,5)},"PD (mm)",keyboard=KeyboardOptions(keyboardType=KeyboardType.Decimal),error=pdError!=null,supporting=pdError)}
            item {Text("Frame & payment",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {FramePreview(frameUri,Modifier.fillMaxWidth().heightIn(max=180.dp))}
            item {Row {
                TextButton(onClick={imagePicker.launch(arrayOf("image/*"))},enabled=!importingPhoto) {Text("Choose frame photo")}
                TextButton(onClick={runCatching {camera.launch(cameraUri)}.onFailure {error="No camera app is available."}},enabled=!importingPhoto) {Text("Camera")}
                if(frameUri!=null) TextButton(onClick={frameUri=null}) {Text("Remove")}
            }}
            item {AppTextField(frameType,{frameType=it.take(FieldRules.DetailMax)},"Frame type",error=frameTypeError!=null,supporting=frameTypeError)}
            item {AppTextField(lensType,{lensType=it.take(FieldRules.DetailMax)},"Lens type",error=lensTypeError!=null,supporting=lensTypeError)}
            item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                AppTextField(framePrice,{framePrice=FieldRules.wholeInput(it)},"Frame · PKR",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),error=frameError!=null,supporting=frameError)
                AppTextField(lensPrice,{lensPrice=FieldRules.wholeInput(it)},"Lenses · PKR",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),error=lensError!=null,supporting=lensError)
            }}
            item {AppTextField(paid,{paid=FieldRules.wholeInput(it)},"Amount paid · PKR",keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),error=paidError!=null,supporting=paidError ?: "Order total ${Money.pkr(total)} · Balance ${Money.pkr((total-amountOrZero(paid)).coerceAtLeast(0))}")}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                AppTextField(extrasPrice,{extrasPrice=FieldRules.wholeInput(it)},"Extras · PKR",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),error=extrasError!=null,supporting=extrasError)
                AppTextField(discount,{discount=FieldRules.wholeInput(it)},"Discount · PKR",Modifier.weight(1f),fill=false,keyboard=KeyboardOptions(keyboardType=KeyboardType.Number),error=discountError!=null,supporting=discountError)
            }}
            item {OutlinedButton(onClick={picker(orderedAt) {orderedAt=it}}) {Text("Order date · ${date(orderedAt)}")}}
            item {AppTextField(notes,{notes=it.take(FieldRules.NotesMax)},"Order notes",singleLine=false,minLines=2,error=notesError!=null,supporting=notesError ?: "${notes.length}/${FieldRules.NotesMax}")}
            item {Row(verticalAlignment=Alignment.CenterVertically) {OutlinedButton(onClick={picker(due) {due=it;error=null}},modifier=Modifier.weight(1f)) {Icon(DaymarkIcons.Event,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(due==null) "Add promised date" else date(due))};if(due!=null) TipIconButton(DaymarkIcons.Close,"Remove promised date",{due=null})}}
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Text("Additional reminder",Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleSmall)
                    HelpTip("Turn this on to add an alert before or after the promised-ready time.","About order reminders")
                }
                Switch(checked=reminderEnabled,onCheckedChange={reminderEnabled=it},modifier=Modifier.semantics {contentDescription="Order reminder"})
            }}
            if(reminderEnabled) item {
                if(due==null) Text("Add a promised date to set a reminder.",color=Muted,style=MaterialTheme.typography.bodySmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Before promised time","After promised time").forEachIndexed {index,mode ->
                        SegmentedButton(selected=reminderMode==mode,onClick={reminderMode=mode},shape=SegmentedButtonDefaults.itemShape(index,2),icon={}) {Text(mode)}
                    }
                }
            }
            if(reminderEnabled && due!=null) item {
                Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(DaymarkIcons.Timer,null,tint=Violet);Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(durationLabel(durationMinutes),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleLarge)
                                Text(if(reminderMode=="Before promised time") "Before promised time" else "After promised time",color=Muted,style=MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick={durationPicker=true}) {Text("Set time")}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(15,30,60).forEach {minutes -> FilterChip(selected=durationMinutes==minutes,onClick={durationMinutes=minutes},label={Text(durationLabel(minutes))})}
                        }
                        Text(date(Rules.deadlineReminder(due,durationMinutes,reminderMode=="Before promised time")),color=Muted,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            (error ?: serverError)?.let {item {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}}
        }
    }
}

@Composable
private fun EyePrescriptionEditor(label:String,value:EyePrescription,onChange:(EyePrescription)->Unit) {
    var addMenu by remember {mutableStateOf(false)}
    var showSphere by remember {mutableStateOf(value.sphere.isNotBlank())}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                Box {TextButton(onClick={addMenu=true}) {Icon(DaymarkIcons.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Optional")}
                    DropdownMenu(expanded=addMenu,onDismissRequest={addMenu=false}) {
                        if(!showSphere && value.sphere.isBlank()) DropdownMenuItem(text={Text("Sphere (SPH)")},onClick={showSphere=true;addMenu=false})
                        if(value.cylinder==null) DropdownMenuItem(text={Text("Add CYL + AXIS")},onClick={onChange(value.copy(cylinder="",axis=null));addMenu=false})
                        if(value.prism==null) DropdownMenuItem(text={Text("Add PRISM + BASE")},onClick={onChange(value.copy(prism="",base=null));addMenu=false})
                    }
                }
            }
            if(showSphere || value.sphere.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.sphere,{onChange(value.copy(sphere=it.filter {c ->c.isLetter() || c.isDigit() || c in "+-."}.take(7)))},label={Text("SPH")},placeholder={Text("Plano or -4.00")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                TipIconButton(DaymarkIcons.Close,"Remove SPH",{showSphere=false;onChange(value.copy(sphere=""))})
            }
            if(value.cylinder!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.cylinder,{onChange(value.copy(cylinder=it.filter {c ->c.isDigit() || c in "+-."}.take(7)))},label={Text("CYL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(value.axis?.toString().orEmpty(),{onChange(value.copy(axis=it.filter(Char::isDigit).take(3).toIntOrNull()))},label={Text("AXIS")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f))
                TipIconButton(DaymarkIcons.Close,"Remove CYL and AXIS",{onChange(value.copy(cylinder=null,axis=null))})
            }
            if(value.prism!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.prism,{onChange(value.copy(prism=it.filter {c ->c.isDigit() || c in "."}.take(7)))},label={Text("PRISM")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                var baseMenu by remember {mutableStateOf(false)}
                Box(Modifier.weight(1f)) {OutlinedButton(onClick={baseMenu=true},modifier=Modifier.fillMaxWidth()) {Text(value.base?.name ?: "BASE")};DropdownMenu(baseMenu,{baseMenu=false}) {PrismBase.entries.forEach {base ->DropdownMenuItem(text={Text(base.name)},onClick={onChange(value.copy(base=base));baseMenu=false})}}}
                TipIconButton(DaymarkIcons.Close,"Remove PRISM and BASE",{onChange(value.copy(prism=null,base=null))})
            }
            // "required" messages wait for Save; range and format problems show as soon as they are typed.
            FieldRules.eye(value)?.takeUnless {it.contains("is required")}?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

private fun durationLabel(minutes:Int):String = listOfNotNull(
    (minutes/1440).takeIf {it>0}?.let {"${it}d"},
    ((minutes%1440)/60).takeIf {it>0}?.let {"${it}h"},
    (minutes%60).takeIf {it>0}?.let {"${it}m"}
).joinToString(" ")

@Composable private fun DurationPicker(initial:Int,dismiss:()->Unit,save:(Int)->Unit) {
    val pickerTheme=if(MaterialTheme.colorScheme.background.red<.2f) android.R.style.Theme_Material else android.R.style.Theme_Material_Light
    var days by remember {mutableIntStateOf(initial/1440)}
    var hours by remember {mutableIntStateOf(initial%1440/60)}
    var minutes by remember {mutableIntStateOf(initial%60)}
    AlertDialog(onDismissRequest=dismiss,title={Text("Reminder offset")},text={
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Days",days,69),Triple("Hours",hours,23),Triple("Minutes",minutes,59)).forEachIndexed {index,(label,value,maximum) ->
                Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally) {
                    Text(label,color=Muted,style=MaterialTheme.typography.labelMedium)
                    androidx.compose.ui.viewinterop.AndroidView(factory={context -> android.widget.NumberPicker(android.view.ContextThemeWrapper(context,pickerTheme)).apply {
                        minValue=0;maxValue=maximum;this.value=value;wrapSelectorWheel=false
                        descendantFocusability=android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS
                        contentDescription=label
                        setOnValueChangedListener {_,_,chosen -> when(index) {0->days=chosen;1->hours=chosen;else->minutes=chosen}}
                    }},modifier=Modifier.height(140.dp).fillMaxWidth())
                }
            }
        }
    },confirmButton={TextButton(onClick={save(days*1440+hours*60+minutes)},enabled=days*1440+hours*60+minutes in 1..99999) {Text("Reminder offset")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
}

@Composable
fun SplashScreen() {
    val app=LocalContext.current.applicationContext as DaymarkApp
    val appearance by app.branding.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalBrand provides appearance) {
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val compact=maxHeight<400.dp
        Column(Modifier.fillMaxWidth().heightIn(min=maxHeight).verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=16.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            val splashLogo=R.drawable.raheel_optics_logo
            Image(painterResource(splashLogo),LocalBrand.current.config.name+" logo",Modifier.size(if(compact) 48.dp else 112.dp))
            Spacer(Modifier.height(if(compact) 8.dp else 24.dp))
            Text(LocalBrand.current.config.name,color=MaterialTheme.colorScheme.onBackground,fontWeight=FontWeight.Bold,style=if(compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(LocalBrand.current.config.tagline,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(if(compact) 12.dp else 32.dp))
            DaymarkLoader(color=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(28.dp))
        }
        }
    }
    }
}

@Composable
private fun EmailCodeScreen(state:UiState,model:TodoViewModel) {
    var code by rememberSaveable {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    val reset=state.codeIsReset
    androidx.activity.compose.BackHandler {if(!state.busy) model.cancelCode()}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item {TextButton(onClick=model::cancelCode,enabled=!state.busy) {Text("Back")}}
            item {BrandLogo(Modifier.size(64.dp))}
            item {Text(if(reset) "Reset password" else "Confirm your email",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineLarge)}
            item {Text("Enter the 6-digit code we sent to ${state.codeEmail}.",color=Muted)}
            state.message?.let {item {Message(it,model::dismissMessage)}}
            item {OutlinedTextField(code,{code=it.filter(Char::isDigit).take(10)},label={Text("Code")},singleLine=true,enabled=!state.busy,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),modifier=Modifier.fillMaxWidth().testTag("email-code"))}
            if(reset) item {OutlinedTextField(password,{password=it},label={Text("New password (12+ characters)")},singleLine=true,enabled=!state.busy,
                visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth())}
            item {Button(onClick={model.verifyCode(code,if(reset) password else null)},enabled=!state.busy && code.length>=6 && (!reset || password.length>=12),
                modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(state.busy) "Checking…" else if(reset) "Set password and sign in" else "Confirm and sign in")}}
            item {TextButton(onClick=model::resendCode,enabled=!state.busy) {Text("Send a new code")}}
        }
    }
}

@Composable
private fun ForgotPasswordScreen(state:UiState,model:TodoViewModel,back:()->Unit) {
    var email by rememberSaveable {mutableStateOf("")}
    androidx.activity.compose.BackHandler {if(!state.busy) back()}
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.safeDrawingPadding().imePadding(),contentPadding=PaddingValues(28.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            item {TextButton(onClick=back,enabled=!state.busy) {Text("Back to sign in")}}
            item {BrandLogo(Modifier.size(64.dp))}
            item {Text("Forgot password",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineLarge)}
            item {Text("Enter your account email. We’ll send you a link to reset your password.",color=Muted)}
            state.message?.let {item {Message(it,model::dismissMessage)}}
            item {OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,enabled=!state.busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())}
            item {Button(onClick={model.reset(email)},enabled=!state.busy && state.config.configured && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(state.busy) "Sending…" else "Email me a code")}}
            if(!state.config.configured) item {Text("Service is being set up. Please try again later.",color=Muted)}
        }
    }
}
