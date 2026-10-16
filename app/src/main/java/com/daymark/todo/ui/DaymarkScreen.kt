package com.daymark.todo.ui

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
import androidx.compose.foundation.clickable
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
import com.daymark.todo.DaymarkApp
import com.daymark.todo.R
import com.daymark.todo.core.*
import com.daymark.todo.analytics.Analytics
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
private val LocalBrand=staticCompositionLocalOf {com.daymark.todo.branding.BrandAppearance(com.daymark.todo.branding.BrandConfig("Raheel Optics","Clear vision. Personal care."))}
@Composable private fun BrandLogo(modifier:Modifier) {
    Image(painterResource(R.drawable.raheel_optics_logo),LocalBrand.current.config.name+" logo",modifier)
}
private val codec=Json { ignoreUnknownKeys=true; encodeDefaults=true }
private fun date(time:Long?):String=time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d · h:mm a"))
} ?: "No promised date"
@Composable private fun priorityColor(priority:String)=when(priority) { "High" -> Coral; "Low" -> Mint; else -> Amber }
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
    val todoSaver=remember { Saver<Todo?,String>(save={it?.let(codec::encodeToString) ?: ""},restore={if(it.isBlank()) null else codec.decodeFromString<Todo>(it)}) }
    var editor by rememberSaveable(stateSaver=todoSaver) { mutableStateOf<Todo?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var repeatDraft by rememberSaveable(stateSaver=todoSaver) {mutableStateOf<Todo?>(null)}
    var deleting by remember { mutableStateOf<Todo?>(null) }
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
    DisposableEffect(tab,state.session?.userId) {onDispose {if(tab=="Customers") model.closeHistory(); if(tab=="Analytics") model.closeAnalytics()}}
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
            },actions={IconButton(onClick={tab="Inbox"}) {
                BadgedBox(badge={if(state.snapshot.inbox.any { !it.read }) Badge(containerColor=Coral)}) {Icon(DaymarkIcons.Notifications,"Notifications")}
            }},colors=TopAppBarDefaults.topAppBarColors(containerColor=Paper))},
            bottomBar={BottomNavigation(tab,{tab=it},{model.dismissMessage();adding=true;editor=null;repeatDraft=null},state.busy || state.loading)}
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {DaymarkLoader()}
                else when(tab) {
                    "Orders" -> HomeScreen(state,model,{model.dismissMessage();editor=it},{model.transition(it,"complete")},{deleting=it})
                    "Customers" -> CustomersScreen(state,model) {source ->repeatDraft=RepeatOrder.draft(source);editor=null;adding=true;model.dismissMessage()}
                    "Analytics" -> AnalyticsScreen(state,model)
                    "Inbox" -> InboxScreen(state.snapshot,allowed,notificationSettings,model) { id -> model.openTask(id) {todo -> editor=todo; if(todo!=null) tab="Orders"} }
                    else -> SettingsScreen(state,allowed,notificationSettings,model) {logout=true}
                }
            }
        }
        if(state.session!=null && (adding || editor!=null)) TodoEditor(editor,state.busy,state.message,{adding=false;editor=null;repeatDraft=null;model.dismissMessage()},repeatDraft) {todo ->
            model.save(todo,editor) {adding=false;editor=null;repeatDraft=null}
        }
        deleting?.let { todo -> AlertDialog(onDismissRequest={deleting=null},title={Text("Cancel order?")},
            text={Text("The order will stay in your records with status Cancel.")},confirmButton={TextButton(onClick={model.transition(todo,"delete");deleting=null},enabled=!state.busy) {Text("Cancel order",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={deleting=null}) {Text("Keep order")}}) }
        if(logout) AlertDialog(onDismissRequest={logout=false},title={Text(if(state.session?.localOnly==true) "Leave guest mode?" else "Sign out?")},
            text={Text(if(state.session?.localOnly==true) "Your guest orders will move to your account when you sign in." else if(state.snapshot.queuedCount>0) "Pending changes will resume after you sign in." else "Reminders pause until you sign in.")},
            confirmButton={TextButton(onClick={logout=false;model.logout()},enabled=!state.busy) {Text(if(state.session?.localOnly==true) "Continue to sign in" else "Sign out")}},
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
    FilledIconButton(onClick=onAdd,enabled=!disabled,
        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Violet,contentColor=MaterialTheme.colorScheme.onPrimary),
        modifier=Modifier.size(56.dp),shape=CircleShape) {Icon(DaymarkIcons.Add,"Add order")}
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
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
        Row(Modifier.padding(start=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text,Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
            IconButton(onClick=dismiss) {Icon(DaymarkIcons.Close,"Dismiss message")}
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

@Composable
private fun PageControls(number:Int,previous:Boolean,next:Boolean,loading:Boolean,first:()->Unit,back:()->Unit,forward:()->Unit) {
    Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick=back,enabled=previous && !loading) {Text("Previous")}
            Text("Page $number",style=MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick=forward,enabled=next && !loading) {Text("Next")}
        }
        if(number>1) TextButton(onClick=first,enabled=!loading) {Text("First page")}
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeScreen(state:UiState,model:TodoViewModel,onEdit:(Todo)->Unit,onComplete:(Todo)->Unit,onDelete:(Todo)->Unit) {
    val list=rememberLazyListState()
    var filters by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while(true) {now=System.currentTimeMillis();delay(60000)}
    } }
    LaunchedEffect(state.query,state.todoPageNumber,state.conflictPageNumber) {list.scrollToItem(0)}
    LazyColumn(state=list,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Your orders",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM")),style=MaterialTheme.typography.labelMedium,color=Muted)
        }}
        state.syncProblem?.let {problem -> item {Text(problem,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)} }
        item {OutlinedTextField(
            value=state.query.search,
            onValueChange={model.updateQuery(state.query.copy(search=it.take(40)))},
            placeholder={Text("Search name, phone or order…",maxLines=1,overflow=TextOverflow.Ellipsis)},
            shape=RoundedCornerShape(16.dp),
            colors=OutlinedTextFieldDefaults.colors(unfocusedBorderColor=MaterialTheme.colorScheme.outlineVariant,unfocusedContainerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.35f)),
            leadingIcon={Icon(DaymarkIcons.Search,null)},
            trailingIcon={if(state.query.search.isNotEmpty()) IconButton(onClick={model.updateQuery(state.query.copy(search=""))}) {Icon(DaymarkIcons.Close,"Clear search")}},
            singleLine=true,
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Text),
            modifier=Modifier.fillMaxWidth()
        )}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth()) {
            FilterChip(selected=state.query.orderStatus==null,onClick={model.updateQuery(state.query.copy(orderStatus=null))},label={Text("All ${state.snapshot.orderStatusCounts.values.sum()}")})
            OrderStatus.entries.forEach {status ->
                FilterChip(selected=state.query.orderStatus==status,onClick={model.updateQuery(state.query.copy(orderStatus=status))},label={Text("${status.label} ${state.snapshot.orderStatusCounts[status] ?: 0}")})
            }
        }}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Text(state.query.orderStatus?.label ?: "All orders",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
            TextButton(onClick={filters=true}) {Icon(DaymarkIcons.Tune,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Filter")}
            Box {
            TextButton(onClick={sortMenu=true}) {Text("Sort");Icon(DaymarkIcons.ExpandMore,null)}
            DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                listOf(TodoSort.Newest,TodoSort.Oldest,TodoSort.Deadline,TodoSort.LatestDeadline,TodoSort.Latest).forEach {sort ->
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
                val filtered=state.query.orderStatus!=null || state.query.deadline!=DeadlineFilter.Any || state.query.search.isNotBlank()
                EmptyState("No orders",if(filtered) "No customer orders match your search or filters." else "Tap + to add the first optical order.")
                if(filtered) FilledTonalButton(onClick={model.updateQuery(state.query.copy(priority=null,deadline=DeadlineFilter.Any,orderStatus=null,search=""))}) {
                    Icon(DaymarkIcons.FilterAltOff,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Remove filters")
                }
            }
        }
        items(state.todos,key={it.id}) {todo -> TodoCard(todo,now,state.busy,onEdit,onComplete,onDelete,{ready ->
            model.save(ready.copy(orderStatus=OrderStatus.READY_FOR_PICKUP,updatedAt=System.currentTimeMillis()),ready) {}
        },Modifier.animateItem())}
        if(state.todoPageNumber>1 || state.todosMore) item {
            PageControls(state.todoPageNumber,state.todoPrevious,state.todosMore,state.todosLoading,model::firstTodoPage,model::previousTodoPage,model::loadMoreTodos)
        }
        if(state.todosLoading) item {Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {DaymarkLoader(Modifier.size(22.dp),label="Loading more orders")}}
    }
    if(filters) FilterSheet(state.query,{filters=false}) {model.updateQuery(it);filters=false}
}

@Composable
private fun ProgressSummary(snapshot:Snapshot,online:Boolean,localOnly:Boolean,open:(Todo)->Unit,filter:(DeadlineFilter)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEE, d MMM")),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
            Text(when {snapshot.queuedCount>0 -> "${snapshot.queuedCount} pending";localOnly -> "On this device";online -> "Synced";else -> "Offline"},style=MaterialTheme.typography.labelSmall,color=Muted)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            SummaryMetric("Open",snapshot.totals.active,MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.colorScheme.onSurfaceVariant,Modifier.weight(1f)) {filter(DeadlineFilter.Any)}
            SummaryMetric("Today",snapshot.dueTodayCount,MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.colorScheme.onSurfaceVariant,Modifier.weight(1f)) {filter(DeadlineFilter.Today)}
            SummaryMetric("Overdue",snapshot.overdueCount,MaterialTheme.colorScheme.surfaceVariant,MaterialTheme.colorScheme.onSurfaceVariant,Modifier.weight(1f)) {filter(DeadlineFilter.Overdue)}
        }
    }
}

