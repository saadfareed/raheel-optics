package pk.raheeloptics.orders.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Tappable examples are transient UI state; they never enter the order book. */
@Composable
fun OnboardingScreen(busy:Boolean,error:String?,onFinish:()->Unit) {
    var page by rememberSaveable {mutableIntStateOf(0)}
    val titles=listOf("Every order in one place","Promised dates, never missed","Ready, invoiced, delivered")
    val details=listOf("Customer, both-eye prescription, frame and payment are saved together. Order numbers are created for you.","Set when the glasses are promised and get a reminder before the customer arrives.","Mark an order Ready, send the customer a WhatsApp invoice with their dues, then complete it on pickup.")
    BackHandler(enabled=page>0) {if(!busy) page--}
    Surface(color=MaterialTheme.colorScheme.background,modifier=Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=Ui.Gutter,vertical=Ui.Tight),verticalAlignment=Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(pk.raheeloptics.orders.R.drawable.raheel_optics_logo),null,Modifier.size(44.dp))
                Spacer(Modifier.width(8.dp))
                Text(androidx.compose.ui.res.stringResource(pk.raheeloptics.orders.R.string.app_name),Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick=onFinish,enabled=!busy) {Text("Skip")}
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val wide=maxWidth>600.dp || maxWidth>maxHeight
                if(wide) Row(Modifier.fillMaxSize().padding(24.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) {OnboardingExample(page)}
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                        OnboardingCopy(page,titles[page],details[page])
                    }
                } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    OnboardingExample(page)
                    OnboardingCopy(page,titles[page],details[page])
                }
            }
            Column(Modifier.fillMaxWidth().padding(horizontal=Ui.Section,vertical=Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    repeat(3) {index ->
                        Box(Modifier.size(48.dp).clip(CircleShape).clickable(enabled=!busy) {page=index}
                            .semantics {contentDescription="Introduction ${index+1} of 3";selected=page==index},contentAlignment=Alignment.Center) {
                            Box(Modifier.size(if(page==index) 20.dp else 8.dp,8.dp).clip(CircleShape).background(if(page==index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
                        }
                    }
                }
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                Row(Modifier.widthIn(max=460.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(page>0) OutlinedButton(onClick={page--},enabled=!busy,shape=Ui.Field,modifier=Modifier.heightIn(min=52.dp)) {Text("Back")}
                    Button(onClick={if(page==2) onFinish() else page++},enabled=!busy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=Ui.Field) {
                        if(busy) {DaymarkLoader(Modifier.size(20.dp),MaterialTheme.colorScheme.onPrimary,"Saving introduction");Spacer(Modifier.width(12.dp))}
                        Text(if(page==2) "Let's begin" else "Next")
                    }
                }
            }
        }
    }
}

@Composable private fun OnboardingCopy(page:Int,title:String,detail:String) {
    Column(Modifier.widthIn(max=460.dp).fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text("${page+1} / 3",color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
        Text(title,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
        Text(detail,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun OnboardingExample(page:Int) {
    var done by rememberSaveable {mutableStateOf(false)}
    var reminder by rememberSaveable {mutableIntStateOf(30)}
    var finalTask by rememberSaveable {mutableStateOf(false)}
    val mint=Color(0xFF24735F)
    val paper=MaterialTheme.colorScheme.surface
    val violet=MaterialTheme.colorScheme.primary
    Box(Modifier.widthIn(max=460.dp).fillMaxWidth().heightIn(min=260.dp).clip(RoundedCornerShape(32.dp))
        .background(Brush.linearGradient(listOf(violet.copy(alpha=.08f),mint.copy(alpha=.08f)))).padding(24.dp),contentAlignment=Alignment.Center) {
        AnimatedContent(targetState=page,label="Introduction example") {step ->
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                when(step) {
                    0 -> {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(DaymarkIcons.Event,null,tint=violet)
                            Spacer(Modifier.width(8.dp));Text("New optical order",fontWeight=FontWeight.Bold)
                        }
                        Surface(shape=RoundedCornerShape(22.dp),color=paper,shadowElevation=2.dp) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(DaymarkIcons.Person,null,tint=violet,modifier=Modifier.size(40.dp).clickable {done=!done}.semantics {contentDescription="Open sample customer"})
                                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                    Text("Ayesha Khan",fontWeight=FontWeight.SemiBold)
                                    Text(if(done) "OD −4.00 · OS −5.00" else "0300 1234567 · RO-0001",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(if(done) DaymarkIcons.CheckCircle else DaymarkIcons.RadioButtonUnchecked,null,tint=if(done) mint else violet)
                            Text(if(done) "Prescription saved" else "Tap to preview prescription",color=if(done) mint else violet,style=MaterialTheme.typography.labelLarge)
                        }
                    }
                    1 -> {
                        Icon(DaymarkIcons.NotificationsActive,null,tint=violet,modifier=Modifier.size(40.dp))
                        Text("Promised-date reminder",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleLarge)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                            listOf(10,30,60).forEach {minutes ->
                                FilterChip(selected=reminder==minutes,onClick={reminder=minutes},label={Text(if(minutes==60) "1 hour" else "$minutes min")})
                            }
                        }
                        Surface(shape=RoundedCornerShape(20.dp),color=paper,shadowElevation=2.dp) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(DaymarkIcons.Alarm,null,tint=mint)
                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text("Ayesha Khan · RO-0001",fontWeight=FontWeight.SemiBold)
                                    Text("${if(reminder==60) "1 hour" else "$reminder minutes"} before promised time",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Text("Choose a reminder while adding or editing an order.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        Surface(shape=RoundedCornerShape(24.dp),color=Color(0xFF24243B)) {
                            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                                Text("Today at Raheel Optics",color=Color.White,style=MaterialTheme.typography.labelLarge)
                                Text(if(finalTask) "3 orders ready" else "2 orders ready",color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.headlineMedium)
                                val progress by animateFloatAsState(if(finalTask) 1f else 2f/3f,label="Sample progress")
                                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),color=Color(0xFFB9F0D1),trackColor=Color.White.copy(alpha=.16f))
                            }
                        }
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(DaymarkIcons.History,null,tint=mint)
                            Text("Ready orders can notify customers",style=MaterialTheme.typography.bodyMedium)
                        }
                        OutlinedButton(onClick={finalTask=!finalTask},modifier=Modifier.fillMaxWidth()) {Text(if(finalTask) "Reset preview" else "Mark sample order ready")}
                    }
                }
            }
        }
    }
}
