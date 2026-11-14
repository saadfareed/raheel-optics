package pk.raheeloptics.orders.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import pk.raheeloptics.orders.RaheelApp
import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.data.OrderStore
import pk.raheeloptics.orders.data.OpticalStore
import pk.raheeloptics.orders.identity.*
import pk.raheeloptics.orders.sync.enqueueSync
import pk.raheeloptics.orders.sync.scheduleSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

data class UiState(
    val session: Session? = null,
    val config: CloudConfig = CloudConfig(),
    val snapshot: Snapshot = Snapshot(),
    val busy: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
    /** Email waiting for a 6-digit code; [codeIsReset] means a password reset rather than sign-up. */
    val codeEmail: String? = null,
    val codeIsReset: Boolean = false,
    val online: Boolean = false,
    val syncProblem: String? = null,
    val query: OrderQuery = OrderQuery(view=OrderView.ACTIVE),
    val todos: List<Order> = emptyList(),
    val todosMore: Boolean = false,
    val conflictPageNumber: Int = 1,
    val todosLoading: Boolean = false,
    val analyticsLoading: Boolean = false,
    val storageStats: pk.raheeloptics.orders.data.StorageStats? = null,
    val sessionIdleDays: Int = 30,
    val importActiveCount: Int? = null,
    val importSkippedCount: Int = 0,
    val importCustomerCount:Int=0,
    val customerQuery:CustomerDirectoryQuery = CustomerDirectoryQuery(),
    val customers:List<CustomerDirectoryRow> = emptyList(),
    val customersMore:Boolean = false,
    val customersLoaded:Boolean = false,
    val customerConflicts:Set<String> = emptySet(),
    val orderAnalytics:OrderAnalyticsSummary?=null,
    val orderAnalyticsError:String?=null,
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class OrdersViewModel(application: Application) : AndroidViewModel(application) {
    private val app=application as RaheelApp
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private var observers: Job?=null
    private var analyticsJob: Job?=null
    private var orderAnalyticsJob:Job?=null
    private var analyticsGeneration=0
    private var conflictOffset=0
    private var store: OrderStore?=null
    private var pendingImport: Pair<OrderStore,TaskImportPreview>?=null
    private val refreshLock=Mutex()
    private val json=Json { ignoreUnknownKeys=true }
    private var todoPages=CursorPages()
    /** Orders shown = PageSize x loaded pages; "Load more" grows it, so the list appends and stays reactive to DB changes. */
    private var todoLimit=ListRules.PageSize
    /** The customer directory is the costliest query; it is only kept fresh while the Customers tab is open. */
    private var customersOpen=false
    private var todoCursor: ListCursor?=null
    private var todoJob: Job?=null
    private var foreground=true
    fun foreground(active:Boolean) {
        foreground=active
        if(active) {
            if(mutable.value.session!=null && app.sessions.session()==null) {logout();return}
            runCatching {app.sessions.markOpened()}.onFailure {mutable.update {it.copy(message="Couldn't save sign-in activity. Try again.")}}
            viewModelScope.launch {runCatching {app.maintainCache()}}
            if(store?.localOnly==false) viewModelScope.launch {if(app.hasSyncWork()) enqueueSync(app)}
        }
    }
    fun setSessionIdleDays(days:Int)=action {
        withContext(Dispatchers.IO) {app.sessions.setIdleDays(days)}
        mutable.update {it.copy(sessionIdleDays=days)}
    }
    fun loadStorageStats() {
        val workspace=store ?: return
        viewModelScope.launch {
            try {
                val stats=workspace.storageStats()
                if(store===workspace) mutable.update {it.copy(storageStats=stats)}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {
                if(store===workspace) mutable.update {it.copy(message="Couldn't load device storage stats. Try again.")}
            }
        }
    }
    fun clearHistoryCache()=action {
        val workspace=store ?: return@action
        workspace.clearConfirmedHistory()
        mutable.update {it.copy(storageStats=null)}
        refresh();loadStorageStats()
    }
    fun clearLocalHistory()=action {
        store?.clearLocalHistory()
        refresh();loadStorageStats()
    }
    fun exportWorkspace(uri:android.net.Uri,selection:OpticalExportSelection=OpticalExportSelection.BACKUP)=action {
        val workspace=store ?: error("Sign in first.")
        withContext(Dispatchers.IO) {
            val snapshot=java.io.File.createTempFile("device-export-",".jsonl",app.cacheDir)
            try {
                withTimeout(30000) {snapshot.bufferedWriter().use {workspace.exportTo(it,selection)}}
                // Release the database snapshot before waiting on an external document provider.
                withTimeout(30000) {
                    app.contentResolver.openOutputStream(uri,"wt")?.use {output -> snapshot.inputStream().use {copyExport(it,output)}}
                        ?: error("Couldn't open the export file.")
                }
            } catch(e:TimeoutCancellationException) {
                throw IllegalStateException("Export did not finish in time. Discard any partial destination file and retry. Your saved work is unchanged.",e)
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                throw IllegalStateException("Export did not finish. Discard any partial destination file and retry. Your saved work is unchanged.",e)
            } finally {snapshot.delete()}
        }
        mutable.update {it.copy(message="${selection.label} exported. Frame photo files are not included; keep the file private.")}
    }
    fun previewTaskImport(uri:android.net.Uri)=action {
        val workspace=store ?: error("Sign in first.")
        val preview=withContext(Dispatchers.IO) {
            try {
                withTimeout(30000) {
                    app.contentResolver.openInputStream(uri)?.use(TaskImport::preview)
                        ?: error("Couldn't open the export file.")
                }
            } catch(e:CancellationException) {
                if(e is TimeoutCancellationException) throw IllegalStateException("Import preview timed out. Choose the file again.",e)
                throw e
            } catch(e:Exception) {throw IllegalStateException("Couldn't read this optical export. Check that the file is complete. ${e.message.orEmpty()}",e)}
        }
        check(store===workspace) {"Account changed. Choose the export again."}
        pendingImport=workspace to preview
        mutable.update {it.copy(importActiveCount=preview.activeTasks.size,importSkippedCount=preview.skippedNonActive,importCustomerCount=preview.customers.size)}
    }
    fun cancelTaskImport() {
        pendingImport=null
        mutable.update {it.copy(importActiveCount=null,importSkippedCount=0)}
    }
    fun applyTaskImport()=action {
        val (workspace,preview)=pendingImport ?: error("Choose an export first.")
        check(store===workspace) {"Account changed. Choose the export again."}
        pendingImport=null
        mutable.update {it.copy(importActiveCount=null,importSkippedCount=0)}
        var imported=0;var existing=0;var importedCustomers=0
        val profiles=OpticalStore(workspace.db,workspace.accountId,localOnly=workspace.localOnly)
        val mapping=mutableMapOf<String,String>()
        try {
            for(customer in preview.customers) {
                val known=profiles.customerByMobile(customer.normalizedMobile)
                val stored=known ?: profiles.upsertCustomer(customer.copy(id=UUID.nameUUIDFromBytes("${workspace.accountId}:customer:${customer.normalizedMobile}".toByteArray()).toString(),revision=0,previousMobiles=emptyList()))
                mapping[customer.id]=stored.id
                if(known==null) importedCustomers++
            }
            for(source in preview.activeTasks) {
                currentCoroutineContext().ensureActive()
                val id=TaskImport.copyId(workspace.accountId,source.id)
                if(workspace.hasTaskId(id)) {existing++;continue}
                val now=System.currentTimeMillis()
                val known=profiles.customerByMobile(source.normalizedMobile)
                workspace.save(source.copy(id=id,customerId=mapping[source.customerId] ?: known?.id,orderNumber="IMPORT-${id.take(8).uppercase()}",revision=0,updatedAt=now,completedAt=null,deliveredAt=null,reminderAt=null,status="active",
                    frame=source.frame.copy(localImageUri=null,imageKey=null)),"create",null,source.id,updateCustomerProfile=false)
                imported++
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            if(imported>0 || importedCustomers>0) {if(!workspace.localOnly) enqueueSync(app);refresh()}
            throw IllegalStateException("Imported $imported orders and $importedCustomers customers before an error. Reopen the export to retry; existing copies will be skipped. ${e.message.orEmpty()}",e)
        }
        if(imported>0 || importedCustomers>0) {if(!workspace.localOnly) enqueueSync(app);refresh();loadStorageStats()}
        mutable.update {it.copy(message="Imported $imported active orders and $importedCustomers customers; skipped $existing existing orders. Existing customer profiles were preserved. Reminders and frame photos need to be added again.")}
    }
    fun clearDeviceData()=action {
        observers?.cancelAndJoin();observers=null
        analyticsJob?.cancelAndJoin();todoJob?.cancelAndJoin()
        try {app.logout(clearDeviceData=true);store=null;mutable.update {UiState(config=app.sessions.config(),loading=false,message="Device data cleared.")}}
        catch(e:Exception) {attach();throw e}
    }
    init { action { attach() } }

    private suspend fun attach() {
        pendingImport=null
        observers?.cancelAndJoin(); analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
        todoCursor=null;conflictOffset=0
        todoPages.reset()
        val expired=app.sessions.hasExpiredSession()
        if(expired) app.logout()
        val session=app.sessions.session()
        app.sessions.markOpened()
        mutable.update { UiState(session,app.sessions.config(),busy=it.busy,loading=session!=null,sessionIdleDays=app.sessions.idleDays(),message=if(expired) "Sign in again. Your local changes are safe." else null) }
        val started=android.os.SystemClock.elapsedRealtime()
        fun phase(name:String) { if(pk.raheeloptics.orders.BuildConfig.DEBUG || pk.raheeloptics.orders.BuildConfig.BUILD_TYPE=="perf") android.util.Log.i("RaheelStartup","$name +${android.os.SystemClock.elapsedRealtime()-started}ms") }
        store=app.workspace(); phase("workspace opened")
        val workspace=store ?: run { mutable.update { it.copy(loading=false) }; return }
        withContext(Dispatchers.Default) {workspace.reconcile()}; phase("reconciled")
        refresh(); phase("first orders page")
        withContext(Dispatchers.Default) {app.refreshReminders()}; phase("reminders scheduled")
        observers=viewModelScope.launch {
            launch {
                workspace.db.onChange(setOf("active_todos","server_todos","server_totals","drafts","journal","history_cache","user_totals","local_totals","quarantined_changes","optical_drafts","optical_customer_cache","optical_journal"))
                    .conflate().debounce(75).onEach {
                        try {refresh()}
                        catch(e:CancellationException) {throw e}
                        catch(_:Exception) {mutable.update {it.copy(message="Couldn't load tasks. Try again.")}}
                    }.catch { mutable.update { it.copy(message="Couldn't watch task changes. Reopen the app.") } }.collect()
            }
            launch {
                workspace.db.onChange(setOf("inbox")).conflate().debounce(75).onEach {
                    try {refreshInbox()} catch(e:CancellationException) {throw e}
                    catch(_:Exception) {mutable.update {it.copy(message="Couldn't load reminders. Try again.")}}
                }.catch {mutable.update {it.copy(message="Couldn't watch reminders. Reopen the app.")}}.collect()
            }
            launch {
                workspace.db.onChange(setOf("active_todos","server_todos","drafts")).conflate().debounce(250).onEach {
                    try {app.refreshReminders()} catch(e:CancellationException) {throw e}
                    catch(_:Exception) {pk.raheeloptics.orders.reminders.enqueueReminderContinuation(app)}
                }.collect()
            }
            if(!workspace.localOnly) launch {
                app.syncOnline.collect { online ->
                    mutable.update { it.copy(online=online) }
                }
            }
            if(!workspace.localOnly) launch {
                app.syncProblem.collect {problem -> mutable.update {it.copy(syncProblem=problem)} }
            }

        }
        if(!workspace.localOnly) {scheduleSync(app);if(app.hasSyncWork()) enqueueSync(app)}
    }

    private suspend fun refreshInbox() = refreshLock.withLock {
        val workspace=store ?: return@withLock
        val inbox=workspace.inboxItems()
        if(store===workspace) mutable.update {it.copy(snapshot=it.snapshot.copy(inbox=inbox))}
    }

    /** Off the main thread: decoding a page of orders froze the first frames. */
    private suspend fun refresh() = withContext(Dispatchers.Default) { refreshLock.withLock {
        val workspace=store ?: return@withLock
        val query=mutable.value.query
        val limit=todoLimit
        val todos=workspace.todoPage(query,limit=limit)
        val customerQuery=mutable.value.customerQuery
        val customers=if(customersOpen) workspace.customerDirectory(customerQuery) else null
        val customerConflicts=workspace.db.getAll("SELECT DISTINCT json_extract(payload,'$.payload.customer.id') FROM optical_journal WHERE state='conflict' AND json_extract(payload,'$.payload.customer.id') IS NOT NULL") {it.getString(0)!!}.toSet()
        var snapshot=workspace.uiSnapshot(todos.items,emptyList(),conflictOffset=conflictOffset)
        if(conflictOffset>0 && snapshot.pending.isEmpty()) {
            conflictOffset=0
            snapshot=workspace.uiSnapshot(todos.items,emptyList())
        }
        if(store!==workspace || mutable.value.query!=query || todoLimit!=limit) return@withLock
        todoCursor=todos.next
        mutable.update {
            it.copy(snapshot=snapshot,loading=false,todos=todos.items,todosMore=todos.next!=null,
                customers=if(customers!=null && it.customerQuery==customerQuery) customers.take(customerQuery.loaded*ListRules.PageSize) else it.customers,
                customersLoaded=it.customersLoaded || (customers!=null && it.customerQuery==customerQuery),
                customersMore=if(customers!=null && it.customerQuery==customerQuery) customers.size>customerQuery.loaded*ListRules.PageSize else it.customersMore,
                customerConflicts=customerConflicts,
                conflictPageNumber=conflictOffset/ListRules.PageSize+1)
        }
    }}

    private fun action(block: suspend ()->Unit) {
        if(mutable.value.busy) return
        mutable.update { it.copy(busy=true,message=null) }
        viewModelScope.launch {
            try { block() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { mutable.update { it.copy(message=e.message ?: "Couldn't finish. Try again.",loading=false) } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun dismissMessage() { mutable.update { it.copy(message=null) } }
    fun continueAsGuest()=action {
        check(app.sessions.session()==null) { "Sign out before opening a guest workspace." }
        check(!app.sessions.guestTransferBound()) {"Sign in to finish moving your guest tasks first."}
        if(app.sessions.guestTransferPending()) app.sessions.finishGuestTransfer()
        app.sessions.clear()
        app.sessions.save(Session("local-device","","","",Long.MAX_VALUE,true))
        attach()
    }
    fun login(email:String,password:String)=action { app.auth.login(email,password); attach() }
    fun register(email:String,password:String)=action {
        if(app.auth.register(email,password)) attach()
        else mutable.update { it.copy(codeEmail=email.trim(),codeIsReset=false,message="We emailed a 6-digit code to ${email.trim()}.") }
    }
    fun reset(email:String)=action { app.auth.reset(email); mutable.update { it.copy(codeEmail=email.trim(),codeIsReset=true,message="If an account exists for this email, we sent it a 6-digit code.") } }
    fun verifyCode(code:String,newPassword:String?)=action {
        val pending=state.value
        app.auth.verifyCode(pending.codeEmail ?: error("Start again."),code.trim(),if(pending.codeIsReset) "recovery" else "signup",newPassword)
        mutable.update { it.copy(codeEmail=null,message=null) }; attach()
    }
    fun resendCode()=action {
        val pending=state.value; val email=pending.codeEmail ?: return@action
        if(pending.codeIsReset) app.auth.reset(email) else app.auth.resendSignupCode(email)
        mutable.update { it.copy(message="A new code is on its way.") }
    }
    fun cancelCode()=mutable.update { it.copy(codeEmail=null,message=null) }
    fun changePassword(current:String,replacement:String,onChanged:()->Unit)=action {
        app.auth.changePassword(current,replacement)
        mutable.update { it.copy(message="Password updated.") }; onChanged()
    }
    fun deleteAccount(password:String)=action {
        observers?.cancelAndJoin(); observers=null
        analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
        try {
            app.deleteAccount(password); store=null
            mutable.update { UiState(config=app.sessions.config(),loading=false,message="Account deleted.") }
        } catch(e:Exception) {
            attach()
            throw e
        }
    }
    fun logout()=action {
        observers?.cancelAndJoin(); observers=null; analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
        try { app.logout() }
        finally {
            // Cleanup may report a database failure after successfully clearing credentials.
            // Do not leave the signed-in UI pointing at a closed workspace in that case.
            if(app.sessions.session()==null) {
                store=null
                mutable.update { UiState(config=app.sessions.config(),loading=false, message="Signed out.") }
            }
        }
    }
    fun loadOrderAnalytics(query:OrderAnalyticsQuery) {
        orderAnalyticsJob?.cancel()
        val workspace=store ?: return
        orderAnalyticsJob=viewModelScope.launch {
            mutable.update {it.copy(analyticsLoading=true,orderAnalyticsError=null)}
            try {val summary=workspace.orderAnalytics(query);if(store===workspace) mutable.update {it.copy(orderAnalytics=summary)}}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {mutable.update {it.copy(orderAnalyticsError=e.message ?: "Couldn't load analytics.")}}
            finally {if(store===workspace) mutable.update {it.copy(analyticsLoading=false)}}
        }
    }
    fun openOrder(id:String,onFound:(Order?)->Unit) {
        viewModelScope.launch {onFound(store?.orderById(id))}
    }
    suspend fun customerForMobile(mobile:String):Customer? {
        val workspace=store ?: return null
        val normalized=PakistanPhone.normalize(mobile) ?: return null
        return OpticalStore(workspace.db,workspace.accountId,localOnly=workspace.localOnly).customerByMobile(normalized)
    }
    fun undoReady(before:Order)=action {
        val workspace=store ?: error("Open your shop first.")
        val current=workspace.orderById(before.id) ?: error("Order no longer exists.")
        check(current.orderStatus==OrderStatus.READY_FOR_PICKUP) {"Order status changed. Open its latest details."}
        workspace.save(current.copy(orderStatus=OrderStatus.ACTIVE,updatedAt=System.currentTimeMillis()),"edit",current)
        if(!workspace.localOnly) enqueueSync(app)
        refresh()
    }
    fun save(todo:Order,before:Order?,onSaved:()->Unit)=action {
        val workspace=store ?: error("Sign in first.")
        Rules.validate(todo,System.currentTimeMillis(),checkReminder=before==null || todo.reminderAt!=before.reminderAt,
            checkDeadline=before==null || todo.dueAt!=before.dueAt)
        workspace.save(todo,if(before==null) "create" else "edit",before)
        if(!workspace.localOnly) enqueueSync(app)
        refresh(); onSaved()
    }
    fun transition(todo:Order,action:String,onDone:()->Unit={})=action {
        val workspace=store ?: error("Sign in first.")
        workspace.transition(todo,action)
        if(!workspace.localOnly) enqueueSync(app)
        refresh(); onDone()
    }
    fun openCustomers() {
        customersOpen=true
        val workspace=store ?: return
        if(workspace.localOnly) {updateCustomers(mutable.value.customerQuery);return}
        viewModelScope.launch {
            try {
                var after="00000000-0000-0000-0000-000000000000"
                repeat(1000) {
                    val rows=app.auth.request("/rest/v1/rpc/optical_customer_page",body=buildJsonObject {put("after_id",after)},authenticated=true)
                        .jsonArray.map {json.decodeFromJsonElement<Customer>(it)}
                    if(store!==workspace) return@launch
                    OpticalStore(workspace.db,workspace.accountId).cacheCustomers(rows)
                    if(rows.size<100) {refresh();return@launch}
                    after=rows.last().id
                }
                refresh()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(message="Showing customers on this device. Cloud customer refresh is unavailable.")}}
        }
    }

    fun resolveCustomerConflict(id:String,keepLocal:Boolean,onSaved:()->Unit)=action {
        val workspace=store ?: error("Open your shop first.")
        OpticalStore(workspace.db,workspace.accountId,localOnly=workspace.localOnly).resolveCustomerConflict(id,keepLocal)
        if(!workspace.localOnly) enqueueSync(app)
        refresh();onSaved()
    }

    /** Debug builds only: adds the labelled demo orders (see [DemoData]); re-running skips ones already present. */
    fun loadDemoData()=action {
        val workspace=store ?: error("Open your shop first.")
        // An earlier demo load used other ids; cancel any of its orders that are still open so nothing shows twice.
        for(n in 1..6) workspace.orderById(UUID.nameUUIDFromBytes("${workspace.accountId}:demo-order:$n".toByteArray()).toString())
            ?.takeIf {!it.orderStatus.isTerminal}?.let {workspace.transition(it,"delete")}
        var added=0
        for(order in DemoData.all(workspace.accountId)) {
            if(workspace.hasTaskId(order.id)) continue
            // Completed and cancelled orders are created open and then moved through the real transition, as in the app.
            val terminal=order.orderStatus==OrderStatus.COMPLETE || order.orderStatus==OrderStatus.CANCEL
            workspace.save(if(terminal) order.copy(orderStatus=OrderStatus.ACTIVE,status="active",completedAt=null,deliveredAt=null) else order,"create",null)
            if(terminal) workspace.orderById(order.id)?.let {created ->workspace.transition(created,if(order.orderStatus==OrderStatus.COMPLETE) "complete" else "delete")}
            added++
        }
        val shop=ShopPreferences.load(app)
        if(shop.phone.isBlank()) ShopPreferences.save(app,shop.copy(phone=DemoData.SHOP_MOBILE))
        if(!workspace.localOnly) enqueueSync(app)
        refresh()
        mutable.update {it.copy(message=if(added==0) "Demo data is already loaded." else "Added $added demo orders for ${DemoData.EXTRA_CUSTOMERS+1} customers.")}
    }

    fun saveCustomer(customer:Customer,onSaved:()->Unit)=action {
        val workspace=store ?: error("Open your shop first.")
        val profiles=OpticalStore(workspace.db,workspace.accountId,localOnly=workspace.localOnly)
        if(customer.id in mutable.value.customerConflicts) profiles.retryCustomerChanges(customer) else profiles.upsertCustomer(customer)
        if(!workspace.localOnly) enqueueSync(app)
        refresh();onSaved()
    }

    fun updateCustomers(query:CustomerDirectoryQuery) {
        customersOpen=true
        mutable.update {it.copy(customerQuery=query)}
        viewModelScope.launch {
            try {refresh()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(message="Couldn't load customers. Try again.")}}
        }
    }

    fun updateQuery(query:OrderQuery) {
        if(mutable.value.query==query) return
        todoJob?.cancel()
        todoCursor=null
        todoLimit=ListRules.PageSize
        todoPages.reset()
        mutable.update { it.copy(query=query,todos=emptyList(),todosMore=false,todosLoading=true) }
        todoJob=viewModelScope.launch {
            try {refresh()} catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(message="Couldn't load tasks.")}}
            finally {if(todoJob===currentCoroutineContext()[Job]) mutable.update {it.copy(todosLoading=false)}}
        }
    }
    fun loadMoreTodos() {
        if(todoJob?.isActive==true || !mutable.value.todosMore) return
        mutable.update {it.copy(todosLoading=true)}
        todoLimit+=ListRules.PageSize
        todoJob=viewModelScope.launch {
            try {refresh()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {todoLimit=(todoLimit-ListRules.PageSize).coerceAtLeast(ListRules.PageSize);mutable.update {it.copy(message="Couldn't load more orders.")}}
            finally {mutable.update {it.copy(todosLoading=false)}}
        }
    }
    suspend fun ordersForCustomer(row:CustomerDirectoryRow):List<Order> =
        (store ?: return emptyList()).customerOrders(row.customer?.id ?: row.latestOrder.customerId,row.latestOrder.normalizedMobile)
    fun closeCustomers() {customersOpen=false}
    fun closeAnalytics() {
        analyticsGeneration++;analyticsJob?.cancel()
        mutable.update {it.copy(analyticsLoading=false)}
    }
    fun loadMoreConflicts() {
        if(conflictOffset+ListRules.PageSize>=mutable.value.snapshot.conflictCount) return
        conflictOffset+=ListRules.PageSize
        viewModelScope.launch {runCatching {refresh()}}
    }
    fun previousConflicts() {
        conflictOffset=(conflictOffset-ListRules.PageSize).coerceAtLeast(0)
        viewModelScope.launch {runCatching {refresh()}}
    }
    fun readAlert(id:String)=action { store!!.markRead(id); refreshInbox() }
    fun resolveConflict(p:PendingOperation,saveAsNew:Boolean)=action {
        val todo=p.operation.todo
        val replacement=if(saveAsNew) todo.copy(id=UUID.randomUUID().toString(),status="active",revision=0,completedAt=null,reminderAt=null,updatedAt=System.currentTimeMillis()) else null
        store!!.keepRemote(todo.id,replacement)
        conflictOffset=0
        if(replacement!=null) enqueueSync(app)
        refresh()
    }
    suspend fun notificationTarget(id:String): Order? {
        val workspace=store ?: return null
        val todo=workspace.activeTodo(id)
        return if(store===workspace) todo else null
    }
    fun openTask(id:String,onFound:(Order?)->Unit) {
        viewModelScope.launch {onFound(runCatching {notificationTarget(id)}.getOrNull())}
    }
}