@Composable
private fun SummaryMetric(label:String,count:Int,background:Color,foreground:Color,modifier:Modifier=Modifier,horizontal:Boolean=false,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier,shape=RoundedCornerShape(18.dp),color=background) {
        if(horizontal) Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),color=foreground,style=MaterialTheme.typography.labelLarge)
            Text(count.toString(),color=foreground,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge)
        } else Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(count.toString(),color=foreground,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineSmall)
            Text(label,color=foreground,style=MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TodoCard(todo:Todo,now:Long,busy:Boolean,onEdit:(Todo)->Unit,onComplete:(Todo)->Unit,onDelete:(Todo)->Unit,onReady:(Todo)->Unit,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    var expanded by rememberSaveable(todo.id) { mutableStateOf(false) }
    val tint=when(todo.orderStatus) {OrderStatus.ACTIVE -> Violet;OrderStatus.READY_FOR_PICKUP -> Amber;OrderStatus.COMPLETE -> Mint;OrderStatus.CANCEL -> Muted}
    val canReady=todo.orderStatus.canMarkReady
    val swipe=rememberSwipeToDismissBoxState(confirmValueChange={target ->
        if(target==SwipeToDismissBoxValue.EndToStart || target==SwipeToDismissBoxValue.StartToEnd) {if(canReady && !busy) onReady(todo);false} else true
    })
    SwipeToDismissBox(state=swipe,enableDismissFromStartToEnd=canReady,enableDismissFromEndToStart=false,backgroundContent={
        Box(Modifier.fillMaxSize().background(Mint,RoundedCornerShape(22.dp)).padding(horizontal=24.dp),contentAlignment=Alignment.CenterStart) {Text("Mark ready",color=Color.White,fontWeight=FontWeight.Bold)}
    }) {Card(onClick={expanded=!expanded},modifier=modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(22.dp),
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
                if(!todo.orderStatus.isTerminal) IconButton(onClick={onEdit(todo)},enabled=!busy,modifier=Modifier.size(48.dp)) {Icon(DaymarkIcons.Edit,"Edit ${todo.title}",tint=Muted)}
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
                    if(!todo.orderStatus.isTerminal) TextButton(onClick={onDelete(todo)},enabled=!busy) {Text(OrderStatus.CANCEL.label,color=MaterialTheme.colorScheme.error)}
                }
            }
        }
    }}
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(query:TodoQuery,dismiss:()->Unit,apply:(TodoQuery)->Unit) {
    var status by rememberSaveable {mutableStateOf(query.orderStatus)}
    var deadline by rememberSaveable {mutableStateOf(query.deadline)}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(modifier=Modifier.navigationBarsPadding(),contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Order filters",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={status=null;deadline=DeadlineFilter.Any}) {Text("Reset")}}}
            item {Text("Order status",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                (listOf<OrderStatus?>(null)+OrderStatus.entries).forEach {value -> FilterChip(selected=status==value,onClick={status=value},label={Text(value?.label ?: "All")})}
            }}
            item {Text("Promised date",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DeadlineFilter.entries.forEach {value -> FilterChip(selected=deadline==value,onClick={deadline=value},label={Text(value.label)})}
            }}
            item {Button(onClick={apply(query.copy(priority=null,deadline=deadline,orderStatus=status))},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("Apply filters")}}
        }
    }
}

