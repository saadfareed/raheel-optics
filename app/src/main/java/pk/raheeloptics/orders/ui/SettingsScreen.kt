package pk.raheeloptics.orders.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.delay
import java.time.*
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(state:UiState,allowed:Boolean,requestPermission:()->Unit,model:OrdersViewModel,onLogout:()->Unit) {
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
            Box(Modifier.size(48.dp).background(Violet.copy(alpha=.1f),CircleShape),contentAlignment=Alignment.Center) {Icon(AppIcons.Person,null,tint=Violet)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text("Account",fontWeight=FontWeight.SemiBold)
                Text(if(state.session?.localOnly==true) "Not signed in · tap to sign in" else state.session?.email.orEmpty(),color=Muted,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
            if(state.session?.localOnly==true) Icon(AppIcons.ChevronRight,null,tint=Muted)
        }}}
        if(pk.raheeloptics.orders.BuildConfig.DEMO_DATA) item {SettingsRow(AppIcons.Storage,"Load demo data","33 customers, about 50 orders (test builds only)",{model.loadDemoData()},enabled=!state.busy)}
        item {SettingsRow(AppIcons.Schedule,"Shop details & timings",ShopPreferences.load(context).phone.let {if(it.isBlank()) "Add your WhatsApp number — used in customer messages" else "WhatsApp $it · hours and address"},{shopDialog=true})}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface) {Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(if(allowed) AppIcons.NotificationsActive else AppIcons.NotificationsOff,null,tint=if(allowed) Mint else Muted)
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
            Icon(AppIcons.Lock,null,tint=Violet);Text("Change password",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Icon(AppIcons.ChevronRight,null,tint=Muted)
        }}}
        item {SettingsRow(AppIcons.Palette,"Appearance",LocalThemeChoice.current,{themeDialog=true})}
        item {Text("Security & privacy",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item {SettingsRow(AppIcons.PrivacyTip,"Privacy","How your account and orders are stored",{privacyDialog=true})}
        if(state.session?.localOnly==false) item {
            Box {
                SettingsRow(AppIcons.VerifiedUser,"Stay signed in",if(state.sessionIdleDays==0) "Until I sign out" else "${state.sessionIdleDays} days without opening the app",{sessionMenu=true})
                DropdownMenu(expanded=sessionMenu,onDismissRequest={sessionMenu=false}) {
                    SessionPolicy.options.forEach {days -> DropdownMenuItem(text={Text(if(days==0) "Until I sign out" else "$days days of inactivity")},onClick={model.setSessionIdleDays(days);sessionMenu=false},trailingIcon={if(days==state.sessionIdleDays) Icon(AppIcons.Check,null)})}
                }
            }
        }
        item {Text("Storage",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        item {Surface(shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(AppIcons.Storage,null,tint=Violet)
                    Column {Text("On this device",fontWeight=FontWeight.SemiBold);Text("Orders, customers and changes waiting to sync",color=Muted,style=MaterialTheme.typography.bodySmall)}
                }
                state.storageStats?.let {stats ->
                    Text("${stats.activeTasks} open orders · ${storageLabel(stats.allocatedBytes)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.queuedOperations>0) Text("${stats.queuedOperations} changes waiting to sync",style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(stats.quarantinedChanges>0) Text("${stats.quarantinedChanges} changes need recovery",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                }
                TextButton(onClick={storageExpanded=!storageExpanded}) {Text(if(storageExpanded) "Hide options" else "Manage storage");Icon(if(storageExpanded) AppIcons.ExpandLess else AppIcons.ExpandMore,null)}
                if(storageExpanded) {
                if(state.session?.localOnly==false) StorageAction(AppIcons.DeleteOutline,"Clear history cache",
                    "Remove downloaded history from this device.",
                    "Cloud history and pending changes stay safe. Download history again when online.",!state.busy) {clearCache=true}
                else Text("Completed and cancelled orders stay on this device for customer records and analytics. Export a backup regularly.",style=MaterialTheme.typography.bodySmall,color=Muted)
                StorageAction(AppIcons.Download,"Export orders or customers",
                    "Choose active orders, customers, or a combined backup.",
                    "Includes structured prescriptions and amounts, but not frame photo files. Keep customer data private.",!state.busy) {exportMenu=true}
                StorageAction(AppIcons.Upload,"Import orders & customers",
                    "Preview an optical export before adding records.",
                    "Known customer phone numbers are reused. Existing profiles are preserved. Imports are retry-safe; reminders and frame photos must be added again.",!state.busy) {import.launch(arrayOf("*/*"))}
                }
            }
        }}
        item {Text("Account actions",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)}
        if(state.session?.localOnly==false) item {SettingsRow(AppIcons.Logout,"Log out","Your saved orders stay on this device",onLogout,enabled=!state.busy)}
        item {SettingsRow(AppIcons.Storage,"Clear device data","Signs out and clears this account on this device",{clearDevice=true},enabled=!state.busy)}
        if(state.session?.localOnly==false) item {SettingsRow(AppIcons.DeleteOutline,"Delete account","Permanently remove your account and cloud data",{model.dismissMessage();deletionDialog=true},tint=Coral,enabled=!state.busy)}
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

@Composable internal fun StorageAction(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,detail:String,help:String,enabled:Boolean,onClick:()->Unit) {
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

internal fun storageLabel(bytes:Long):String=if(bytes<1024*1024) "${bytes/1024} KiB" else String.format(java.util.Locale.getDefault(),"%.1f MiB",bytes/1048576.0)

@Composable internal fun SettingsRow(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,detail:String,onClick:()->Unit,tint:Color=Violet,enabled:Boolean=true) {
    Surface(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(24.dp),color=MaterialTheme.colorScheme.surface,modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(44.dp).background(tint.copy(alpha=.08f),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {Icon(icon,null,tint=tint)}
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(title,fontWeight=FontWeight.SemiBold,color=if(tint==Coral) Coral else Ink)
                Text(detail,color=Muted,style=MaterialTheme.typography.bodySmall)
            }
            Icon(AppIcons.ChevronRight,null,tint=Muted)
        }
    }
}

@Composable internal fun PrivacyDialog(dismiss:()->Unit) {
    val uriHandler=androidx.compose.ui.platform.LocalUriHandler.current
    val policyUrl=pk.raheeloptics.orders.BuildConfig.PRIVACY_POLICY_URL
    val publicPolicy=runCatching { java.net.URI(policyUrl).let {it.scheme=="https" && !it.host.isNullOrBlank() && it.userInfo==null} }.getOrDefault(false)
    AlertDialog(onDismissRequest=dismiss,title={Text("Privacy")},text={Text("Cloud accounts store your email, customers, orders and payments with Supabase. Guest orders stay on this phone. Device databases use app-private storage without separate database encryption; session credentials use Android Keystore protection. Reminders use Android notifications and depend on device permissions. Released builds send crash reports (device model, Android version, app version and the error trace, never customer or order details) to Google Firebase Crashlytics so faults can be fixed. There is no advertising or analytics tracking. Account deletion removes live cloud records, but does not remotely erase other devices or exported files.",modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick=dismiss) {Text("Close")}},dismissButton={if(publicPolicy) TextButton(onClick={uriHandler.openUri(policyUrl)}) {Text("Full policy")}})
}

@Composable internal fun PasswordDialog(busy:Boolean,serverError:String?,dismiss:()->Unit,save:(String,String)->Unit) {
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

@Composable internal fun PasswordField(value:String,change:(String)->Unit,label:String,error:String?=null) {
    var visible by remember {mutableStateOf(false)}
    OutlinedTextField(value,change,label={Text(label)},singleLine=true,shape=Ui.Field,isError=error!=null,supportingText=error?.let {{Text(it)}},
        visualTransformation=if(visible) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),modifier=Modifier.fillMaxWidth(),
        trailingIcon={TipIconButton(if(visible) AppIcons.VisibilityOff else AppIcons.Visibility,if(visible) "Hide password" else "Show password",{visible=!visible})})
}
