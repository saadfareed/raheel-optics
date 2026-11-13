package pk.raheeloptics.orders.ui

import android.Manifest
import androidx.core.content.edit
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pk.raheeloptics.orders.RaheelApp
import pk.raheeloptics.orders.core.*
import java.time.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(target:String?,consumeTarget:()->Unit,model:OrdersViewModel=viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    androidx.activity.compose.ReportDrawnWhen {!state.loading && (state.session==null || !state.todosLoading)}
    val context=LocalContext.current
    val app=context.applicationContext as RaheelApp
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
                BadgedBox(badge={if(state.snapshot.inbox.any { !it.read }) Badge(containerColor=Coral)}) {Icon(AppIcons.Notifications,"Notifications")}
            }}},colors=TopAppBarDefaults.topAppBarColors(containerColor=Paper))},
            bottomBar={BottomNavigation(tab,{tab=it},{model.dismissMessage();adding=true;editor=null;repeatDraft=null},state.busy || state.loading)}
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                state.message?.let {Message(it,model::dismissMessage)}
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.loading) LogoLoader(Modifier.fillMaxSize())
                else when(tab) {
                    "Orders" -> OrdersScreen(state,model,{model.dismissMessage();details=it},{model.transition(it,"complete")},{deleting=it})
                    "Customers" -> CustomersScreen(state,model,{source ->repeatDraft=RepeatOrder.draft(source);editor=null;adding=true;model.dismissMessage()},{model.dismissMessage();details=it})
                    "Analytics" -> OpticalAnalyticsScreen(state,model)
                    "Inbox" -> InboxScreen(state.snapshot,allowed,notificationSettings,model) { id -> model.openTask(id) {todo -> editor=todo; if(todo!=null) tab="Orders"} }
                    else -> SettingsScreen(state,allowed,notificationSettings,model) {logout=true}
                }
            }
        }
        if(state.session!=null && (adding || editor!=null)) OrderEditor(editor,state.busy,state.message,{adding=false;editor=null;repeatDraft=null;model.dismissMessage()},repeatDraft,lookupCustomer={model.customerForMobile(it)}) {todo ->
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
internal fun BottomNavigation(tab:String,onSelect:(String)->Unit,onAdd:()->Unit,disabled:Boolean) {
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

@Composable internal fun AddTaskButton(onAdd:()->Unit,disabled:Boolean) {
    Tip("Add order") {FilledIconButton(onClick=onAdd,enabled=!disabled,
        colors=IconButtonDefaults.filledIconButtonColors(containerColor=Violet,contentColor=MaterialTheme.colorScheme.onPrimary),
        modifier=Modifier.size(56.dp),shape=CircleShape) {Icon(AppIcons.Add,"Add order")}}
}

@Composable internal fun NavigationDestination(name:String,tab:String,onSelect:(String)->Unit,modifier:Modifier,compact:Boolean) {
    val active=tab==name
    Column(modifier.heightIn(min=48.dp).clickable {onSelect(name)}.semantics {contentDescription=name;selected=active},horizontalAlignment=Alignment.CenterHorizontally) {
        Box(Modifier.size(if(compact) 32.dp else 48.dp).clip(RoundedCornerShape(16.dp))
            .background(if(active) Violet.copy(alpha=.1f) else Color.Transparent),contentAlignment=Alignment.Center) {
            Icon(when(name) {"Orders"->AppIcons.GridView;"Customers"->AppIcons.Person;"Analytics"->AppIcons.DonutLarge;else->AppIcons.Settings},null,tint=if(active) Violet else Muted)
        }
        Text(name,color=if(active) Violet else Muted,style=MaterialTheme.typography.labelSmall,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.fillMaxWidth())
    }
}
