package pk.raheeloptics.orders.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.R
import pk.raheeloptics.orders.core.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable internal fun BrandLogo(modifier:Modifier) {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val image=@Composable {m:Modifier -> Image(painterResource(R.drawable.raheel_optics_logo),LocalBrand.current.config.name+" logo",m)}
    if(dark) Box(modifier.background(Color.White,CircleShape).padding(4.dp),contentAlignment=Alignment.Center) {image(Modifier.fillMaxSize())} else image(modifier)
}

internal val codec=Json { ignoreUnknownKeys=true; encodeDefaults=true }

internal fun shortDate(time:Long):String=java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.of("Asia/Karachi")).format(DateTimeFormatter.ofPattern("d MMM yyyy",java.util.Locale.US))

internal fun date(time:Long?):String=time?.let {
    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d · h:mm a"))
} ?: "No promised date"

internal fun remaining(time:Long,now:Long):String {
    val minutes=(time-now)/60000
    return when { time<now -> "Overdue"; minutes<1 -> "Due now"; minutes<60 -> "${minutes}m left"; minutes<1440 -> "${minutes/60}h left"; else -> "${minutes/1440}d left" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun HelpTip(detail:String,label:String="More information") {
    val tooltip=rememberTooltipState(isPersistent=true)
    androidx.activity.compose.BackHandler(enabled=tooltip.isVisible) {tooltip.dismiss()}
    val scope=rememberCoroutineScope()
    TooltipBox(positionProvider=TooltipDefaults.rememberPlainTooltipPositionProvider(),tooltip={PlainTooltip {Text(detail)}},state=tooltip) {
        IconButton(onClick={scope.launch {tooltip.show()}},modifier=Modifier.size(40.dp)) {
            Icon(AppIcons.Info,label,modifier=Modifier.size(18.dp),tint=Muted)
        }
    }
}

@Composable internal fun Message(text:String,dismiss:()->Unit) {
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(text) {kotlinx.coroutines.delay(5000);currentDismiss()}
    Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.padding(horizontal=20.dp,vertical=8.dp)) {
        Row(Modifier.padding(start=14.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(text,Modifier.weight(1f).semantics {liveRegion=LiveRegionMode.Polite},style=MaterialTheme.typography.bodySmall)
            TipIconButton(AppIcons.Close,"Dismiss message",dismiss)
        }
    }
}

@Composable internal fun EmptyState(title:String,detail:String,icon:androidx.compose.ui.graphics.vector.ImageVector=AppIcons.CheckCircle) {
    Column(Modifier.fillMaxWidth().padding(vertical=56.dp,horizontal=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(Violet.copy(alpha=.08f),CircleShape),contentAlignment=Alignment.Center) {Icon(icon,null,Modifier.size(32.dp),tint=Violet)}
        Text(title,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
        Text(detail,style=MaterialTheme.typography.bodyMedium,color=Muted)
    }
}