@Composable
private fun CustomersScreen(state:UiState,model:TodoViewModel,newOrder:(Todo)->Unit) {
    val query=state.customerQuery
    var sortMenu by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<Customer?>(null)}
    val list=rememberLazyListState()
    LaunchedEffect(query) {list.scrollToItem(0)}
    LazyColumn(state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {OutlinedTextField(value=query.search,onValueChange={model.updateCustomers(query.copy(search=it.take(80),page=1))},
            placeholder={Text("Search name or mobile")},leadingIcon={Icon(DaymarkIcons.Search,null)},
            trailingIcon={if(query.search.isNotEmpty()) IconButton(onClick={model.updateCustomers(query.copy(search="",page=1))}) {Icon(DaymarkIcons.Close,"Clear search")}},
            singleLine=true,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())}
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            FilterChip(selected=query.outstandingOnly,onClick={model.updateCustomers(query.copy(outstandingOnly=!query.outstandingOnly,page=1))},label={Text("Balance due")})
            Spacer(Modifier.weight(1f))
            Box {
                TextButton(onClick={sortMenu=true}) {Text(query.sort.label);Icon(DaymarkIcons.ExpandMore,null)}
                DropdownMenu(expanded=sortMenu,onDismissRequest={sortMenu=false}) {
                    CustomerSort.entries.forEach {sort ->DropdownMenuItem(text={Text(sort.label)},onClick={model.updateCustomers(query.copy(sort=sort,page=1));sortMenu=false})}
                }
            }
        }}
        item {Text("Tap a customer to start a new order.",style=MaterialTheme.typography.bodySmall,color=Muted)}
        if(state.customers.isEmpty()) item {
            EmptyState("No customers found",if(query.search.isNotBlank() || query.outstandingOnly) "Try another search or remove the balance filter." else "Your first saved order adds a customer here.",DaymarkIcons.Person)
        }
        items(state.customers,key={it.customer?.id ?: it.latestOrder.normalizedMobile}) {row ->
            val customer=row.latestOrder
            Card(onClick={newOrder(customer)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(42.dp).background(Violet.copy(alpha=.12f),CircleShape),contentAlignment=Alignment.Center) {Icon(DaymarkIcons.Person,null,tint=Violet)}
                        Column(Modifier.weight(1f)) {
                            Text(customer.title,fontWeight=FontWeight.SemiBold)
                            Text(customer.customerMobile,style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                        Icon(DaymarkIcons.Add,"New order")
                    }
                    Text(if(row.orderCount==0) "No orders stored on this device" else "${row.orderCount} order(s) · Last ${date(row.lastOrderAt)}",style=MaterialTheme.typography.labelSmall,color=Muted)
                    if(row.balancePaisa>0) Text("Balance PKR ${java.math.BigDecimal.valueOf(row.balancePaisa,2).toPlainString()}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)
                    row.customer?.let {customer -> TextButton(onClick={model.dismissMessage();editing=customer}) {Text("Customer details")}}
                    if(row.customer?.id in state.customerConflicts) Text("Customer changes need review",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        if(query.page>1 || state.customersMore) item {
            PageControls(query.page,query.page>1,state.customersMore,state.busy,
                {model.updateCustomers(query.copy(page=1))},
                {model.updateCustomers(query.copy(page=(query.page-1).coerceAtLeast(1)))},
                {model.updateCustomers(query.copy(page=query.page+1))})
        }
    }
    editing?.let {customer ->CustomerDetailsSheet(customer,state.busy,state.message,{editing=null;model.dismissMessage()},
        resolveConflict=if(customer.id in state.customerConflicts) {keep ->model.resolveCustomerConflict(customer.id,keep) {editing=null}} else null
    ) {updated ->model.saveCustomer(updated) {editing=null}}}
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
        LazyColumn(modifier=Modifier.imePadding().navigationBarsPadding().testTag("customer-profile-form"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {Text("Customer details",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
            item {Text("These details are used for future orders. Previous orders keep their original prescription.",style=MaterialTheme.typography.bodySmall,color=Muted)}
            if(resolveConflict!=null) item {Column {
                Text("The cloud profile changed or this phone belongs to another customer. Refresh Customers while online to compare versions. You can also correct the details below and Save to retry.",color=MaterialTheme.colorScheme.error)
                TextButton(enabled=!busy,onClick={resolveConflict(false)}) {Text("Use cloud version")}
                TextButton(enabled=!busy,onClick={resolveConflict(true)}) {Text("Retry my saved changes")}
            }}
            item {OutlinedTextField(name,{name=it.take(120)},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(mobile,{mobile=it.take(20)},label={Text("Mobile number")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())}
            item {Text("Latest prescription",fontWeight=FontWeight.SemiBold)}
            item {EyePrescriptionEditor("OD · Right eye",prescription.distanceRight) {prescription=prescription.copy(distanceRight=it)}}
            item {EyePrescriptionEditor("OS · Left eye",prescription.distanceLeft) {prescription=prescription.copy(distanceLeft=it)}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(prescription.addRight.orEmpty(),{prescription=prescription.copy(addRight=it.ifBlank {null})},label={Text("ADD OD")},modifier=Modifier.weight(1f))
                OutlinedTextField(prescription.addLeft.orEmpty(),{prescription=prescription.copy(addLeft=it.ifBlank {null})},label={Text("ADD OS")},modifier=Modifier.weight(1f))
            }}
            item {OutlinedTextField(prescription.pd.orEmpty(),{prescription=prescription.copy(pd=it.ifBlank {null})},label={Text("PD")},modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(notes,{notes=it.take(4000)},label={Text("Customer notes")},modifier=Modifier.fillMaxWidth())}
            (error ?: serverError)?.let {message ->item {Text(message,color=MaterialTheme.colorScheme.error)}}
            item {Button(enabled=!busy,onClick={try {
                val normalized=PakistanPhone.normalize(mobile) ?: error("Enter a valid Pakistani mobile number.")
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
private fun AnalyticsScreen(state:UiState,model:TodoViewModel) {
    val snapshot=state.snapshot
    val visibleOrders=(state.todos+snapshot.completedTodos.values).distinctBy {it.id}
    val bookedPaisa=visibleOrders.filter {it.orderStatus!=OrderStatus.CANCEL}.sumOf {it.amounts.totalPaisa}
    val collectedPaisa=visibleOrders.sumOf {order -> order.payments.sumOf {it.amountPaisa}}
    val outstandingPaisa=(bookedPaisa-collectedPaisa).coerceAtLeast(0)
    var days by rememberSaveable {mutableIntStateOf(7)}
    var periodMenu by remember {mutableStateOf(false)}
    var trendPriority by rememberSaveable {mutableStateOf<String?>(null)}
    val today=LocalDate.now()
    LaunchedEffect(days,state.session?.userId,snapshot.totals.revision) {model.loadAnalytics(days)}
    val summary=state.analyticsSummary
    val local=state.localAnalytics
    val overlay=state.pendingAnalytics
    val priorities=snapshot.priorityCounts
    val cloudTrend=if(trendPriority==null) summary?.trend else summary?.priorityTrends?.get(trendPriority)
    val baseTrend=cloudTrend ?: if(trendPriority==null) local?.trend else local?.priorityTrends?.get(trendPriority)
    val pendingTrend=if(cloudTrend==null) null else if(trendPriority==null) overlay?.trend else overlay?.priorityTrends?.get(trendPriority)
    val values=(0 until days).map {index -> (baseTrend?.getOrNull(index) ?: 0)+(pendingTrend?.getOrNull(index) ?: 0)}
    val withDeadline=((summary ?: local)?.withDeadline ?: 0)+(overlay?.withDeadline ?: 0)
    val onTime=if(withDeadline==0) null else (((summary ?: local)?.onTime ?: 0)+(overlay?.onTime ?: 0)).toFloat()/withDeadline
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Text("Business overview",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
        item {Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                SummaryMetric("Sales · PKR",(bookedPaisa/100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),MaterialTheme.colorScheme.primaryContainer,MaterialTheme.colorScheme.onPrimaryContainer,Modifier.weight(1f)) {}
                SummaryMetric("Collected · PKR",(collectedPaisa/100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.onSecondaryContainer,Modifier.weight(1f)) {}
            }
            SummaryMetric("Outstanding · PKR",(outstandingPaisa/100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),MaterialTheme.colorScheme.errorContainer,MaterialTheme.colorScheme.onErrorContainer,Modifier.fillMaxWidth(),horizontal=true) {}
            Text("Device view; the backend migration includes account-wide indexed reporting for the production dashboard.",style=MaterialTheme.typography.labelSmall,color=Muted)
        }}
        item {PortfolioChart(snapshot.orderStatusCounts)}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Text("Completions",Modifier.weight(1f,fill=false),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
                    HelpTip("Includes orders delivered again after reopening.","About deliveries")
                }
                Box {
                    TextButton(onClick={periodMenu=true}) {Text("Last $days days");Icon(DaymarkIcons.ExpandMore,null,Modifier.size(18.dp))}
                    DropdownMenu(expanded=periodMenu,onDismissRequest={periodMenu=false}) {
                        listOf(7,30).forEach {period -> DropdownMenuItem(text={Text("Last $period days")},onClick={days=period;periodMenu=false},trailingIcon={if(days==period) Icon(DaymarkIcons.Check,null)})}
                    }
                }
            }
            Text(values.sum().toString(),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            TrendChart(values,today,if(trendPriority==null) Violet else priorityColor(trendPriority!!))
            if(state.analyticsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(summary==null) "Saved deliveries · this device" else "Cloud deliveries · last $days days",color=Muted,style=MaterialTheme.typography.labelSmall)
        }}}
        item {Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFF24243B),modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(22.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {Text("On time",color=Color.White,fontWeight=FontWeight.SemiBold);Text("Orders with promised dates",color=Color(0xFFCBC9DE),style=MaterialTheme.typography.labelSmall)}
            Text(onTime?.let {"${(it*100).toInt()}%"} ?: "—",color=Color(0xFFB9F0D1),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium)
        }}}
    }
}

@Composable
private fun PortfolioChart(counts:Map<OrderStatus,Int>) {
    val values=OrderStatus.entries.map {counts[it] ?: 0}
    val colors=listOf(Violet,Amber,Mint,Muted)
    val total=values.sum()
    val track=Paper
    val animation by animateFloatAsState(if(total==0) 0f else 1f,tween(800),label="Portfolio chart")
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(132.dp).semantics {contentDescription=OrderStatus.entries.joinToString {"${it.label}: ${counts[it] ?: 0}"}},contentAlignment=Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke=16.dp.toPx()
                val diameter=size.minDimension-stroke
                val bounds=Size(diameter,diameter)
                val offset=Offset(stroke/2,stroke/2)
                drawArc(track,0f,360f,false,offset,bounds,style=Stroke(stroke))
                var angle=-90f
                values.forEachIndexed {index,value ->
                    val sweep=if(total==0) 0f else value.toFloat()/total*360f
                    if(sweep>0) drawArc(colors[index],angle,(sweep-4f).coerceAtLeast(1f)*animation,false,offset,bounds,style=Stroke(stroke,cap=StrokeCap.Round))
                    angle+=sweep
                }
            }
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text(total.toString(),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium);Text("orders",color=Muted,style=MaterialTheme.typography.labelSmall)}
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            OrderStatus.entries.map {it.label}.forEachIndexed {index,label ->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(colors[index],CircleShape));Text(label,Modifier.weight(1f),style=MaterialTheme.typography.labelMedium,color=Muted);Text(values[index].toString(),fontWeight=FontWeight.Bold)
            }}
        }
    }}
}

@Composable private fun TrendChart(values:List<Int>,today:LocalDate,color:Color) {
    val track=Paper
    val axis=Muted
    val maximum=(values.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Complete orders",style=MaterialTheme.typography.labelSmall,color=Muted)
        Canvas(Modifier.fillMaxWidth().height(168.dp).semantics {
            contentDescription=values.mapIndexed {i,count -> "${today.minusDays((values.size-1-i).toLong())}: $count completed"}.joinToString(", ")
        }) {
            val left=32.dp.toPx();val bottom=28.dp.toPx();val top=8.dp.toPx()
            val height=size.height-bottom-top;val width=size.width-left-4.dp.toPx()
            val text=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                this.color=android.graphics.Color.argb(255,(axis.red*255).toInt(),(axis.green*255).toInt(),(axis.blue*255).toInt());textSize=11.sp.toPx()
            }
            val ticks=listOf(0,maximum/2,maximum).distinct()
            ticks.forEach {tick ->
                val y=top+height*(1f-tick.toFloat()/maximum)
                drawLine(track,Offset(left,y),Offset(size.width,y),1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(tick.toString(),0f,y+4.dp.toPx(),text)
            }
            drawLine(axis.copy(alpha=.4f),Offset(left,top),Offset(left,top+height),1.dp.toPx())
            drawLine(axis.copy(alpha=.4f),Offset(left,top+height),Offset(size.width,top+height),1.dp.toPx())
            val slot=width/values.size.coerceAtLeast(1)
            values.forEachIndexed {index,value ->
                if(value>0) {
                    val barHeight=height*value/maximum
                    drawRoundRect(color,Offset(left+index*slot+slot*.18f,top+height-barHeight),Size(slot*.64f,barHeight),androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                }
            }
            listOf(0,(values.size-1)/2,values.size-1).distinct().filter {it>=0}.forEach {index ->
                val label=today.minusDays((values.size-1-index).toLong()).format(DateTimeFormatter.ofPattern("MMM d"))
                val x=(left+index*slot+slot/2-text.measureText(label)/2).coerceIn(left,(size.width-text.measureText(label)).coerceAtLeast(left))
                drawContext.canvas.nativeCanvas.drawText(label,x,size.height-5.dp.toPx(),text)
            }
        }
        Text("Date · ${ZoneId.systemDefault().id}",style=MaterialTheme.typography.labelSmall,color=Muted,modifier=Modifier.align(Alignment.CenterHorizontally))
        if(values.sum()==0) Text("No completions in this period.",color=Muted,style=MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun InboxScreen(snapshot:Snapshot,allowed:Boolean,notificationSettings:()->Unit,model:TodoViewModel,open:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
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
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {uri -> uri?.let(model::exportWorkspace)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> uri?.let(model::previewTaskImport)}
    LaunchedEffect(Unit) {model.loadStorageStats()}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(Violet.copy(alpha=.1f),CircleShape),contentAlignment=Alignment.Center) {Icon(DaymarkIcons.Person,null,tint=Violet)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text("Account",fontWeight=FontWeight.SemiBold);Text(if(state.session?.localOnly==true) "Sign in to your account" else state.session?.email.orEmpty(),color=Muted,style=MaterialTheme.typography.bodySmall)}
        }}}
        item {SettingsRow(DaymarkIcons.Schedule,"Shop details & timings","Used in pickup messages",{shopDialog=true})}
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
                    Column {Text("On this device",fontWeight=FontWeight.SemiBold);Text("Active tasks and changes waiting to sync",color=Muted,style=MaterialTheme.typography.bodySmall)}
                }
                state.storageStats?.let {stats ->
                    Text("${stats.activeTasks} active tasks · ${storageLabel(stats.allocatedBytes)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.queuedOperations>0) Text("${stats.queuedOperations} changes waiting to sync",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.quarantinedChanges>0) Text("${stats.quarantinedChanges} changes need recovery",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
                TextButton(onClick={storageExpanded=!storageExpanded}) {Text(if(storageExpanded) "Hide options" else "Manage storage");Icon(if(storageExpanded) DaymarkIcons.ExpandLess else DaymarkIcons.ExpandMore,null)}
                if(storageExpanded) {
                if(state.session?.localOnly==false) StorageAction(DaymarkIcons.DeleteOutline,"Clear history cache",
                    "Remove downloaded history from this device.",
                    "Cloud history and pending changes stay safe. Download history again when online.",!state.busy) {clearCache=true}
                else StorageAction(DaymarkIcons.DeleteOutline,"Clear local history",
                    "Remove finished-task history from this device.",
                    "This cannot be undone. Export first; active tasks and lifetime counts stay.",!state.busy) {clearLocalHistory=true}
                StorageAction(DaymarkIcons.Download,"Export device data",
                    "Save a copy of the data stored on this device.",
                    "The file contains readable task data, not passwords. It is a device snapshot, not a full cloud backup; store it privately.",!state.busy) {export.launch("daymark-export.jsonl")}
                StorageAction(DaymarkIcons.Upload,"Import active tasks",
                    "Add active-task copies from a Daymark export.",
                    "Creates new task copies and clears all reminders. Finished tasks, history and queued changes are skipped. Reopening the same file resumes safely.",!state.busy) {import.launch(arrayOf("*/*"))}
                }
            }
        }}
        item {Text("Account actions",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item {SettingsRow(DaymarkIcons.Logout,if(state.session?.localOnly==true) "Sign in" else "Sign out","Your saved tasks stay on this device",onLogout,enabled=!state.busy)}
        item {SettingsRow(DaymarkIcons.Storage,"Clear device data","Signs out and clears this account on this device",{clearDevice=true},enabled=!state.busy)}
        if(state.session?.localOnly==false) item {SettingsRow(DaymarkIcons.DeleteOutline,"Delete account","Permanently remove your account and cloud data",{model.dismissMessage();deletionDialog=true},tint=Coral,enabled=!state.busy)}
        item {Text(LocalBrand.current.config.name+" · "+com.daymark.todo.BuildConfig.VERSION_NAME,modifier=Modifier.fillMaxWidth(),color=Muted,style=MaterialTheme.typography.labelSmall)}
    }
    if(state.importActiveCount!=null) AlertDialog(onDismissRequest=model::cancelTaskImport,title={Text("Import task copies?")},text={Text("${state.importActiveCount} active tasks will be added with new IDs. ${state.importSkippedCount} completed/deleted tasks and all history/queued changes will be skipped. All reminders will be cleared. If a quota or storage limit stops the import, reopen the same file to resume without duplicating imported copies.")},confirmButton={TextButton(onClick=model::applyTaskImport,enabled=!state.busy && state.importActiveCount>0) {Text("Import tasks")}},dismissButton={TextButton(onClick=model::cancelTaskImport) {Text("Cancel")}})
    if(shopDialog) ShopSettingsDialog(context,{shopDialog=false})
    if(clearCache) AlertDialog(onDismissRequest={clearCache=false},title={Text("Clear offline history?")},text={Text("Cloud history and unsynced changes stay safe. You can download history again when online. Android may reuse the freed database space.")},confirmButton={TextButton(onClick={clearCache=false;model.clearHistoryCache()}) {Text("Clear cache")}},dismissButton={TextButton(onClick={clearCache=false}) {Text("Cancel")}})
    if(clearLocalHistory) AlertDialog(onDismissRequest={clearLocalHistory=false},title={Text("Clear local history?")},text={Text("These history entries have no cloud copy. Export device data first if you need them. Active tasks and lifetime counts stay; removed history cannot be undone.")},confirmButton={TextButton(onClick={clearLocalHistory=false;model.clearLocalHistory()}) {Text("Clear history")}},dismissButton={TextButton(onClick={clearLocalHistory=false}) {Text("Cancel")}})
    if(clearDevice) AlertDialog(onDismissRequest={clearDevice=false},title={Text("Clear device data?")},text={Text("This signs you out and clears this account’s local tasks and history. Cloud data stays in your account. Local-only tasks have no cloud copy; export first. Pending changes must be synced or resolved before clearing.")},confirmButton={TextButton(onClick={clearDevice=false;model.clearDeviceData()}) {Text("Clear device data")}},dismissButton={TextButton(onClick={clearDevice=false}) {Text("Cancel")}})
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
            Text("This permanently removes your account, tasks and history. Unsynced changes on this phone will also be removed.")
            PasswordField(current,{current=it},"Current password")
            state.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(onClick={model.deleteAccount(current)},enabled=!state.busy && current.isNotBlank()) {Text(if(state.busy) "Deleting…" else "Delete account")}},dismissButton={TextButton(onClick={deletionDialog=false},enabled=!state.busy) {Text("Cancel")}})
    }
    if(passwordDialog) PasswordDialog(state.busy,state.message,{passwordDialog=false}) {current,replacement ->model.changePassword(current,replacement) {passwordDialog=false}}
}

