package pk.raheeloptics.orders.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.R
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.delay
import java.time.*
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrderEditor(original:Order?,busy:Boolean,serverError:String?,dismiss:()->Unit,repeatDraft:Order?=null,lookupCustomer:suspend (String)->Customer?={null},save:(Order)->Unit) {
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
            item {Row(verticalAlignment=Alignment.CenterVertically) {OutlinedButton(onClick={picker(due) {due=it;error=null}},modifier=Modifier.weight(1f)) {Icon(AppIcons.Event,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(due==null) "Add promised date" else date(due))};if(due!=null) TipIconButton(AppIcons.Close,"Remove promised date",{due=null})}}
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
                            Icon(AppIcons.Timer,null,tint=Violet);Spacer(Modifier.width(12.dp))
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
internal fun EyePrescriptionEditor(label:String,value:EyePrescription,onChange:(EyePrescription)->Unit) {
    var addMenu by remember {mutableStateOf(false)}
    var showSphere by remember {mutableStateOf(value.sphere.isNotBlank())}
    Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(label,Modifier.weight(1f),fontWeight=FontWeight.SemiBold)
                Box {TextButton(onClick={addMenu=true}) {Icon(AppIcons.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("Optional")}
                    DropdownMenu(expanded=addMenu,onDismissRequest={addMenu=false}) {
                        if(!showSphere && value.sphere.isBlank()) DropdownMenuItem(text={Text("Sphere (SPH)")},onClick={showSphere=true;addMenu=false})
                        if(value.cylinder==null) DropdownMenuItem(text={Text("Add CYL + AXIS")},onClick={onChange(value.copy(cylinder="",axis=null));addMenu=false})
                        if(value.prism==null) DropdownMenuItem(text={Text("Add PRISM + BASE")},onClick={onChange(value.copy(prism="",base=null));addMenu=false})
                    }
                }
            }
            if(showSphere || value.sphere.isNotBlank()) Row(verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.sphere,{onChange(value.copy(sphere=it.filter {c ->c.isLetter() || c.isDigit() || c in "+-."}.take(7)))},label={Text("SPH")},placeholder={Text("Plano or -4.00")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                TipIconButton(AppIcons.Close,"Remove SPH",{showSphere=false;onChange(value.copy(sphere=""))})
            }
            if(value.cylinder!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.cylinder,{onChange(value.copy(cylinder=it.filter {c ->c.isDigit() || c in "+-."}.take(7)))},label={Text("CYL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                OutlinedTextField(value.axis?.toString().orEmpty(),{onChange(value.copy(axis=it.filter(Char::isDigit).take(3).toIntOrNull()))},label={Text("AXIS")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,modifier=Modifier.weight(1f))
                TipIconButton(AppIcons.Close,"Remove CYL and AXIS",{onChange(value.copy(cylinder=null,axis=null))})
            }
            if(value.prism!=null) Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                OutlinedTextField(value.prism,{onChange(value.copy(prism=it.filter {c ->c.isDigit() || c in "."}.take(7)))},label={Text("PRISM")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),singleLine=true,modifier=Modifier.weight(1f))
                var baseMenu by remember {mutableStateOf(false)}
                Box(Modifier.weight(1f)) {OutlinedButton(onClick={baseMenu=true},modifier=Modifier.fillMaxWidth()) {Text(value.base?.name ?: "BASE")};DropdownMenu(baseMenu,{baseMenu=false}) {PrismBase.entries.forEach {base ->DropdownMenuItem(text={Text(base.name)},onClick={onChange(value.copy(base=base));baseMenu=false})}}}
                TipIconButton(AppIcons.Close,"Remove PRISM and BASE",{onChange(value.copy(prism=null,base=null))})
            }
            // "required" messages wait for Save; range and format problems show as soon as they are typed.
            FieldRules.eye(value)?.takeUnless {it.contains("is required")}?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
        }
    }
}

internal fun durationLabel(minutes:Int):String = listOfNotNull(
    (minutes/1440).takeIf {it>0}?.let {"${it}d"},
    ((minutes%1440)/60).takeIf {it>0}?.let {"${it}h"},
    (minutes%60).takeIf {it>0}?.let {"${it}m"}
).joinToString(" ")

@Composable internal fun DurationPicker(initial:Int,dismiss:()->Unit,save:(Int)->Unit) {
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
