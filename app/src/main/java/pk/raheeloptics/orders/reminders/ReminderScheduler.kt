package pk.raheeloptics.orders.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.core.content.edit
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import pk.raheeloptics.orders.MainActivity
import pk.raheeloptics.orders.R
import pk.raheeloptics.orders.core.TaskAlert
import pk.raheeloptics.orders.core.AlertKind
import pk.raheeloptics.orders.core.Order
import pk.raheeloptics.orders.core.ReminderPolicy
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import pk.raheeloptics.orders.data.OrderStore

interface ReminderEnvironment {
    val scope: CoroutineScope
    val reminders: ReminderScheduler
    suspend fun workspace(): OrderStore?
    suspend fun <T> withReminderWorkspace(action:suspend (OrderStore?)->T):T
}

fun enqueueReminderContinuation(context:Context) {
    WorkManager.getInstance(context).enqueueUniqueWork("restore-reminders",ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ReminderWorker>().setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL,30,java.util.concurrent.TimeUnit.SECONDS).build())
}

class ReminderScheduler(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private fun pending(account: String, id: String, time: Long = 0): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setData("raheel-optics://reminder/$account/$id".toUri()).putExtra("account",account).putExtra("todo",id.substringAfter(':',id)).putExtra("kind",id.substringBefore(':',"reminder")).putExtra("time",time),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun reconcile(account: String, todos: List<Order>) {
        reconcileSchedules(account,ReminderPolicy.future(todos,System.currentTimeMillis()).associate { it.id to it.reminderAt!! })
    }
    fun reconcileSchedules(account: String, schedules: Map<String,Long>) {
        val desired = schedules.entries.sortedWith(compareBy<Map.Entry<String,Long>> {it.value}.thenBy {it.key})
            .take(ReminderPolicy.MaxScheduled).associate {"${account}|${it.key}" to it.value}
        val old = prefs.all.filterValues { it is Long }.mapValues { it.value as Long }
        val edit=prefs.edit()
        var changed=false
        for ((key, _) in old) if (key !in desired) { val parts=key.split('|'); alarms.cancel(pending(parts[0],parts[1])); edit.remove(key); changed=true }
        for ((key, time) in desired) if (old[key] != time) {
            val id = key.substringAfter('|')
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending(account,id,time))
            edit.putLong(key,time); changed=true
        }
        if(changed) edit.apply()
    }
    fun cancelAll() {
        for ((key, _) in prefs.all) { val p=key.split('|'); if(p.size==2) alarms.cancel(pending(p[0],p[1])) }
        prefs.edit { clear() }
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    fun invalidateSchedules() { prefs.edit { clear() } }
    fun allowed(): Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && manager.areNotificationsEnabled() && manager.getNotificationChannel("todos")?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    /** Large missed sets produce one private summary, with individual entries in the inbox. */
    fun notifyMissedBatch(todos:List<Order>) {
        if(todos.size<=5) {todos.forEach {notify(it)};return}
        if(!allowed()) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("todos","Task alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val tap=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify("missed-reminders",0,NotificationCompat.Builder(context,"todos").setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("${todos.size} task alerts").setContentText("Review them in Inbox.")
                .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
        } catch(_:SecurityException) { /* Inbox entries remain available when notifications are suppressed. */ }
    }
    fun notifyAlerts(alerts:List<TaskAlert>) {
        if(alerts.size>5) {notifyMissedBatch(alerts.map {it.todo});return}
        alerts.forEach {notify(it.todo,it.kind==AlertKind.Deadline)}
    }
    fun notify(todo: Order,deadline:Boolean=false) {
        if (!allowed()) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("todos","Task alerts",NotificationManager.IMPORTANCE_DEFAULT))
        val tap=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setData("raheel-optics://order/${todo.id}".toUri()).putExtra("todo",todo.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            manager.notify(if(deadline) "deadline:${todo.id}" else todo.id,0,NotificationCompat.Builder(context,"todos").setSmallIcon(R.drawable.ic_notification).setContentTitle(todo.title).setContentText(if(deadline) "Your task deadline is due." else "Your todo reminder is due.").setContentIntent(tap).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(NotificationCompat.Builder(context,"todos").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(context.getString(R.string.app_name)).setContentText("You have a task reminder.").setAutoCancel(true).build()).build())
        } catch (_: SecurityException) { /* Permission can change after allowed(); the inbox remains available. */ }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val account=intent.getStringExtra("account") ?: return
        val id=intent.getStringExtra("todo") ?: return
        val kind=if(intent.getStringExtra("kind")=="deadline") AlertKind.Deadline else AlertKind.Reminder
        val expected=intent.getLongExtra("time",0)
        if(expected>System.currentTimeMillis()) return
        val pending=goAsync(); val app=context.applicationContext as ReminderEnvironment
        app.scope.launch {
            try {
                withTimeout(8000) {
                app.withReminderWorkspace { current ->
                val store=current ?: return@withReminderWorkspace
                if(store.accountId!=account) return@withReminderWorkspace
                val todo=store.activeTodo(id) ?: return@withReminderWorkspace
                val alert=TaskAlert(todo,kind,expected)
                if(!alert.matches(todo)) return@withReminderWorkspace
                if(store.addTaskAlert(alert)) app.reminders.notify(todo,kind==AlertKind.Deadline)
                app.reminders.reconcileSchedules(store.accountId,store.notificationSchedules())
                }
                }
            } catch(_:TimeoutCancellationException) {enqueueReminderContinuation(context)}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {
                WorkManager.getInstance(context).enqueueUniqueWork("restore-reminders",ExistingWorkPolicy.KEEP,OneTimeWorkRequestBuilder<ReminderWorker>().build())
            } finally { pending.finish() }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        ReminderScheduler(context).invalidateSchedules()
        WorkManager.getInstance(context).enqueueUniqueWork("restore-reminders",ExistingWorkPolicy.REPLACE,OneTimeWorkRequestBuilder<ReminderWorker>().build())
    }
}
class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context,parameters) {
    override suspend fun doWork(): Result = try {
        withTimeout(30000) {
            val app=applicationContext as ReminderEnvironment
            app.withReminderWorkspace { current ->
            val store=current ?: return@withReminderWorkspace Result.success()
            val more=store.deliverDueTaskAlerts(app.reminders::notifyAlerts)
            app.reminders.reconcileSchedules(store.accountId,store.notificationSchedules())
            if(more) Result.retry() else Result.success()
            }
        }
    } catch (_:TimeoutCancellationException) {Result.retry()}
    catch (e:CancellationException) {throw e}
    catch (_: Exception) { Result.retry() }
}
