package com.daymark.todo.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.location.*
import android.os.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.daymark.todo.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.time.*
import kotlin.coroutines.resume

object ShopPreferences {
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    fun load(context:Context):ShopSchedule {
        val p=context.getSharedPreferences("optical-shop",0)
        p.getString("schedule_v2",null)?.let {return json.decodeFromString(it)}
        fun legacy(value:String?):ShopDay {
            if(value.equals("closed",true)) return ShopDay(false)
            val times=value?.split(Regex("\\s*[–—]\\s*|\\s+-\\s+"))
            val interval=runCatching {val f=java.time.format.DateTimeFormatter.ofPattern("h:mm a",java.util.Locale.US);val a=LocalTime.parse(times!![0].uppercase(),f);val b=LocalTime.parse(times[1].uppercase(),f);OpeningInterval(a.hour*60+a.minute,b.hour*60+b.minute)}.getOrDefault(OpeningInterval())
            return ShopDay(true,listOf(interval))
        }
        return ShopSchedule(name=p.getString("name","Raheel Optics").orEmpty(),phone=p.getString("phone","").orEmpty(),address=p.getString("address","").orEmpty(),
            days=List(7) {legacy(p.getString(if(it==6) "sunday" else "weekdays",if(it==6) "Closed" else "10:00 AM – 8:00 PM"))})
    }
    fun save(context:Context,value:ShopSchedule) {
        value.validate()
        check(context.getSharedPreferences("optical-shop",0).edit().putString("schedule_v2",json.encodeToString(value)).putString("name",value.name).putString("phone",value.phone).putString("address",value.address).commit()) {"Couldn't save shop settings."}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FullScreenForm(title:String,close:()->Unit,saveLabel:String="Save",busy:Boolean=false,save:(()->Unit)?=null,content:@Composable (PaddingValues)->Unit) {
    Dialog(onDismissRequest={if(!busy) close()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Scaffold(modifier=Modifier.fillMaxSize().safeDrawingPadding().imePadding(),topBar={TopAppBar(title={Text(title)},navigationIcon={TextButton(onClick=close,enabled=!busy) {Text("Close")}})},
            bottomBar={if(save!=null) Surface {Button(onClick=save,enabled=!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=10.dp).heightIn(min=48.dp)) {Text(if(busy) "Saving…" else saveLabel)}}},content=content)
    }
}

@Composable internal fun ShopSettingsScreen(close:()->Unit) {
    val context=LocalContext.current
    var shop by remember {mutableStateOf(ShopPreferences.load(context))}
    var error by remember {mutableStateOf<String?>(null)}
    var locating by remember {mutableStateOf(false)}
    var exceptionDate by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    fun locate() {scope.launch {
        locating=true;error=null
        try {
            val point=withTimeout(20000) {currentShopLocation(context)}
            val address=withContext(Dispatchers.IO) {runCatching {@Suppress("DEPRECATION") android.location.Geocoder(context).getFromLocation(point.latitude,point.longitude,1)?.firstOrNull()?.getAddressLine(0)}.getOrNull()}
            shop=shop.copy(latitude=point.latitude,longitude=point.longitude,address=address ?: shop.address)
            if(address==null) error="Pin found. Address lookup unavailable; enter the address manually."
        } catch(e:CancellationException) {if(e !is TimeoutCancellationException) throw e;error="Location timed out. Enter the address manually or retry outdoors."}
        catch(_:Exception) {error="Location unavailable. Check location services or enter your address manually."}
        finally {locating=false}
    }}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {grants ->if(grants.values.any {it}) locate() else error="Location permission was declined. You can enter your address manually."}
    FullScreenForm("Shop details & hours",close,save={try {ShopPreferences.save(context,shop);close()} catch(e:Exception) {error=e.message}}) {padding ->
        LazyColumn(modifier=Modifier.padding(padding).testTag("shop-hours-form"),contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
            item {AppTextField(shop.name,{shop=shop.copy(name=it.take(120))},"Shop name")}
            item {AppTextField(shop.phone,{shop=shop.copy(phone=it.take(20));error=null},"Shop WhatsApp number",placeholder="0300 1234567",keyboard=KeyboardOptions(keyboardType=KeyboardType.Phone),
                supporting=if(shop.phone.isNotBlank() && PakistanPhone.normalize(shop.phone)==null) "Enter a valid Pakistani mobile number." else null,
                error=shop.phone.isNotBlank() && PakistanPhone.normalize(shop.phone)==null)}
            item {AppTextField(shop.address,{shop=shop.copy(address=it.take(500))},"Shop address",singleLine=false,minLines=2)}
            item {TextButton(enabled=!locating,onClick={permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}) {Text(if(locating) "Finding location…" else "Use current location")}}
            if(shop.latitude!=null && shop.longitude!=null) item {Column {
                TextButton(onClick={runCatching {context.startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("geo:${shop.latitude},${shop.longitude}?q=${shop.latitude},${shop.longitude}")))}.onFailure {error="No maps app is available."}}) {Text("View location pin")}
            }}
            item {SectionLabel("Weekly opening hours")}
            item {TextButton(onClick={shop=shop.copy(days=List(7) {shop.days.first()})}) {Text("Copy Monday's hours to all days")}}
            items((0..6).toList()) {index ->DayHoursEditor(DayOfWeek.of(index+1).getDisplayName(java.time.format.TextStyle.FULL,java.util.Locale.US),shop.days[index]) {day ->shop=shop.copy(days=shop.days.mapIndexed {i,old ->if(i==index) day else old})}}
            item {SectionLabel("Special dates")}
            item {AppTextField(exceptionDate,{exceptionDate=it.take(10)},"Date · YYYY-MM-DD")}
            item {TextButton(onClick={try {val date=LocalDate.parse(exceptionDate).toString();shop=shop.copy(exceptions=shop.exceptions+(date to ShopDay(false)));exceptionDate=""} catch(_:Exception) {error="Enter a date such as 2026-12-25."}}) {Text("Add special date")}}
            items(shop.exceptions.keys.sorted()) {date ->Column {
                DayHoursEditor(date,shop.exceptions.getValue(date)) {day ->shop=shop.copy(exceptions=shop.exceptions+(date to day))}
                TextButton(onClick={shop=shop.copy(exceptions=shop.exceptions-date)}) {Text("Remove special date")}
            }}
            error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        }
    }
}