@Composable
private fun ShopSettingsDialog(context:android.content.Context,dismiss:()->Unit) {
    val prefs=remember {context.getSharedPreferences("optical-shop",android.content.Context.MODE_PRIVATE)}
    var name by rememberSaveable {mutableStateOf(prefs.getString("name","Raheel Optics") ?: "Raheel Optics")}
    var phone by rememberSaveable {mutableStateOf(prefs.getString("phone","") ?: "")}
    var address by rememberSaveable {mutableStateOf(prefs.getString("address","") ?: "")}
    var weekdays by rememberSaveable {mutableStateOf(prefs.getString("weekdays","10:00 AM – 8:00 PM") ?: "10:00 AM – 8:00 PM")}
    var sunday by rememberSaveable {mutableStateOf(prefs.getString("sunday","Closed") ?: "Closed")}
    var error by remember {mutableStateOf<String?>(null)}
    AlertDialog(onDismissRequest=dismiss,title={Text("Shop details & timings")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(name,{name=it.take(120)},label={Text("Shop name")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(phone,{phone=it.take(20);error=null},label={Text("Contact / WhatsApp")},placeholder={Text("0300 1234567")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(address,{address=it.take(300)},label={Text("Shop address")},minLines=2,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(weekdays,{weekdays=it.take(80)},label={Text("Monday–Saturday")},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(sunday,{sunday=it.take(80)},label={Text("Sunday")},singleLine=true,modifier=Modifier.fillMaxWidth())
        Text("Currency PKR · Time zone Asia/Karachi",style=MaterialTheme.typography.labelMedium,color=Muted)
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={TextButton(onClick={
        val normalized=PakistanPhone.normalize(phone)
        if(name.trim().length<2) error="Enter the shop name."
        else if(phone.isNotBlank() && normalized==null) error="Enter a valid Pakistani mobile number."
        else {prefs.edit {putString("name",name.trim());putString("phone",phone.trim());putString("normalized_phone",normalized.orEmpty());putString("address",address.trim());putString("weekdays",weekdays.trim());putString("sunday",sunday.trim())};dismiss()}
    }) {Text("Save")}},dismissButton={TextButton(onClick=dismiss) {Text("Cancel")}})
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
    val policyUrl=com.daymark.todo.BuildConfig.PRIVACY_POLICY_URL
    val publicPolicy=runCatching { java.net.URI(policyUrl).let {it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null} }.getOrDefault(false)
    AlertDialog(onDismissRequest=dismiss,title={Text("Privacy")},text={Text("Cloud accounts store email, account details, tasks and history with Supabase. Local-only task workspaces stay on this phone. Device task databases use app-private storage without separate database encryption; session credentials use Android Keystore protection. Reminders use Android notifications and depend on device permissions. The app has no advertising or third-party tracking SDKs. Account deletion removes live cloud records, but does not remotely erase other devices or exported files. Provider backup and operational-log retention periods are currently unconfirmed.",modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=dismiss) {Text("Close")}},dismissButton={if(publicPolicy) TextButton(onClick={uriHandler.openUri(policyUrl)}) {Text("Full policy")}})
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
@Composable private fun PasswordField(value:String,change:(String)->Unit,label:String) {
    var visible by remember {mutableStateOf(false)}
    OutlinedTextField(value,change,label={Text(label)},singleLine=true,visualTransformation=if(visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),trailingIcon={IconButton(onClick={visible=!visible}) {Icon(if(visible) DaymarkIcons.VisibilityOff else DaymarkIcons.Visibility,if(visible) "Hide password" else "Show password")}})
}

@Composable private fun AccountScreen(state:UiState,model:TodoViewModel) {
    var forgot by rememberSaveable {mutableStateOf(false)}
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
    Surface(color=Paper,modifier=Modifier.fillMaxSize()) {
        LazyColumn(contentPadding=PaddingValues(horizontal=28.dp,vertical=40.dp),verticalArrangement=Arrangement.spacedBy(20.dp),modifier=Modifier.imePadding().safeDrawingPadding()) {
            item {Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surface,shadowElevation=2.dp) {
                        BrandLogo(Modifier.size(72.dp).padding(6.dp))
                    }
                    Text(LocalBrand.current.config.name,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium,modifier=Modifier.weight(1f))
                }
                Text(if(register) "Create your account" else "Welcome back",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                Text(if(register) "Save customer orders and keep them in sync." else "Sign in to see your shop orders across devices.",color=Muted,style=MaterialTheme.typography.bodyMedium)
            }}
            state.message?.let {item {Message(it,model::dismissMessage)}}
            if(state.busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            item {OutlinedTextField(email,{email=it},label={Text("Email")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Email),modifier=Modifier.fillMaxWidth())}
            item {PasswordField(password,{password=it;signupError=null},"Password")}
            if(register) item {PasswordField(confirmation,{confirmation=it;signupError=null},"Confirm password")}
            if(register) item {Text("Use at least 12 characters.",color=Muted,style=MaterialTheme.typography.bodySmall)}
            signupError?.let {item {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}}
            item {Button(onClick={if(register) {
                signupError=runCatching {RegistrationRules.validate(password,confirmation)}.exceptionOrNull()?.message
                if(signupError==null) model.register(email,password)
            } else model.login(email,password)},enabled=!state.busy && state.config.configured,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(register) "Create account" else "Sign in")}}
            item {Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                TextButton(onClick={register=!register;signupError=null;confirmation=""},enabled=!state.busy) {Text(if(register) "Sign in instead" else "Create account")}
                TextButton(onClick={model.dismissMessage();forgot=true},enabled=!state.busy) {Text("Forgot password?")}
            }}
            item {HorizontalDivider()}
            item {OutlinedButton(onClick=model::continueAsGuest,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text("Continue as guest")}}
            item {Text("Guest orders sync to your account when you sign in.",color=Muted,style=MaterialTheme.typography.bodySmall)}
            item {TextButton(onClick={privacy=true},modifier=Modifier.fillMaxWidth()) {Text("Privacy")}}
            if(!state.config.configured) item {Text("Service is being set up. Please try again later.",color=Muted,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditor(original:Todo?,busy:Boolean,serverError:String?,dismiss:()->Unit,repeatDraft:Todo?=null,save:(Todo)->Unit) {
    val existing=original ?: repeatDraft
    val context=LocalContext.current
    val pickerTheme=if(MaterialTheme.colorScheme.background.red<.2f) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    var title by rememberSaveable(existing?.id) {mutableStateOf(existing?.title.orEmpty())}
    var mobile by rememberSaveable(existing?.id) {mutableStateOf(existing?.customerMobile.orEmpty())}
    var orderNumber by rememberSaveable(existing?.id) {mutableStateOf(existing?.orderNumber?.takeIf {it.isNotBlank()} ?: "RO-${java.time.format.DateTimeFormatter.ofPattern("yyMMdd-HHmm").format(java.time.LocalDateTime.now())}")}
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
    var framePrice by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.framePaisa?.takeIf {it>0 }?.let {"%.2f".format(java.util.Locale.US,it/100.0)}.orEmpty())}
    var lensPrice by rememberSaveable(existing?.id) {mutableStateOf(existing?.amounts?.lensPaisa?.takeIf {it>0 }?.let {"%.2f".format(java.util.Locale.US,it/100.0)}.orEmpty())}
    var paid by rememberSaveable(existing?.id) {mutableStateOf(existing?.payments?.sumOf {it.amountPaisa}?.takeIf {it>0}?.let {"%.2f".format(java.util.Locale.US,it/100.0)}.orEmpty())}
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {uri -> if(uri!=null) frameUri=uri.toString()}
    fun picker(initial:Long?,result:(Long)->Unit) {
        val chosen=Instant.ofEpochMilli(initial ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        DatePickerDialog(context,pickerTheme,{_,year,month,day ->TimePickerDialog(context,pickerTheme,{_,hour,minute ->result(LocalDate.of(year,month+1,day).atTime(hour,minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())},chosen.hour,chosen.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},chosen.year,chosen.monthValue-1,chosen.dayOfMonth).show()
    }
    if(durationPicker) DurationPicker(durationMinutes,{durationPicker=false}) {durationMinutes=it;durationPicker=false}
    ModalBottomSheet(onDismissRequest={if(!busy) dismiss()},containerColor=Paper) {
        LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.imePadding()) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(original==null) "New order" else "Edit order",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                IconButton(onClick=dismiss,enabled=!busy) {Icon(DaymarkIcons.Close,"Close task editor")}
            }}
            item {Text("Customer",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            if(repeatDraft!=null) item {Text("Review the copied prescription before saving. Previous orders keep their original prescription.",style=MaterialTheme.typography.bodySmall,color=Muted)}
            item {OutlinedTextField(title,{if(it.length<=Rules.MaxTitleLength) {title=it;error=null}},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(mobile,{mobile=it.take(20);error=null},label={Text("Mobile number")},placeholder={Text("0300 1234567")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(orderNumber,{orderNumber=it.take(40).uppercase()},label={Text("Order number")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Text("Distance prescription",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {EyePrescriptionEditor("OD · Right eye",rightEye,{rightEye=it})}
            item {EyePrescriptionEditor("OS · Left eye",leftEye,{leftEye=it})}
            item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(addRight,{addRight=it.take(7)},label={Text("ADD OD")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
                OutlinedTextField(addLeft,{addLeft=it.take(7)},label={Text("ADD OS")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
            }}
            item {OutlinedTextField(pd,{pd=it.take(7)},label={Text("PD (mm)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Text("Frame & payment",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {OutlinedButton(onClick={imagePicker.launch("image/*")},modifier=Modifier.fillMaxWidth()) {Icon(DaymarkIcons.Upload,null);Spacer(Modifier.width(8.dp));Text(if(frameUri==null) "Upload frame image" else "Frame image selected · Change")}}
            item {Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(framePrice,{framePrice=it.take(12)},label={Text("Frame · PKR")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
                OutlinedTextField(lensPrice,{lensPrice=it.take(12)},label={Text("Lenses · PKR")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.weight(1f))
            }}
            item {OutlinedTextField(paid,{paid=it.take(12)},label={Text("Amount paid · PKR")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(notes,{notes=it.take(4000)},label={Text("Order notes")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth())}
            item {Row(verticalAlignment=Alignment.CenterVertically) {OutlinedButton(onClick={picker(due) {due=it;error=null}},modifier=Modifier.weight(1f)) {Icon(DaymarkIcons.Event,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(due==null) "Add promised date" else date(due))};if(due!=null) IconButton(onClick={due=null}) {Icon(DaymarkIcons.Close,"Remove promised date")}}}
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Text("Additional reminder",Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleSmall)
                    HelpTip("Turn this on to add an alert before or after the promised-ready time.","About order reminders")
                }
                Switch(checked=reminderEnabled,onCheckedChange={reminderEnabled=it},modifier=Modifier.semantics {contentDescription="Task reminder"})
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
            item {Button(onClick={try {
                val now=System.currentTimeMillis()
                val at=if(!reminderEnabled) null else when(reminderMode) {
                    "Before promised time" -> Rules.deadlineReminder(due,durationMinutes,true)
                    "After promised time" -> Rules.deadlineReminder(due,durationMinutes,false)
                    else -> null
                }
                val normalized=PakistanPhone.normalize(mobile) ?: throw IllegalArgumentException("Enter a valid Pakistani mobile number.")
                fun paisa(value:String,label:String):Long=if(value.isBlank()) 0 else ((value.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a valid $label amount."))*java.math.BigDecimal(100)).longValueExact()
                val framePaisa=paisa(framePrice,"frame")
                val lensPaisa=paisa(lensPrice,"lens")
                val paidPaisa=paisa(paid,"paid")
                val oldPayments=existing?.payments.orEmpty()
                val payments=if(paidPaisa==oldPayments.sumOf {it.amountPaisa}) oldPayments else if(paidPaisa==0L) emptyList() else listOf(Payment(UUID.randomUUID().toString(),paidPaisa))
                val todo=(existing ?: Todo(UUID.randomUUID().toString(),title)).copy(title=title.trim(),customerMobile=mobile.trim(),normalizedMobile=normalized,orderNumber=orderNumber.trim(),
                    prescription=(existing?.prescription ?: Prescription()).copy(distanceRight=rightEye,distanceLeft=leftEye,addRight=addRight.ifBlank {null},addLeft=addLeft.ifBlank {null},pd=pd.ifBlank {null}),
                    frame=(existing?.frame ?: FrameDetails()).copy(localImageUri=frameUri),amounts=OrderAmounts(framePaisa=framePaisa,lensPaisa=lensPaisa),payments=payments,
                    notes=notes.trim(),priority=priority,dueAt=due,reminderAt=at,updatedAt=now)
                Rules.validate(todo,now,existing==null || at!=existing.reminderAt,existing==null || due!=existing.dueAt);save(todo)
            } catch(e:Exception) {error=e.message}},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(busy) "Saving…" else "Save order")}}
        }
    }
}

@Composable
private fun EyePrescriptionEditor(label:String,value:EyePrescription,onChange:(EyePrescription)->Unit) {
    var addMenu by remember {mutableStateOf(false)}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                Box {TextButton(onClick={addMenu=true}) {Icon(DaymarkIcons.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Optional")}
                    DropdownMenu(expanded=addMenu,onDismissRequest={addMenu=false}) {
                        if(value.cylinder==null) DropdownMenuItem(text={Text("Add CYL + AXIS")},onClick={onChange(value.copy(cylinder="",axis=1));addMenu=false})
                        if(value.prism==null) DropdownMenuItem(text={Text("Add PRISM + BASE")},onClick={onChange(value.copy(prism="",base=PrismBase.IN));addMenu=false})
                    }
                }
            }
            OutlinedTextField(value.sphere,{onChange(value.copy(sphere=it.take(7)))},label={Text("SPH")},placeholder={Text("Plano or -4.00")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(value.cylinder!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.cylinder,{onChange(value.copy(cylinder=it.take(7)))},label={Text("CYL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(value.axis?.toString().orEmpty(),{onChange(value.copy(axis=it.toIntOrNull()))},label={Text("AXIS")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f))
                IconButton(onClick={onChange(value.copy(cylinder=null,axis=null))}) {Icon(DaymarkIcons.Close,"Remove CYL and AXIS")}
            }
            if(value.prism!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.prism,{onChange(value.copy(prism=it.take(7)))},label={Text("PRISM")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                var baseMenu by remember {mutableStateOf(false)}
                Box(Modifier.weight(1f)) {OutlinedButton(onClick={baseMenu=true},modifier=Modifier.fillMaxWidth()) {Text(value.base?.name ?: "BASE")};DropdownMenu(baseMenu,{baseMenu=false}) {PrismBase.entries.forEach {base ->DropdownMenuItem(text={Text(base.name)},onClick={onChange(value.copy(base=base));baseMenu=false})}}}
                IconButton(onClick={onChange(value.copy(prism=null,base=null))}) {Icon(DaymarkIcons.Close,"Remove PRISM and BASE")}
            }
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
            item {Button(onClick={model.reset(email)},enabled=!state.busy && state.config.configured && android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches(),modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) {Text(if(state.busy) "Sending…" else "Send reset link")}}
            if(!state.config.configured) item {Text("Service is being set up. Please try again later.",color=Muted)}
        }
    }
}
