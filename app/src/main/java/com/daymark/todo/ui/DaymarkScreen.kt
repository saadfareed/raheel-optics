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
private val lightScheme=lightColorScheme(primary=Color(0xFF6651D6),onPrimary=Color.White,secondary=Color(0xFF24735F),background=Color(0xFFF7F7FC),
    surface=Color.White,surfaceVariant=Color(0xFFEDEBF8),onSurface=Color(0xFF24243B),onBackground=Color(0xFF24243B),onSurfaceVariant=Color(0xFF6D7085),error=Color(0xFFB64032))
private val darkScheme=darkColorScheme(primary=Color(0xFFC4B5FF),secondary=Color(0xFF81D9B5),background=Color(0xFF14131C),
    surface=Color(0xFF201F2B),surfaceVariant=Color(0xFF302D40),onSurface=Color(0xFFF0EDF8),onBackground=Color(0xFFF0EDF8),onSurfaceVariant=Color(0xFFBBB6CE),error=Color(0xFFFFB4A8))
private val LocalThemeChoice=staticCompositionLocalOf {"System"}
private val LocalSetTheme=staticCompositionLocalOf<(String)->Unit> {{}}
private val LocalBrand=staticCompositionLocalOf {com.daymark.todo.branding.BrandAppearance(com.daymark.todo.branding.BrandConfig("Raheel Optics","Clear vision. Personal care."))}
@Composable private fun BrandLogo(modifier:Modifier) {
    Image(painterResource(R.drawable.raheel_optics_logo),LocalBrand.current.config.name+" logo",modifier)
}
private val codec=Json { ignoreUnknownKeys=true; encodeDefaults=true }
private fun date(time:Long?):String=time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d · h:mm a"))
} ?: "No deadline"
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
        if(state.session==null && !state.loading) { tab="Orders"; editor=null; adding=false; deleting=null; logout=false }
    }
    LaunchedEffect(tab,state.session?.userId) { if(tab=="Customers" && state.session!=null) model.openHistory() }
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
            bottomBar={BottomNavigation(tab,{tab=it},{model.dismissMessage();adding=true;editor=null},state.busy || state.loading)}
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.loading) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {DaymarkLoader()}
                else when(tab) {
                    "Orders" -> HomeScreen(state,model,{model.dismissMessage();editor=it},{model.transition(it,"complete")},{deleting=it})
                    "Customers" -> HistoryScreen(state,model,{model.transition(it,"reopen")},{deleting=it})
                    "Analytics" -> AnalyticsScreen(state,model)
                    "Inbox" -> InboxScreen(state.snapshot,allowed,notificationSettings,model) { id -> model.openTask(id) {todo -> editor=todo; if(todo!=null) tab="Orders"} }
                    else -> SettingsScreen(state,allowed,notificationSettings,model) {logout=true}
                }
            }
        }
        if(state.session!=null && (adding || editor!=null)) TodoEditor(editor,state.busy,state.message,{adding=false;editor=null;model.dismissMessage()}) {todo ->
            model.save(todo,editor) {adding=false;editor=null}
        }
        deleting?.let { todo -> AlertDialog(onDismissRequest={deleting=null},title={Text("Delete todo?")},
            text={Text("It stays in your history.")},confirmButton={TextButton(onClick={model.transition(todo,"delete");deleting=null},enabled=!state.busy) {Text("Delete",color=MaterialTheme.colorScheme.error)}},
            dismissButton={TextButton(onClick={deleting=null}) {Text("Cancel")}}) }
        if(logout) AlertDialog(onDismissRequest={logout=false},title={Text(if(state.session?.localOnly==true) "Leave guest mode?" else "Sign out?")},
            text={Text(if(state.session?.localOnly==true) "Your guest tasks will move to your account when you sign in." else if(state.snapshot.queuedCount>0) "Pending changes will resume after you sign in." else "Reminders pause until you sign in.")},
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

@Composable
private fun HomeScreen(state:UiState,model:TodoViewModel,onEdit:(Todo)->Unit,onComplete:(Todo)->Unit,onDelete:(Todo)->Unit) {
    val list=rememberLazyListState()
    var filters by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while(true) {now=System.currentTimeMillis();delay(60000)}
    } }
    LaunchedEffect(state.query,state.todoPageNumber,state.conflictPageNumber) {list.scrollToItem(0)}
    LazyColumn(state=list,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {ProgressSummary(state.snapshot,state.online,state.session?.localOnly==true,onEdit) {model.updateQuery(TodoQuery(deadline=it,sort=TodoSort.Deadline))}}
        state.syncProblem?.let {problem -> item {Text(problem,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)} }
        item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Customer orders",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                Text(listOfNotNull(state.query.priority,state.query.deadline.takeIf {it!=DeadlineFilter.Any}?.label,state.query.sort.label).joinToString(" · "),style=MaterialTheme.typography.labelMedium,color=Muted)
            }
            FilledTonalIconButton(onClick={filters=true}) {Icon(DaymarkIcons.Tune,"Filter and sort")}
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
                val filtered=state.query.priority!=null || state.query.deadline!=DeadlineFilter.Any
                EmptyState("No orders",if(filtered) "No orders match your filters." else "Tap + to add the first optical order.")
                if(filtered) FilledTonalButton(onClick={model.updateQuery(state.query.copy(priority=null,deadline=DeadlineFilter.Any))}) {
                    Icon(DaymarkIcons.FilterAltOff,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("Remove filters")
                }
            }
        }
        items(state.todos,key={it.id}) {todo -> TodoCard(todo,now,state.busy,onEdit,onComplete,onDelete,Modifier.animateItem())}
        if(state.todoPageNumber>1 || state.todosMore) item {
            PageControls(state.todoPageNumber,state.todoPrevious,state.todosMore,state.todosLoading,model::firstTodoPage,model::previousTodoPage,model::loadMoreTodos)
        }
        if(state.todosLoading) item {Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {DaymarkLoader(Modifier.size(22.dp),label="Loading more tasks")}}
    }
    if(filters) FilterSheet(state.query,{filters=false}) {model.updateQuery(it);filters=false}
}

