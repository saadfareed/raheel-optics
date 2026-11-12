package pk.raheeloptics.orders.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import pk.raheeloptics.orders.core.*
import java.time.*

@Composable internal fun OrderDateRange(label:String,from:Long?,until:Long?,change:(Long?,Long?)->Unit) {
    val context=LocalContext.current
    val zone=ZoneId.of("Asia/Karachi")
    fun date(value:Long)=Instant.ofEpochMilli(value).atZone(zone).toLocalDate()
    fun choose(value:Long?,isEnd:Boolean) {
        val day=value?.let {date(if(isEnd) it-1 else it)} ?: LocalDate.now(zone)
        DatePickerDialog(context,{_,y,m,d ->val chosen=LocalDate.of(y,m+1,d);val millis=(if(isEnd) chosen.plusDays(1) else chosen).atStartOfDay(zone).toInstant().toEpochMilli();if(isEnd) change(from,millis) else change(millis,until)},day.year,day.monthValue-1,day.dayOfMonth).show()
    }
    Text(label,style=MaterialTheme.typography.titleSmall)
    Row {TextButton(onClick={choose(from,false)},modifier=Modifier.weight(1f)) {Text(from?.let {"From ${date(it)}"} ?: "From date")};TextButton(onClick={choose(until,true)},modifier=Modifier.weight(1f)) {Text(until?.let {"To ${date(it-1)}"} ?: "To date")};if(from!=null || until!=null) TextButton(onClick={change(null,null)}) {Text("Clear")}}
}
