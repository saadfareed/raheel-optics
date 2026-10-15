package com.daymark.todo

import android.app.Application
import com.daymark.todo.data.TodoStore
import com.daymark.todo.data.appSchema
import com.powersync.PowerSyncDatabase
import com.powersync.DatabaseDriverFactory
import com.daymark.todo.identity.AuthClient
import com.daymark.todo.identity.SessionStore
import com.daymark.todo.reminders.ReminderScheduler
import com.daymark.todo.reminders.ReminderEnvironment
import com.daymark.todo.sync.SupabaseSynchronizer
import com.daymark.todo.sync.SyncEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DaymarkApp : Application(), ReminderEnvironment, SyncEnvironment {
    override val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var sessions: SessionStore
    lateinit var updates: com.daymark.todo.updates.UpdateController
    lateinit var branding: com.daymark.todo.branding.BrandingController
    lateinit var auth: AuthClient
    lateinit var onboarding: com.daymark.todo.onboarding.OnboardingStore
    override lateinit var reminders: ReminderScheduler
    private var store: TodoStore?=null
    private val lock=Mutex()
    private val syncLock=Mutex()
    private val reminderLock=Mutex()
    val syncOnline=kotlinx.coroutines.flow.MutableStateFlow(false)
    val syncProblem=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val syncEvents=kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var workspaceKey: String? = null
    override fun onCreate() {
        super.onCreate()
        if(BuildConfig.SENTRY_DSN.isNotBlank()) io.sentry.android.core.SentryAndroid.init(this) { it.dsn=BuildConfig.SENTRY_DSN }
        com.daymark.todo.ui.syncLaunchTheme(this)
        sessions=SessionStore(this)
        onboarding=com.daymark.todo.onboarding.OnboardingStore(this,sessions.sessionForRevocation()!=null)
        val config=com.daymark.todo.identity.CloudConfig(BuildConfig.SUPABASE_URL,BuildConfig.SUPABASE_PUBLIC_KEY)
        if(config.configured && config != sessions.config()) sessions.configure(config)
        auth=AuthClient(sessions); reminders=ReminderScheduler(this)
        branding=com.daymark.todo.branding.BrandingController(this,auth,config.supabaseUrl)
        updates=com.daymark.todo.updates.UpdateController(this, auth)
    }
    override suspend fun workspace(): TodoStore? = lock.withLock {
        recoverPendingErasures()
        val session=sessions.session() ?: return@withLock null
        sessions.bindGuestTransfer(session)
        val key = sessions.workspaceKey(session)
        if(workspaceKey==key && store?.accountId==session.userId) return@withLock store
        store?.db?.close()
        val filename="todos-${key.replace(Regex("[^a-zA-Z0-9-]"),"")}.db"
        sessions.rememberWorkspace(session,filename)
        val db=PowerSyncDatabase(DatabaseDriverFactory(this),appSchema,dbFilename=filename)
        TodoStore(db,session.userId,session.localOnly,com.daymark.todo.data.CachePolicy(BuildConfig.HISTORY_CACHE_DAYS,BuildConfig.HISTORY_CACHE_ENTRIES,BuildConfig.HISTORY_CACHE_BYTES.toLong()),availableBytes={android.os.StatFs(filesDir.absolutePath).availableBytes}).also { store=it; workspaceKey=key }
    }
    suspend fun maintainCache() = withTimeout(10000) {syncLock.withLock {workspace()?.reconcile()}}
    override suspend fun hasSyncWork():Boolean = syncLock.withLock { workspace()?.needsExchange()==true || sessions.session()?.let {sessions.guestTransferOwnedBy(it)}==true }

    override suspend fun connect() = withTimeout(45000) {
        syncLock.withLock {
            val workspace=workspace() ?: return@withLock
            if(workspace.localOnly) return@withLock
            try {
                val guestPending=sessions.session()?.let {sessions.guestTransferOwnedBy(it)}==true
                if(!workspace.needsExchange() && !guestPending) return@withLock
                val moved=if(workspace.needsInitialSync()) false else transferGuestTasks(workspace)
                val changed=SupabaseSynchronizer(workspace,auth).exchange() || moved
                syncOnline.value=true
                syncProblem.value=if(sessions.guestTransferPending() && sessions.session()?.let {sessions.guestTransferOwnedBy(it)}==false) "Sign in to the account where you started moving guest tasks." else null
                if(changed) syncEvents.value+=1
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {
                syncOnline.value=false
                syncProblem.value=if(e is com.daymark.todo.identity.ApiException && e.status in setOf(413,429)) e.message
                    else if(e is IllegalStateException && e.message?.startsWith("A saved change cannot be read.")==true) e.message
                    else "Sync is paused. Your changes are saved on this device and will retry."
                throw e
            }
        }
        // Reminder work no longer holds the upload/sign-out lock; it remains inside the total deadline.
        refreshReminders()
    }
    /** Runs under syncLock. Source survives until all tasks are durably queued in the destination. */
    private suspend fun transferGuestTasks(destination:TodoStore):Boolean {
        val session=sessions.session() ?: return false
        if(!sessions.guestTransferOwnedBy(session)) return false
        val db=PowerSyncDatabase(DatabaseDriverFactory(this),appSchema,dbFilename="todos-local-device.db")
        try {
            val source=TodoStore(db,"local-device",true)
            val page=source.guestTransferPage(sessions.guestTransferCursor())
            var pageComplete=true
            var wrote=false
            for(todo in page) {
                wrote=destination.receiveGuestTask(todo,source.guestTaskHistory(todo.id)) || wrote
                if(!destination.guestTaskTransferred(todo.id)) {pageComplete=false;break}
                sessions.advanceGuestTransfer(todo.id)
            }
            if(pageComplete && page.size<25) {
                destination.receiveGuestInbox(source.inboxItems())
                db.disconnectAndClear(clearLocal=true)
                sessions.finishGuestTransfer()
            }
            return wrote
        } finally {db.close()}
    }

    override suspend fun <T> withReminderWorkspace(action:suspend (TodoStore?)->T):T =
        reminderLock.withLock { action(workspace()) }

    override suspend fun refreshReminders() = withContext(Dispatchers.IO) {
        withTimeout(10000) {reminderLock.withLock {
            val workspace=workspace() ?: return@withLock
            val more=workspace.deliverDueTaskAlerts(reminders::notifyAlerts)
            reminders.reconcileSchedules(workspace.accountId,workspace.notificationSchedules())
            if(more) com.daymark.todo.reminders.enqueueReminderContinuation(this@DaymarkApp)
        }}
    }
    suspend fun deleteAccount(currentPassword: String) = withContext(NonCancellable) { syncLock.withLock {
        // Prevent queued uploads from racing deletion. Keep local data on server failure.
        auth.deleteAccount(currentPassword)
        reminderLock.withLock { lock.withLock { clearLocalAccount(deleteData=true) } }
    } }

    suspend fun logout(clearDeviceData:Boolean=false) = syncLock.withLock { reminderLock.withLock { lock.withLock {
        if(!clearDeviceData && sessions.sessionForRevocation()?.localOnly==true) sessions.prepareGuestTransfer()
        if(clearDeviceData) {
            check((store?.storageStats()?.queuedOperations ?: 0)==0L) {"Sync or resolve pending changes before clearing device data. Export is available in Settings."}
            val session=sessions.sessionForRevocation()
            val current=workspaceKey?.let {"todos-${it.replace(Regex("[^a-zA-Z0-9-]"),"")}.db"}
            if(session!=null) for(filename in sessions.retainedWorkspaces(session)) if(filename!=current) {
                val other=PowerSyncDatabase(DatabaseDriverFactory(this@DaymarkApp),appSchema,dbFilename=filename)
                try {
                    check(TodoStore(other,session.userId,session.localOnly).storageStats().queuedOperations==0L) {"A retained workspace has pending changes. Reopen and sync it before clearing device data."}
                } finally {other.close()}
            }
        }
        try {
            withTimeout(5000) { auth.revokeCurrentSession() }
        } catch (e: CancellationException) {
            if (e !is TimeoutCancellationException) throw e
        } catch (_: Exception) { /* Local sign-out must also succeed offline. */ }
        finally {
            clearLocalAccount(deleteData=clearDeviceData)
        }
    } } }

    private suspend fun clearLocalAccount(deleteData: Boolean = false) {
        val closing = store
        val session=sessions.sessionForRevocation()
        val currentName=workspaceKey?.let {"todos-${it.replace(Regex("[^a-zA-Z0-9-]"),"")}.db"}
        val retained=if(deleteData && session!=null) sessions.retainedWorkspaces(session) else emptySet()
        if(deleteData) sessions.markForErasure(retained)
        com.daymark.todo.core.finishSignOut(
            { sessions.clear() },
            { store=null; workspaceKey=null; syncOnline.value=false;syncProblem.value=null },
            { androidx.work.WorkManager.getInstance(this@DaymarkApp).cancelUniqueWork("todo-sync") },
            { androidx.work.WorkManager.getInstance(this@DaymarkApp).cancelUniqueWork("todo-periodic-sync") },
            { reminders.cancelAll() },
            {
                if(deleteData) {
                    closing?.db?.disconnectAndClear(clearLocal=true)
                    currentName?.let {sessions.finishErasure(it)}
                    for(filename in retained) if(filename!=currentName) {
                        val other=PowerSyncDatabase(DatabaseDriverFactory(this@DaymarkApp),appSchema,dbFilename=filename)
                        try {other.disconnectAndClear(clearLocal=true)} finally {other.close()}
                        sessions.finishErasure(filename)
                    }
                    session?.let {sessions.forgetWorkspaces(it)}
                } else closing?.db?.disconnect()
            },
            { closing?.db?.close() }
        )
    }
    /** Called under the workspace lock before opening any account after interrupted cleanup. */
    private suspend fun recoverPendingErasures() {
        for(filename in sessions.pendingErasures()) {
            val db=PowerSyncDatabase(DatabaseDriverFactory(this),appSchema,dbFilename=filename)
            try {db.disconnectAndClear(clearLocal=true)} finally {db.close()}
            sessions.finishErasure(filename)
        }
    }
}
