package pk.raheeloptics.orders.sync

import android.content.Context
import androidx.work.*
import pk.raheeloptics.orders.data.OrderStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.util.concurrent.TimeUnit

interface SyncEnvironment {
    suspend fun workspace(): OrderStore?
    suspend fun hasSyncWork():Boolean
    suspend fun connect()
    suspend fun refreshReminders()
}

/** Remove legacy timed polling; pending constrained work resumes when online. */
fun scheduleSync(context:Context) {WorkManager.getInstance(context).cancelUniqueWork("todo-periodic-sync")}

private val enqueueScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
private val enqueueLock=Mutex()
fun enqueueSync(context: Context) {
    val manager=WorkManager.getInstance(context.applicationContext)
    enqueueScope.launch {
        enqueueLock.withLock {
            // Keep at most one waiting successor. Running work can be exiting as an edit arrives.
            val waiting=manager.getWorkInfosForUniqueWork("todo-sync").get().any {
                it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED
            }
            if(waiting) return@withLock
            val request=OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            manager.enqueueUniqueWork("todo-sync",ExistingWorkPolicy.APPEND_OR_REPLACE,request).result.get()
        }
    }
}
class SyncWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val app=applicationContext as SyncEnvironment
        return try {
            withTimeout(45000) {
                val store=app.workspace() ?: return@withTimeout Result.success()
                if(store.localOnly) return@withTimeout Result.success()
                while(app.hasSyncWork()) {app.connect(); delay(250)}
                Result.success()
            }
        } catch (_: TimeoutCancellationException) { Result.retry() }
        catch (e: CancellationException) { throw e }
        catch (e:pk.raheeloptics.orders.identity.ApiException) {if(e.permanentQuota) Result.success() else Result.retry()}
        catch (_: Exception) { Result.retry() }
    }
}