@Composable private fun DayHoursEditor(label:String,day:ShopDay,change:(ShopDay)->Unit) {
    val context=LocalContext.current
    fun pick(value:Int,update:(Int)->Unit)=TimePickerDialog(context,{_,h,m ->update(h*60+m)},value/60,value%60,false).show()
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {Text(label,Modifier.weight(1f));Text(if(day.open) "Open" else "Closed");Switch(day.open,{change(day.copy(open=it))},modifier=Modifier.testTag("$label-hours"))}
        if(day.open) {
            day.intervals.forEachIndexed {i,span ->Row(verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick={pick(span.start) {t ->change(day.copy(intervals=day.intervals.mapIndexed {j,old ->if(j==i) old.copy(start=t) else old}))}},modifier=Modifier.weight(1f)) {Text("Open ${LocalTime.of(span.start/60,span.start%60)}")}
                TextButton(onClick={pick(span.end) {t ->change(day.copy(intervals=day.intervals.mapIndexed {j,old ->if(j==i) old.copy(end=t) else old}))}},modifier=Modifier.weight(1f)) {Text("Close ${LocalTime.of(span.end/60,span.end%60)}")}
                if(day.intervals.size>1) TextButton(onClick={change(day.copy(intervals=day.intervals.filterIndexed {j,_ ->j!=i}))}) {Text("−")}
            }}
            if(day.intervals.size<4) TextButton(onClick={change(day.copy(intervals=day.intervals+OpeningInterval(780,1020)))}) {Text("+ Add interval")}
        }
    }}
}

@Suppress("MissingPermission")
private suspend fun currentShopLocation(context:Context):Location = suspendCancellableCoroutine {cont ->
    val manager=context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val fine=context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED
    val provider=if(fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
    check(manager.isProviderEnabled(provider)) {"Location services disabled"}
    if(Build.VERSION.SDK_INT>=30) {
        val signal=CancellationSignal();cont.invokeOnCancellation {signal.cancel()}
        manager.getCurrentLocation(provider,signal,java.util.concurrent.Executor {Handler(Looper.getMainLooper()).post(it)}) {location ->if(cont.isActive) {if(location!=null) cont.resume(location) else cont.resumeWith(Result.failure(IllegalStateException("No location fix. Enter the address manually or try again outdoors.")))}}
    } else {
        val listener=object:LocationListener {
            override fun onLocationChanged(location:Location) {manager.removeUpdates(this);if(cont.isActive) cont.resume(location)}
            @Deprecated("Legacy") override fun onStatusChanged(provider:String?,status:Int,extras:Bundle?) {}
            override fun onProviderEnabled(provider:String) {}
            override fun onProviderDisabled(provider:String) {}
        }
        manager.requestSingleUpdate(provider,listener,Looper.getMainLooper());cont.invokeOnCancellation {manager.removeUpdates(listener)}
    }
}