@Composable
private fun ProgressSummary(snapshot:Snapshot,online:Boolean,localOnly:Boolean,open:(Todo)->Unit,filter:(DeadlineFilter)->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()) {
    Surface(color=Color.Transparent,shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF24243B),Color(0xFF493987)))).padding(22.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMM d")),Modifier.weight(1f),color=Color(0xFFD9D3F4),style=MaterialTheme.typography.labelLarge)
                Icon(if(online && snapshot.queuedCount==0 && !localOnly) DaymarkIcons.CloudDone else DaymarkIcons.CloudOff,null,Modifier.size(18.dp),tint=Color(0xFFD9D3F4))
            }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(if(snapshot.totals.active==0) "Ready for the first order" else "Keep every order in sight",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineSmall)
                Text("${snapshot.totals.active} open orders",color=Color(0xFFE3DEF4),style=MaterialTheme.typography.bodyMedium)
            }
            snapshot.focusTask?.let {task ->
                Surface(onClick={open(task)},color=Color.White.copy(alpha=.1f),shape=RoundedCornerShape(16.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text("Next up",color=Color(0xFFB9F0D1),style=MaterialTheme.typography.labelSmall)
                            Text(task.title,color=Color.White,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(task.dueAt?.let {date(it)} ?: "${task.priority} priority",color=Color(0xFFD9D3F4),style=MaterialTheme.typography.labelSmall)
                        }
                        Icon(DaymarkIcons.ChevronRight,null,tint=Color.White)
                    }
                }
            }
            Text(when {localOnly -> "Saved on this device";snapshot.recoveryCount>0 -> "Changes need recovery";snapshot.conflictCount>0 -> "${snapshot.conflictCount} conflicts to review";snapshot.queuedCount>0 -> "${snapshot.queuedCount} changes waiting to sync";online -> "All changes synced";else -> "Saved offline"},color=Color(0xFFD9D3F4),style=MaterialTheme.typography.labelSmall)
        }
    }
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(28.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Your day",fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleMedium)
            if(LocalDensity.current.fontScale>1.3f) Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                SummaryMetric("Due today",snapshot.dueTodayCount,MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.onSecondaryContainer,Modifier.fillMaxWidth(),horizontal=true) {filter(DeadlineFilter.Today)}
                SummaryMetric("Overdue",snapshot.overdueCount,MaterialTheme.colorScheme.errorContainer,MaterialTheme.colorScheme.onErrorContainer,Modifier.fillMaxWidth(),horizontal=true) {filter(DeadlineFilter.Overdue)}
            } else Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
                SummaryMetric("Due today",snapshot.dueTodayCount,MaterialTheme.colorScheme.secondaryContainer,MaterialTheme.colorScheme.onSecondaryContainer,Modifier.weight(1f)) {filter(DeadlineFilter.Today)}
                SummaryMetric("Overdue",snapshot.overdueCount,MaterialTheme.colorScheme.errorContainer,MaterialTheme.colorScheme.onErrorContainer,Modifier.weight(1f)) {filter(DeadlineFilter.Overdue)}
            }
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("Done today",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=Muted)
                    Text(snapshot.completedTodayCount.toString(),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge,color=Mint)
                }
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoCard(todo:Todo,now:Long,busy:Boolean,onEdit:(Todo)->Unit,onComplete:(Todo)->Unit,onDelete:(Todo)->Unit,modifier:Modifier=Modifier) {
    var expanded by rememberSaveable(todo.id) { mutableStateOf(false) }
    val tint=priorityColor(todo.priority)
    val swipeState=rememberSwipeToDismissBoxState(confirmValueChange={value ->
        if(value==SwipeToDismissBoxValue.StartToEnd && !busy) {onComplete(todo);true} else false
    })

    SwipeToDismissBox(state=swipeState,enableDismissFromStartToEnd=!busy,enableDismissFromEndToStart=false,
        backgroundContent={
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Mint).padding(horizontal=24.dp),contentAlignment=Alignment.CenterStart) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(DaymarkIcons.CheckCircle,null,tint=Color.White)
                    Text("Glasses ready",color=Color.White,fontWeight=FontWeight.Bold)
                }
            }
        },modifier=modifier.fillMaxWidth()) {
    Card(onClick={onEdit(todo)},modifier=Modifier.fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(22.dp),
        colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface),elevation=CardDefaults.cardElevation(defaultElevation=1.dp),border=androidx.compose.foundation.BorderStroke(1.dp,tint.copy(alpha=.16f))) {
        Column(Modifier.drawBehind {drawRect(tint,Offset.Zero,Size(3.dp.toPx(),size.height))}.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(48.dp).background(Violet.copy(alpha=.08f),RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) {
                    Icon(DaymarkIcons.Person,"Customer",tint=Violet)
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(todo.title,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium,maxLines=if(expanded) Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
                    if(todo.customerMobile.isNotBlank()) Text(todo.customerMobile,style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(todo.notes.isNotBlank() && !expanded) Text(todo.notes,maxLines=1,overflow=TextOverflow.Ellipsis,color=Muted,style=MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Surface(color=tint.copy(alpha=.1f),shape=CircleShape) {Text(todo.priority+" priority",Modifier.padding(horizontal=10.dp,vertical=4.dp),style=MaterialTheme.typography.labelMedium,color=tint)}
                        if(todo.notes.isNotBlank()) Icon(DaymarkIcons.Notes,null,Modifier.size(15.dp),tint=Muted)
                    }
                }
                IconButton(onClick={onEdit(todo)},enabled=!busy,modifier=Modifier.size(48.dp)) {Icon(DaymarkIcons.Edit,"Edit ${todo.title}",tint=Muted)}
            }
            if(todo.dueAt!=null) {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Icon(DaymarkIcons.Schedule,null,Modifier.size(15.dp),tint=Muted)
                    Text(date(todo.dueAt),Modifier.padding(start=6.dp).weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
                    Text(remaining(todo.dueAt,now),style=MaterialTheme.typography.labelMedium,color=if(todo.dueAt<now) Coral else Violet)
                }
            }
            if(expanded) {
                if(todo.notes.isNotBlank()) Text(todo.notes,color=Muted,style=MaterialTheme.typography.bodyMedium)
                todo.reminderAt?.let {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    Icon(DaymarkIcons.Alarm,null,Modifier.size(16.dp),tint=Muted);Text("Reminder · ${date(it)}",style=MaterialTheme.typography.labelMedium,color=Muted)
                }}
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Updated ${date(todo.updatedAt)}",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,color=Muted)
                    TextButton(onClick={onDelete(todo)},enabled=!busy) {Icon(DaymarkIcons.DeleteOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Delete",color=MaterialTheme.colorScheme.error)}
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSheet(query:TodoQuery,dismiss:()->Unit,apply:(TodoQuery)->Unit) {
    var priority by rememberSaveable {mutableStateOf(query.priority)}
    var deadline by rememberSaveable {mutableStateOf(query.deadline)}
    var sort by rememberSaveable {mutableStateOf(query.sort)}
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=Paper) {
        LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {Row(verticalAlignment=Alignment.CenterVertically) {Text("Filter & sort",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={priority=null;deadline=DeadlineFilter.Any;sort=TodoSort.Latest}) {Text("Reset")}}}
            item {Text("Priority",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                listOf<String?>(null,"High","Medium","Low").forEach {value -> FilterChip(selected=priority==value,onClick={priority=value},label={Text(value ?: "Any")})}
            }}
            item {Text("End time",fontWeight=FontWeight.SemiBold);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                DeadlineFilter.entries.forEach {value -> FilterChip(selected=deadline==value,onClick={deadline=value},label={Text(value.label)})}
            }}
            item {Text("Sort by",fontWeight=FontWeight.SemiBold);Column {TodoSort.entries.forEach {value ->
                Surface(onClick={sort=value},color=Color.Transparent) {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {RadioButton(selected=sort==value,onClick={sort=value});Text(value.label)}}
            }}}
            item {Button(onClick={apply(TodoQuery(priority,deadline,sort))},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text("Apply")}}
        }
    }
}

@Composable
private fun HistoryScreen(state:UiState,model:TodoViewModel,reopen:(Todo)->Unit,delete:(Todo)->Unit) {
    val list=rememberLazyListState()
    LaunchedEffect(state.historyPageNumber) {list.scrollToItem(0)}
    LazyColumn(state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(state.history.isEmpty() && !state.historyLoading) item {
            EmptyState(if(state.historyError==null) "No history yet" else "Your history",if(state.historyError==null) "Completed tasks appear here." else "Connect to see your cloud history.",DaymarkIcons.History)
        }
        items(state.history,key={it.todo.id}) {h ->
            var expanded by rememberSaveable(h.todo.id) {mutableStateOf(false)}
            val color=when(h.action) {"delete"->Coral;"reopen"->Violet;else->Mint}
            Card(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth().animateItem().animateContentSize(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(42.dp).background(color.copy(alpha=.1f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {
                            Icon(when(h.action) {"delete"->DaymarkIcons.DeleteOutline;"reopen"->DaymarkIcons.Replay;else->DaymarkIcons.CheckCircle},null,tint=color)
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Text(h.todo.title,fontWeight=FontWeight.SemiBold,maxLines=if(expanded) Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
                            Text(when(h.action) {"delete"->"Deleted";"reopen"->"Reopened";else->"Completed"},color=color,style=MaterialTheme.typography.labelMedium)
                        }
                        Icon(if(expanded) DaymarkIcons.ExpandLess else DaymarkIcons.ExpandMore,null,tint=Muted)
                    }
                    Text(date(h.occurredAt),color=Muted,style=MaterialTheme.typography.labelSmall)
                    if(expanded) {
                        if(h.todo.notes.isNotBlank()) Text(h.todo.notes,color=Muted,style=MaterialTheme.typography.bodyMedium)
                        if(h.todo.dueAt!=null) Text("Deadline · ${date(h.todo.dueAt)}",style=MaterialTheme.typography.labelMedium,color=Muted)
                    }
                    val active=h.todo.id in state.snapshot.activeTaskIds
                    val conflicted=h.todo.id in state.snapshot.conflictedTaskIds
                    val currentState=state.snapshot.currentStates[h.todo.id]
                    if(h.action=="complete" && !active && !conflicted && (currentState==null || currentState==("completed" to h.todo.revision))) Row {
                        TextButton(onClick={reopen(h.todo)},enabled=!state.busy) {Icon(DaymarkIcons.Replay,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Reopen")}
                        TextButton(onClick={delete(h.todo)},enabled=!state.busy) {Text("Delete",color=MaterialTheme.colorScheme.error)}
                    }
                }
            }
        }
        if(state.historyCloudPage) item {TextButton(onClick=model::showSavedHistory,modifier=Modifier.fillMaxWidth()) {Text("Saved history")}}
        if(state.historyCloudPage && (state.historyPageNumber>1 || state.historyMore)) item {
            PageControls(state.historyPageNumber,state.cloudHistoryPrevious,state.historyMore,state.historyLoading,model::firstCloudHistory,model::previousHistoryPage,model::nextHistoryPage)
        } else if(state.historyPageNumber>1 || state.historyMore) item {
            PageControls(state.historyPageNumber,state.savedHistoryPrevious,state.historyMore,state.historyLoading,model::firstSavedHistory,model::previousSavedHistory,model::loadMoreHistory)
        }
        if(state.historyLoading) item {Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {DaymarkLoader(Modifier.size(22.dp),label="Loading history")}}
        if(state.historyError!=null) item {
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp)) {Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Icon(DaymarkIcons.CloudOff,null,tint=Muted);Spacer(Modifier.width(12.dp))
                Text(if(state.history.isEmpty()) "Cloud history is waiting to sync." else "Showing history saved on this device.",Modifier.weight(1f),color=Muted,style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=model::retryHistory) {Text("Refresh")}
            }}
        }
    }
}

@Composable
private fun AnalyticsScreen(state:UiState,model:TodoViewModel) {
    val snapshot=state.snapshot
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
        item {Text("Overview",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}
        item {PortfolioChart(snapshot.totals)}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("Active tasks by priority",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
            val total=priorities.values.sum().coerceAtLeast(1)
            listOf("High","Medium","Low").forEach {priority ->
                val count=priorities[priority] ?: 0
                val fraction by animateFloatAsState(count.toFloat()/total,tween(650),label="$priority priority")
                Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Row {Text(priority,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=Muted);Text(count.toString(),fontWeight=FontWeight.SemiBold)}
                    Box(Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).background(Paper)) {
                        if(fraction>0f) Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(priorityColor(priority)))
                    }
                }
            }
        }}}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Text("Completions",Modifier.weight(1f,fill=false),fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
                    HelpTip("Includes tasks completed again after reopening.","About completions")
                }
                Box {
                    TextButton(onClick={periodMenu=true}) {Text("Last $days days");Icon(DaymarkIcons.ExpandMore,null,Modifier.size(18.dp))}
                    DropdownMenu(expanded=periodMenu,onDismissRequest={periodMenu=false}) {
                        listOf(7,30).forEach {period -> DropdownMenuItem(text={Text("Last $period days")},onClick={days=period;periodMenu=false},trailingIcon={if(days==period) Icon(DaymarkIcons.Check,null)})}
                    }
                }
            }
            Text(values.sum().toString(),style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf<String?>(null,"High","Medium","Low").forEachIndexed {index,priority ->
                    SegmentedButton(selected=trendPriority==priority,onClick={trendPriority=priority},shape=SegmentedButtonDefaults.itemShape(index,4),icon={}) {
                        Text(priority ?: "All",maxLines=1,style=MaterialTheme.typography.labelMedium)
                    }
                }
            }
            TrendChart(values,today,if(trendPriority==null) Violet else priorityColor(trendPriority!!))
            if(state.analyticsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(summary==null) "Saved history · this device" else "Cloud history · last $days days",color=Muted,style=MaterialTheme.typography.labelSmall)
        }}}
        item {Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFF24243B),modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(22.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {Text("On time",color=Color.White,fontWeight=FontWeight.SemiBold);Text("Tasks with deadlines",color=Color(0xFFCBC9DE),style=MaterialTheme.typography.labelSmall)}
            Text(onTime?.let {"${(it*100).toInt()}%"} ?: "—",color=Color(0xFFB9F0D1),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium)
        }}}
    }
}

