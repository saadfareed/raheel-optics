package pk.raheeloptics.orders.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.core.*
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OpticalAnalyticsScreen(state:UiState,model:OrdersViewModel) {
    val context=LocalContext.current
    var query by remember {mutableStateOf(OrderAnalyticsQuery())}
    LaunchedEffect(query,state.snapshot.totals.revision,state.session?.userId) {model.loadOrderAnalytics(query)}
    val summary=state.orderAnalytics ?: OrderAnalyticsSummary()
    fun datePicker(initial:LocalDate,change:(LocalDate)->Unit) {DatePickerDialog(context,{_,y,m,d ->change(LocalDate.of(y,m+1,d))},initial.year,initial.monthValue-1,initial.dayOfMonth).show()}
    val statusColors=listOf(OrderStatus.ACTIVE to MaterialTheme.colorScheme.primary,OrderStatus.READY_FOR_PICKUP to Color(0xFFB77A12),
        OrderStatus.COMPLETE to MaterialTheme.colorScheme.secondary,OrderStatus.CANCEL to MaterialTheme.colorScheme.outline)
    LazyColumn(contentPadding=PaddingValues(start=Ui.Gutter,end=Ui.Gutter,top=Ui.Tight,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
        item {Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
            OrderAnalyticsPeriod.entries.forEach {period ->FilterChip(selected=query.period==period,onClick={query=query.copy(period=period)},label={Text(period.label,maxLines=1)})}
        }}
        if(query.period==OrderAnalyticsPeriod.CUSTOM) item {Row(horizontalArrangement=Arrangement.spacedBy(Ui.Tight)) {
            OutlinedButton(onClick={datePicker(query.from) {date ->query=query.copy(from=date)}},shape=Ui.Field,modifier=Modifier.weight(1f)) {Text("From ${query.from}",maxLines=1)}
            OutlinedButton(onClick={datePicker(query.through) {date ->query=query.copy(through=date)}},shape=Ui.Field,modifier=Modifier.weight(1f)) {Text("To ${query.through}",maxLines=1)}
        }}
        if(state.analyticsLoading) item {LogoLoader(Modifier.fillMaxWidth().padding(vertical=24.dp),"Loading analytics")}
        state.orderAnalyticsError?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        item {Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(Ui.Item)) {
            MetricTile("Sales",Money.pkr(summary.sales),Modifier.weight(1f))
            MetricTile("Collected",Money.pkr(summary.collected),Modifier.weight(1f),MaterialTheme.colorScheme.secondary)
        }}
        item {Row(Modifier.height(IntrinsicSize.Min),horizontalArrangement=Arrangement.spacedBy(Ui.Item)) {
            MetricTile("Outstanding",Money.pkr(summary.outstanding),Modifier.weight(1f),if(summary.outstanding>0) MaterialTheme.colorScheme.error else null)
            MetricTile("Orders","${summary.orders}",Modifier.weight(1f),detail="Avg ${Money.pkr(summary.average)}")
        }}
        item {AppCard {
            SectionLabel("Orders by status")
            val total=statusColors.sumOf {(s,_) ->summary.statuses[s] ?: 0}
            Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                if(total>0) statusColors.forEach {(s,c) ->val n=summary.statuses[s] ?: 0;if(n>0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(c))}
            }
            statusColors.forEach {(s,c) ->Row(verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(c,CircleShape));Spacer(Modifier.width(Ui.Tight))
                Text(s.label,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Text("${summary.statuses[s] ?: 0}",fontWeight=FontWeight.SemiBold)
            }}
        }}
        item {AppCard {KeyValueRow("Overdue orders","${summary.overdue}",emphasis=if(summary.overdue>0) MaterialTheme.colorScheme.error else null)}}
        item {Text("Sales and collections use the selected period; status, outstanding and overdue are current.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
    }
}

@Composable private fun MetricTile(label:String,value:String,modifier:Modifier=Modifier,tint:Color?=null,detail:String?=null) {
    Surface(modifier.fillMaxHeight(),shape=Ui.Card,color=MaterialTheme.colorScheme.surface,border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant.copy(alpha=.6f))) {
        Column(Modifier.padding(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=tint ?: MaterialTheme.colorScheme.onSurface,maxLines=1,
                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            detail?.let {Text(it,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}
        }
    }
}
