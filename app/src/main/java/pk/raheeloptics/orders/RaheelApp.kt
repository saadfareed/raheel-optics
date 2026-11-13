package pk.raheeloptics.orders

import pk.raheeloptics.orders.data.WorkspaceFiles
import android.app.Application
import pk.raheeloptics.orders.data.OrderStore
import pk.raheeloptics.orders.data.appSchema
import com.powersync.PowerSyncDatabase
import com.powersync.DatabaseDriverFactory
import pk.raheeloptics.orders.identity.AuthClient
import pk.raheeloptics.orders.identity.SessionStore
import pk.raheeloptics.orders.reminders.ReminderScheduler
import pk.raheeloptics.orders.reminders.ReminderEnvironment
import pk.raheeloptics.orders.sync.SupabaseSynchronizer
import pk.raheeloptics.orders.sync.SyncEnvironment
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RaheelApp : Application(), ReminderEnvironment, SyncEnvironment {
    override val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    lateinit var sessions: SessionStore
    lateinit var updates: pk.raheeloptics.orders.updates.UpdateController
    lateinit var branding: pk.raheeloptics.orders.branding.BrandingController
    lateinit var auth: AuthClient
    lateinit var onboarding: pk.raheeloptics.orders.onboarding.OnboardingStore
    override lateinit var reminders: ReminderScheduler
    private var store: OrderStore?=null
    private val lock=Mutex()
    private val syncLock=Mutex()
    private val reminderLock=Mutex()
    val syncOnline=kotlinx.coroutines.flow.MutableStateFlow(false)
    val syncProblem=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val syncEvents=kotlinx.coroutines.flow.MutableStateFlow(0L)
    private var workspaceKey: String? = null
    override fun onCreate() {
        super.onCreate()
        CrashReporting.start(this)
        pk.raheeloptics.orders.ui.syncLaunchTheme(this)
        sessions=SessionStore(this)
        if(WorkspaceFiles.migrateLegacy(this)) {
            sessions.migrateWorkspaceNames()
            // Work queued by the old build names classes that no longer exist; startup re-queues sync and reminders.
            androidx.work.WorkManager.getInstance(this).apply {cancelUniqueWork("todo-sync");cancelUniqueWork("restore-reminders")}
        }
        onboarding=pk.raheeloptics.orders.onboarding.OnboardingStore(this,sessions.sessionForRevocation()!=null)
        val config=pk.raheeloptics.orders.identity.CloudConfig(BuildConfig.SUPABASE_URL,BuildConfig.SUPABASE_PUBLIC_KEY)
        if(config.configured && config != sessions.config()) sessions.configure(config)
        auth=AuthClient(sessions); reminders=ReminderScheduler(this)
        branding=pk.raheeloptics.orders.branding.BrandingController(this,auth,config.supabaseUrl)
        updates=pk.raheeloptics.orders.updates.UpdateController(this, auth)
    }
    override suspend fun workspace(): OrderStore? = lock.withLock {
        recoverPendingErasures()
        val session=sessions.session() ?: return@withLock null
        sessions.bindGuestTransfer(session)
        val key = sessions.workspaceKey(session)
        if(workspaceKey==key && store?.accountId==session.userId) return@withLock store
        store?.db?.close()
        val filename=WorkspaceFiles.forKey(key)
        sessions.rememberWorkspace(session,filename)
        val db=PowerSyncDatabase(DatabaseDriverFactory(this),appSchema,dbFilename=filename)
        OrderStore(db,session.userId,session.localOnly,pk.raheeloptics.orders.data.CachePolicy(BuildConfig.HISTORY_CACHE_DAYS,BuildConfig.HISTORY_CACHE_ENTRIES,BuildConfig.HISTORY_CACHE_BYTES.toLong()),availableBytes={android.os.StatFs(filesDir.absolutePath).availableBytes}).also { store=it; workspaceKey=key }
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
                syncProblem.value=if(e is pk.raheeloptics.orders.identity.ApiException && e.status in setOf(413,429)) e.message
                    else if(e is IllegalStateException && e.message?.startsWith("A saved change cannot be read.")==true) e.message
                    else "Sync is paused. Your changes are saved on this device and will retry."
                throw e
            }
        }
        // Reminder work no longer holds the upload/sign-out lock; it remains inside the total deadline.
        refreshReminders()
    }
    /** Runs under syncLock. Source survives until all tasks are durably queued in the destination. */
    private suspend fun transferGuestTasks(destination:OrderStore):Boolean {
        val session=sessions.session() ?: return false
        if(!sessions.guestTransferOwnedBy(session)) return false
        val db=PowerSyncDatabase(DatabaseDriverFactory(this),appSchema,dbFilename=WorkspaceFiles.forKey("local-device"))
        try {
            val source=OrderStore(db,"local-device",true)
            val page=source.guestTransferPage(sessions.guestTransferCursor())
            var pageComplete=true
            var wrote=false
            for(todo in page) {
                wrote=destination.receiveGuestTask(todo,source.guestTaskHistory(todo.id)) || wrote
                if(!destination.guestTaskTransferred(todo.id)) {pageComplete=false;break}
                sessions.advanceGuestTransfer(todo.id)
            }
            if(pageComplete && page.size<pk.raheeloptics.orders.core.ListRules.PageSize) {
                destination.receiveGuestInbox(source.inboxItems())
                db.disconnectAndClear(clearLocal=true)
                sessions.finishGuestTransfer()
            }
            return wrote
        } finally {db.close()}
    }

    override suspend fun <T> withReminderWorkspace(action:suspend (OrderStore?)->T):T =
        reminderLock.withLock { action(workspace()) }

    override suspend fun refreshReminders() = withContext(Dispatchers.IO) {
        withTimeout(10000) {reminderLock.withLock {
            val workspace=workspace() ?: return@withLock
            val more=workspace.deliverDueTaskAlerts(reminders::notifyAlerts)
            reminders.reconcileSchedules(workspace.accountId,workspace.notificationSchedules())
            if(more) pk.raheeloptics.orders.reminders.enqueueReminderContinuation(this@RaheelApp)
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
            val current=workspaceKey?.let(WorkspaceFiles::forKey)
            if(session!=null) for(filename in sessions.retainedWorkspaces(session)) if(filename!=current) {
                val other=PowerSyncDatabase(DatabaseDriverFactory(this@RaheelApp),appSchema,dbFilename=filename)
                try {
                    check(OrderStore(other,session.userId,session.localOnly).storageStats().queuedOperations==0L) {"A retained workspace has pending changes. Reopen and sync it before clearing device data."}
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
        val currentName=workspaceKey?.let(WorkspaceFiles::forKey)
        val retained=if(deleteData && session!=null) sessions.retainedWorkspaces(session) else emptySet()
        if(deleteData) sessions.markForErasure(retained)
        pk.raheeloptics.orders.core.finishSignOut(
            { sessions.clear() },
            { store=null; workspaceKey=null; syncOnline.value=false;syncProblem.value=null },
            { androidx.work.WorkManager.getInstance(this@RaheelApp).cancelUniqueWork("todo-sync") },
            { androidx.work.WorkManager.getInstance(this@RaheelApp).cancelUniqueWork("todo-periodic-sync") },
            { reminders.cancelAll() },
            {
                if(deleteData) {
                    closing?.db?.disconnectAndClear(clearLocal=true)
                    currentName?.let {sessions.finishErasure(it)}
                    for(filename in retained) if(filename!=currentName) {
                        val other=PowerSyncDatabase(DatabaseDriverFactory(this@RaheelApp),appSchema,dbFilename=filename)
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
