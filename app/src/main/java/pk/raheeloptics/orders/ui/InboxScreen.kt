package pk.raheeloptics.orders.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pk.raheeloptics.orders.core.*
import java.time.*

@Composable
internal fun InboxScreen(snapshot:Snapshot,allowed:Boolean,notificationSettings:()->Unit,model:OrdersViewModel,open:(String)->Unit) {
    LazyColumn(contentPadding=PaddingValues(Ui.Gutter),verticalArrangement=Arrangement.spacedBy(Ui.Item)) {
        if(!allowed) item {Surface(modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceVariant) {Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
                Icon(AppIcons.NotificationsOff,null,tint=Muted)
                Spacer(Modifier.weight(1f))
                HelpTip("Due alerts stay in Inbox when notifications are off.","About notification delivery")
            }
            Text("Notifications off",fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.titleMedium)
            Text("Due alerts still appear here. Turn on notifications to see them outside the app.",color=Muted,style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick=notificationSettings,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text("Open settings")}
        }}}
        if(snapshot.inbox.isEmpty()) item {EmptyState("No reminders","You're all caught up.",AppIcons.NotificationsNone)}
        items(snapshot.inbox,key={it.id}) {alert ->Card(onClick={model.readAlert(alert.id);open(alert.todoId)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),colors=CardDefaults.cardColors(containerColor=if(alert.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.fillMaxWidth().padding(18.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.Top) {
                Icon(AppIcons.Alarm,null,tint=Violet)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text(alert.title,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis);Text(date(alert.time),color=Muted,style=MaterialTheme.typography.labelSmall)
                    if(alert.todoId !in snapshot.activeTaskIds) Text("No longer active",color=Muted,style=MaterialTheme.typography.labelSmall)
                }
            }
        }}
    }
}