@Composable
private fun PortfolioChart(totals:Totals) {
    val values=listOf(totals.active,totals.completed,totals.deleted)
    val colors=listOf(Violet,Mint,Coral)
    val total=values.sum()
    val track=Paper
    val animation by animateFloatAsState(if(total==0) 0f else 1f,tween(800),label="Portfolio chart")
    Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Row(Modifier.fillMaxWidth().padding(22.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(132.dp).semantics {contentDescription="${totals.active} active, ${totals.completed} completed, ${totals.deleted} deleted"},contentAlignment=Alignment.Center) {
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
            Column(horizontalAlignment=Alignment.CenterHorizontally) {Text(total.toString(),fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium);Text("tasks",color=Muted,style=MaterialTheme.typography.labelSmall)}
        }
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            listOf("Active","Completed","Deleted").forEachIndexed {index,label ->Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
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
        Text("Completed tasks",style=MaterialTheme.typography.labelSmall,color=Muted)
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
    var storageExpanded by rememberSaveable {mutableStateOf(false)}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) {uri -> uri?.let(model::exportWorkspace)}
    val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri -> uri?.let(model::previewTaskImport)}
    LaunchedEffect(Unit) {model.loadStorageStats()}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(48.dp).background(Violet.copy(alpha=.1f),CircleShape),contentAlignment=Alignment.Center) {Icon(DaymarkIcons.Person,null,tint=Violet)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text("Account",fontWeight=FontWeight.SemiBold);Text(if(state.session?.localOnly==true) "Sign in to your account" else state.session?.email.orEmpty(),color=Muted,style=MaterialTheme.typography.bodySmall)}
        }}}
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
        item {SettingsRow(DaymarkIcons.PrivacyTip,"Privacy","How your account and tasks are stored",{privacyDialog=true})}
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
                Text(if(register) "Save your tasks and keep them in sync." else "Sign in to see your tasks across devices.",color=Muted,style=MaterialTheme.typography.bodyMedium)
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
            item {Text("Guest tasks sync to your account when you sign in.",color=Muted,style=MaterialTheme.typography.bodySmall)}
            item {TextButton(onClick={privacy=true},modifier=Modifier.fillMaxWidth()) {Text("Privacy")}}
            if(!state.config.configured) item {Text("Service is being set up. Please try again later.",color=Muted,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TodoEditor(existing:Todo?,busy:Boolean,serverError:String?,dismiss:()->Unit,save:(Todo)->Unit) {
    val context=LocalContext.current
    val pickerTheme=if(MaterialTheme.colorScheme.background.red<.2f) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert
    var title by rememberSaveable(existing?.id) {mutableStateOf(existing?.title.orEmpty())}
    var mobile by rememberSaveable(existing?.id) {mutableStateOf(existing?.customerMobile.orEmpty())}
    var orderNumber by rememberSaveable(existing?.id) {mutableStateOf(existing?.orderNumber?.takeIf {it.isNotBlank()} ?: "RO-${java.time.format.DateTimeFormatter.ofPattern("yyMMdd-HHmm").format(java.time.LocalDateTime.now())}")}
    var rightEye by remember(existing?.id) {mutableStateOf(existing?.prescription?.distanceRight ?: EyePrescription())}
    var leftEye by remember(existing?.id) {mutableStateOf(existing?.prescription?.distanceLeft ?: EyePrescription())}
    var notes by rememberSaveable(existing?.id) {mutableStateOf(existing?.notes.orEmpty())}
    var priority by rememberSaveable(existing?.id) {mutableStateOf(existing?.priority ?: "Medium")}
    var due by rememberSaveable(existing?.id) {mutableStateOf(existing?.dueAt)}
    var durationMinutes by rememberSaveable(existing?.id) {mutableIntStateOf(if(existing?.reminderAt!=null && existing.dueAt!=null) (kotlin.math.abs(existing.reminderAt-existing.dueAt)/60000).toInt().coerceIn(1,99999) else 30)}
    var reminderEnabled by rememberSaveable(existing?.id) {mutableStateOf(existing?.reminderAt!=null)}
    var reminderMode by rememberSaveable(existing?.id) {mutableStateOf(if(existing?.reminderAt!=null && existing.dueAt!=null && existing.reminderAt>existing.dueAt) "After deadline" else "Before deadline")}
    var durationPicker by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    fun picker(initial:Long?,result:(Long)->Unit) {
        val chosen=Instant.ofEpochMilli(initial ?: System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        DatePickerDialog(context,pickerTheme,{_,year,month,day ->TimePickerDialog(context,pickerTheme,{_,hour,minute ->result(LocalDate.of(year,month+1,day).atTime(hour,minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())},chosen.hour,chosen.minute,android.text.format.DateFormat.is24HourFormat(context)).show()},chosen.year,chosen.monthValue-1,chosen.dayOfMonth).show()
    }
    if(durationPicker) DurationPicker(durationMinutes,{durationPicker=false}) {durationMinutes=it;durationPicker=false}
    ModalBottomSheet(onDismissRequest={if(!busy) dismiss()},containerColor=Paper) {
        LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(14.dp),modifier=Modifier.imePadding()) {
            item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(existing==null) "New order" else "Edit order",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                IconButton(onClick=dismiss,enabled=!busy) {Icon(DaymarkIcons.Close,"Close task editor")}
            }}
            item {OutlinedTextField(title,{if(it.length<=Rules.MaxTitleLength) {title=it;error=null}},label={Text("Customer name")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(mobile,{mobile=it.take(20)},label={Text("Pakistani mobile number")},placeholder={Text("0300 1234567")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {OutlinedTextField(orderNumber,{orderNumber=it.take(40).uppercase()},label={Text("Order number")},singleLine=true,modifier=Modifier.fillMaxWidth())}
            item {Text("Distance prescription",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)}
            item {OpticalEyeEditor("OD · Right eye",rightEye) {rightEye=it}}
            item {OpticalEyeEditor("OS · Left eye",leftEye) {leftEye=it}}
            item {OutlinedTextField(notes,{notes=it.take(4000)},label={Text("Notes")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth())}
            item {Text("Priority",style=MaterialTheme.typography.labelLarge);FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("Low","Medium","High").forEach {FilterChip(selected=priority==it,onClick={priority=it},label={Text(it)})}}}
            item {Row(verticalAlignment=Alignment.CenterVertically) {OutlinedButton(onClick={picker(due) {due=it;error=null}},modifier=Modifier.weight(1f)) {Icon(DaymarkIcons.Event,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(due==null) "Add promised date" else date(due))};if(due!=null) IconButton(onClick={due=null}) {Icon(DaymarkIcons.Close,"Remove promised date")}}}
            item {Row(verticalAlignment=Alignment.CenterVertically) {
                Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically) {
                    Text("Additional reminder",Modifier.weight(1f,fill=false),style=MaterialTheme.typography.titleSmall)
                    HelpTip("A deadline alert is automatic. Turn this on to add another alert before or after the deadline.","About task reminders")
                }
                Switch(checked=reminderEnabled,onCheckedChange={reminderEnabled=it},modifier=Modifier.semantics {contentDescription="Task reminder"})
            }}
            if(reminderEnabled) item {
                if(due==null) Text("Add a deadline to set a reminder.",color=Muted,style=MaterialTheme.typography.bodySmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("Before deadline","After deadline").forEachIndexed {index,mode ->
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
                                Text(if(reminderMode=="Before deadline") "Before your deadline" else "After your deadline",color=Muted,style=MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick={durationPicker=true}) {Text("Set time")}
                        }
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            listOf(15,30,60).forEach {minutes -> FilterChip(selected=durationMinutes==minutes,onClick={durationMinutes=minutes},label={Text(durationLabel(minutes))})}
                        }
                        Text(date(Rules.deadlineReminder(due,durationMinutes,reminderMode=="Before deadline")),color=Muted,style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
            (error ?: serverError)?.let {item {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})}}
            item {Button(onClick={try {
                val now=System.currentTimeMillis()
                val at=if(!reminderEnabled) null else when(reminderMode) {
                    "Before deadline" -> Rules.deadlineReminder(due,durationMinutes,true)
                    "After deadline" -> Rules.deadlineReminder(due,durationMinutes,false)
                    else -> null
                }
                val normalized=PakistanPhone.normalize(mobile) ?: throw IllegalArgumentException("Enter a valid Pakistani mobile number.")
                val todo=(existing ?: Todo(UUID.randomUUID().toString(),title)).copy(title=title.trim(),customerMobile=mobile.trim(),normalizedMobile=normalized,orderNumber=orderNumber.trim(),prescription=Prescription(distanceRight=rightEye,distanceLeft=leftEye),notes=notes.trim(),priority=priority,dueAt=due,reminderAt=at,updatedAt=now)
                Rules.validate(todo,now,existing==null || at!=existing.reminderAt,existing==null || due!=existing.dueAt);save(todo)
            } catch(e:Exception) {error=e.message}},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {Text(if(busy) "Saving…" else "Save order")}}
        }
    }
}

@Composable private fun OpticalEyeEditor(label:String,value:EyePrescription,onChange:(EyePrescription)->Unit) {
    var menu by remember {mutableStateOf(false)}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp)) {Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Box {TextButton(onClick={menu=true}) {Icon(DaymarkIcons.Add,null);Text(" Optional")};DropdownMenu(menu,{menu=false}) {
            if(value.cylinder==null) DropdownMenuItem({Text("Add CYL + AXIS")},{onChange(value.copy(cylinder="",axis=1));menu=false})
            if(value.prism==null) DropdownMenuItem({Text("Add PRISM + BASE")},{onChange(value.copy(prism="",base=PrismBase.IN));menu=false})
        }}}
        OutlinedTextField(value.sphere,{onChange(value.copy(sphere=it.take(7)))},label={Text("SPH")},placeholder={Text("Plano or -4.00")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.fillMaxWidth())
        if(value.cylinder!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(value.cylinder,{onChange(value.copy(cylinder=it.take(7)))},label={Text("CYL")},modifier=Modifier.weight(1f));OutlinedTextField(value.axis?.toString().orEmpty(),{onChange(value.copy(axis=it.toIntOrNull()))},label={Text("AXIS")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f));IconButton({onChange(value.copy(cylinder=null,axis=null))}) {Icon(DaymarkIcons.Close,"Remove")}}
        if(value.prism!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {OutlinedTextField(value.prism,{onChange(value.copy(prism=it.take(7)))},label={Text("PRISM")},modifier=Modifier.weight(1f));TextButton(onClick={onChange(value.copy(base=PrismBase.entries[(value.base?.ordinal?.plus(1) ?: 0)%PrismBase.entries.size]))}) {Text(value.base?.name ?: "BASE")};IconButton({onChange(value.copy(prism=null,base=null))}) {Icon(DaymarkIcons.Close,"Remove")}}
    }}
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
