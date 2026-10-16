package com.daymark.todo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.daymark.todo.DaymarkApp
import com.daymark.todo.core.*
import com.daymark.todo.data.TodoStore
import com.daymark.todo.data.OpticalStore
import com.daymark.todo.identity.*
import com.daymark.todo.history.HistoryRules
import com.daymark.todo.sync.enqueueSync
import com.daymark.todo.sync.scheduleSync
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
    val online: Boolean = false,
    val syncProblem: String? = null,
    val lastSync: String? = null,
    val query: TodoQuery = TodoQuery(),
    val todos: List<Todo> = emptyList(),
    val todosMore: Boolean = false,
    val todoPageNumber: Int = 1,
    val todoPrevious: Boolean = false,
    val conflictPageNumber: Int = 1,
    val savedHistoryPrevious: Boolean = false,
    val cloudHistoryPrevious: Boolean = false,
    val todosLoading: Boolean = false,
    val history: List<HistoryEntry> = emptyList(),
    val historyMore: Boolean = true,
    val historyLoading: Boolean = false,
    val historyError: String? = null,
    val olderHistory: List<HistoryEntry> = emptyList(),
    val historyEnd: Boolean = false,
    val historyPageNumber: Int = 1,
    val historyCloudPage: Boolean = false,
    val analyticsSummary: HistoryAnalytics? = null,
    val analyticsLoading: Boolean = false,
    val localAnalytics: HistoryAnalytics? = null,
    val pendingAnalytics: HistoryAnalytics? = null,
    val storageStats: com.daymark.todo.data.StorageStats? = null,
    val sessionIdleDays: Int = 30,
    val importActiveCount: Int? = null,
    val importSkippedCount: Int = 0,
    val customerQuery:CustomerDirectoryQuery = CustomerDirectoryQuery(),
    val customers:List<CustomerDirectoryRow> = emptyList(),
    val customersMore:Boolean = false,
    val customerConflicts:Set<String> = emptySet(),
)

@OptIn(kotlinx.coroutines.FlowPreview::class)
class TodoViewModel(application: Application) : AndroidViewModel(application) {
    private val app=application as DaymarkApp
    private val mutable=MutableStateFlow(UiState())
    val state=mutable.asStateFlow()
    private var observers: Job?=null
    private var historyJob: Job?=null
    private var analyticsJob: Job?=null
    private var analyticsGeneration=0
    private var analyticsDays:Int?=null
    private var conflictOffset=0
    private var store: TodoStore?=null
    private var pendingImport: Pair<TodoStore,TaskImportPreview>?=null
    private val refreshLock=Mutex()
    private val json=Json { ignoreUnknownKeys=true }
    private var todoPages=CursorPages()
    private var savedHistoryPages=CursorPages()
    private var todoCursor: ListCursor?=null
    private var todoJob: Job?=null
    private var localHistoryMore=false
    private var cloudCursor: ListCursor?=null
    private var historyStarted=false
    private var historyPages=CursorPages()
    private var historyGeneration=0
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
    fun refreshBranding()=action {app.branding.refresh(force=true)}
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
        mutable.update {it.copy(olderHistory=emptyList(),storageStats=null)}
        cloudCursor=null;historyStarted=false
        savedHistoryPages.reset()
        refresh();loadStorageStats()
    }
    fun clearLocalHistory()=action {
        store?.clearLocalHistory()
        savedHistoryPages.reset();refresh();loadStorageStats()
    }
    fun exportWorkspace(uri:android.net.Uri)=action {
        val workspace=store ?: error("Sign in first.")
        withContext(Dispatchers.IO) {
            val snapshot=java.io.File.createTempFile("device-export-",".jsonl",app.cacheDir)
            try {
                withTimeout(30000) {snapshot.bufferedWriter().use {workspace.exportTo(it)}}
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
        mutable.update {it.copy(message="Export saved. Keep this file private; it contains your tasks and notes.")}
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
            } catch(e:Exception) {throw IllegalStateException("Couldn't read this Daymark export. Check that it is a complete version 1 file.",e)}
        }
        check(store===workspace) {"Account changed. Choose the export again."}
        pendingImport=workspace to preview
        mutable.update {it.copy(importActiveCount=preview.activeTasks.size,importSkippedCount=preview.skippedNonActive)}
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
        var imported=0;var existing=0
        try {
            for(source in preview.activeTasks) {
                currentCoroutineContext().ensureActive()
                val id=TaskImport.copyId(workspace.accountId,source.id)
                if(workspace.hasTaskId(id)) {existing++;continue}
                val now=System.currentTimeMillis()
                workspace.save(source.copy(id=id,revision=0,updatedAt=now,completedAt=null,reminderAt=null,status="active"),"create",null,source.id)
                imported++
            }
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            if(imported>0) {if(!workspace.localOnly) enqueueSync(app);refresh()}
            throw IllegalStateException("Imported $imported tasks before a limit or storage error. Reopen the same export to retry; existing copies will be skipped.",e)
        }
        if(imported>0) {if(!workspace.localOnly) enqueueSync(app);refresh();loadStorageStats()}
        mutable.update {it.copy(message="Imported $imported active task copies; skipped $existing already imported and ${preview.skippedNonActive} completed/deleted tasks. All reminders were cleared.")}
    }
    fun clearDeviceData()=action {
        observers?.cancelAndJoin();observers=null
        historyJob?.cancelAndJoin();analyticsJob?.cancelAndJoin();todoJob?.cancelAndJoin()
        try {app.logout(clearDeviceData=true);store=null;mutable.update {UiState(config=app.sessions.config(),loading=false,message="Device data cleared.")}}
        catch(e:Exception) {attach();throw e}
    }
    init { action { attach() } }

    private suspend fun attach() {
        pendingImport=null
        observers?.cancelAndJoin(); historyJob?.cancelAndJoin(); analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
        todoCursor=null;conflictOffset=0;analyticsDays=null
        historyPages.reset();savedHistoryPages.reset()
        historyGeneration++
        todoPages.reset(); cloudCursor=null; historyStarted=false
        val expired=app.sessions.hasExpiredSession()
        if(expired) app.logout()
        val session=app.sessions.session()
        app.sessions.markOpened()
        mutable.update { UiState(session,app.sessions.config(),busy=it.busy,loading=session!=null,historyEnd=session?.localOnly==true,sessionIdleDays=app.sessions.idleDays(),message=if(expired) "Sign in again. Your local changes are safe." else null) }
        store=app.workspace()
        val workspace=store ?: run { mutable.update { it.copy(loading=false) }; return }
        workspace.reconcile()
        app.refreshReminders(); refresh()
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
                    catch(_:Exception) {com.daymark.todo.reminders.enqueueReminderContinuation(app)}
                }.collect()
            }
            if(!workspace.localOnly) launch {
                app.syncOnline.collect { online ->
                    val reconnected=online && !mutable.value.online
                    mutable.update { it.copy(online=online) }
                    if(reconnected && historyStarted) reloadCloudHistory()
                }
            }
            if(!workspace.localOnly) launch {
                app.syncProblem.collect {problem -> mutable.update {it.copy(syncProblem=problem)} }
            }
            if(!workspace.localOnly) launch {
                app.syncEvents.collect { if(it>0 && historyStarted) reloadCloudHistory() }
            }

        }
        if(!workspace.localOnly) {scheduleSync(app);if(app.hasSyncWork()) enqueueSync(app)}
    }

    private suspend fun refreshInbox() = refreshLock.withLock {
        val workspace=store ?: return@withLock
        val inbox=workspace.inboxItems()
        if(store===workspace) mutable.update {it.copy(snapshot=it.snapshot.copy(inbox=inbox))}
    }

    private suspend fun refresh(analyticsChanged:Boolean=true) = refreshLock.withLock {
        val workspace=store ?: return@withLock
        val query=mutable.value.query
        val taskAnchor=todoPages.cursor
        val historyAnchor=savedHistoryPages.cursor
        val todos=workspace.todoPage(query,cursor=taskAnchor)
        val cached=workspace.historyPage(cursor=historyAnchor,limit=ListRules.PageSize)
        val customerQuery=mutable.value.customerQuery
        val customers=workspace.customerDirectory(customerQuery)
        val customerConflicts=workspace.db.getAll("SELECT DISTINCT json_extract(payload,'$.payload.customer.id') FROM optical_journal WHERE state='conflict' AND json_extract(payload,'$.payload.customer.id') IS NOT NULL") {it.getString(0)!!}.toSet()
        var snapshot=workspace.uiSnapshot(todos.items,cached.items,mutable.value.olderHistory.map {it.todo.id},conflictOffset=conflictOffset)
        if(conflictOffset>0 && snapshot.pending.isEmpty()) {
            conflictOffset=0
            snapshot=workspace.uiSnapshot(todos.items,cached.items,mutable.value.olderHistory.map {it.todo.id})
        }
        if(store!==workspace || mutable.value.query!=query || todoPages.cursor!=taskAnchor || savedHistoryPages.cursor!=historyAnchor) return@withLock
        todoCursor=todos.next
        localHistoryMore=cached.next!=null
        mutable.update {
            val history=if(it.historyCloudPage) HistoryRules.latest(HistoryRules.merge(
                if(it.historyPageNumber==1) snapshot.history.filter {entry -> entry.pending} else emptyList(),it.olderHistory))
                else HistoryRules.latest(cached.items)
            it.copy(snapshot=snapshot,loading=false,todos=todos.items,todosMore=todos.next!=null,
                customers=if(it.customerQuery==customerQuery) customers.take(ListRules.PageSize) else it.customers,
                customersMore=if(it.customerQuery==customerQuery) customers.size>ListRules.PageSize else it.customersMore,
                customerConflicts=customerConflicts,
                todoPageNumber=todoPages.number,todoPrevious=todoPages.canPrevious,
                conflictPageNumber=conflictOffset/ListRules.PageSize+1,
                savedHistoryPrevious=savedHistoryPages.canPrevious,
                historyPageNumber=if(it.historyCloudPage) it.historyPageNumber else savedHistoryPages.number,
                history=if(it.historyCloudPage) history else history.take(ListRules.PageSize),
                historyMore=if(it.historyCloudPage) !it.historyEnd else localHistoryMore)
        }
        try {if(analyticsChanged) refreshLocalAnalytics(workspace)} catch(e:CancellationException) {throw e}
        catch(_:Exception) { /* Preserve the successfully published page and previous analytics. */ }
    }

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
        else mutable.update { it.copy(message="Confirm your email, then sign in.") }
    }
    fun reset(email:String)=action { app.auth.reset(email); mutable.update { it.copy(message="If an account exists for this email, you’ll receive a reset link.") } }
    fun changePassword(current:String,replacement:String,onChanged:()->Unit)=action {
        app.auth.changePassword(current,replacement)
        mutable.update { it.copy(message="Password updated.") }; onChanged()
    }
    fun deleteAccount(password:String)=action {
        observers?.cancelAndJoin(); observers=null
        historyJob?.cancelAndJoin(); analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
        try {
            app.deleteAccount(password); store=null
            mutable.update { UiState(config=app.sessions.config(),loading=false,message="Account deleted.") }
        } catch(e:Exception) {
            attach()
            throw e
        }
    }
    fun logout()=action {
        observers?.cancelAndJoin(); observers=null; historyJob?.cancelAndJoin(); analyticsJob?.cancelAndJoin(); todoJob?.cancelAndJoin()
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
    fun save(todo:Todo,before:Todo?,onSaved:()->Unit)=action {
        val workspace=store ?: error("Sign in first.")
        Rules.validate(todo,System.currentTimeMillis(),checkReminder=before==null || todo.reminderAt!=before.reminderAt,
            checkDeadline=before==null || todo.dueAt!=before.dueAt)
        workspace.save(todo,if(before==null) "create" else "edit",before)
        if(!workspace.localOnly) enqueueSync(app)
        refresh(); onSaved()
    }
    fun transition(todo:Todo,action:String)=action {
        val workspace=store ?: error("Sign in first.")
        if(!workspace.localOnly && todo.status=="completed" && workspace.completedTodo(todo.id)!=todo) {
            val current=app.auth.request("/rest/v1/todos?id=eq.${UUID.fromString(todo.id)}&select=body",method="GET",authenticated=true)
                .jsonArray.firstOrNull()?.jsonObject?.get("body")?.let { json.decodeFromJsonElement<Todo>(it) }
            check(current==todo) { "This todo changed. Refresh history." }
            val entry=mutable.value.history.find { it.todo==todo } ?: error("Refresh history.")
            workspace.rememberHistoryForAction(entry)
        }
        workspace.transition(todo,action)
        if(!workspace.localOnly) enqueueSync(app)
        refresh()
    }
    fun openCustomers() {
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
                    if(rows.size<100) {refresh(false);return@launch}
                    after=rows.last().id
                }
                refresh(false)
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

    fun saveCustomer(customer:Customer,onSaved:()->Unit)=action {
        val workspace=store ?: error("Open your shop first.")
        val profiles=OpticalStore(workspace.db,workspace.accountId,localOnly=workspace.localOnly)
        if(customer.id in mutable.value.customerConflicts) profiles.retryCustomerChanges(customer) else profiles.upsertCustomer(customer)
        if(!workspace.localOnly) enqueueSync(app)
        refresh();onSaved()
    }

    fun updateCustomers(query:CustomerDirectoryQuery) {
        mutable.update {it.copy(customerQuery=query)}
        viewModelScope.launch {
            try {refresh(analyticsChanged=false)}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(message="Couldn't load customers. Try again.")}}
        }
    }

    fun updateQuery(query:TodoQuery) {
        if(mutable.value.query==query) return
        todoJob?.cancel()
        todoCursor=null
        todoPages.reset()
        mutable.update { it.copy(query=query,todos=emptyList(),todosMore=false,todosLoading=true) }
        todoJob=viewModelScope.launch {
            try {refresh(analyticsChanged=false)} catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(message="Couldn't load tasks.")}}
            finally {if(todoJob===currentCoroutineContext()[Job]) mutable.update {it.copy(todosLoading=false)}}
        }
    }
    fun loadMoreTodos() = moveTodoPage(next=true)
    fun previousTodoPage() = moveTodoPage(next=false)
    fun firstTodoPage() = moveTodoPage(first=true)
    private fun moveTodoPage(next:Boolean=false,first:Boolean=false) {
        if(todoJob?.isActive==true) return
        mutable.update {it.copy(todosLoading=true)}
        todoJob=viewModelScope.launch {
            val previous=todoPages
            val candidate=previous.copy()
            try {
                refreshLock.withLock {
                    if(first) candidate.reset()
                    else if(next) todoCursor?.let {candidate.next(it)}
                    else candidate.previous()
                    todoPages=candidate
                }
                refresh(analyticsChanged=false)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {
                if(todoPages===candidate && mutable.value.todoPageNumber!=candidate.number) todoPages=previous
                mutable.update {it.copy(message="Couldn't load tasks.")}
            }
            finally {mutable.update {it.copy(todosLoading=false)}}
        }
    }
    fun openHistory() {
        if(historyStarted) return
        historyStarted=true
        if(store?.localOnly==false) reloadCloudHistory()
    }
    fun closeHistory() {
        historyJob?.cancel();historyStarted=false;historyGeneration++
        historyPages.reset();savedHistoryPages.reset();cloudCursor=null
        mutable.update {it.copy(olderHistory=emptyList(),historyCloudPage=false,historyPageNumber=1,historyError=null,historyLoading=false)}
        viewModelScope.launch {runCatching {refresh(analyticsChanged=false)}}
    }
    private fun reloadCloudHistory() {
        if(historyJob?.isActive==true) return
        fetchHistoryPage(cloudCursor,mutable.value.historyPageNumber)
    }
    fun nextHistoryPage() {
        val current=mutable.value
        if(current.historyLoading || !current.historyCloudPage || current.historyEnd) return
        val last=current.olderHistory.lastOrNull() ?: return
        val candidate=historyPages.copy().apply {next(ListCursor(last.occurredAt,last.todo.id))}
        fetchHistoryPage(candidate.cursor,candidate.number,candidate)
    }
    fun previousHistoryPage() {
        val current=mutable.value
        if(current.historyLoading || !historyPages.canPrevious) return
        val candidate=historyPages.copy().apply {previous()}
        fetchHistoryPage(candidate.cursor,candidate.number,candidate)
    }
    fun loadMoreHistory() = moveSavedHistory(next=true)
    fun previousSavedHistory() = moveSavedHistory(next=false)
    fun firstSavedHistory() = moveSavedHistory(first=true)
    fun firstCloudHistory() { if(historyJob?.isActive!=true) fetchHistoryPage(null,1,CursorPages()) }
    private fun moveSavedHistory(next:Boolean=false,first:Boolean=false) {
        if(historyJob?.isActive==true) return
        mutable.update {it.copy(historyLoading=true,historyError=null)}
        historyJob=viewModelScope.launch {
            val previous=savedHistoryPages
            val candidate=previous.copy()
            try {
                refreshLock.withLock {
                    if(first) candidate.reset()
                    else if(next) {
                        val workspace=store ?: return@withLock
                        workspace.historyPage(candidate.cursor).next?.let {candidate.next(it)}
                    } else candidate.previous()
                    savedHistoryPages=candidate
                }
                refresh(analyticsChanged=false)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {
                if(savedHistoryPages===candidate && mutable.value.historyPageNumber!=candidate.number) savedHistoryPages=previous
                mutable.update {it.copy(historyError="Couldn't load saved history.")}
            }
            finally {mutable.update {it.copy(historyLoading=false)}}
        }
    }
    fun retryHistory() { if(historyJob?.isActive!=true) fetchHistoryPage() }
    private fun fetchHistoryPage(cursor:ListCursor?=cloudCursor,pageNumber:Int=mutable.value.historyPageNumber,navigation:CursorPages=historyPages.copy()) {
        val workspace=store ?: return
        if(workspace.localOnly) return
        val generation=++historyGeneration
        mutable.update { it.copy(historyLoading=true,historyError=null) }
        historyJob=viewModelScope.launch {
            try {
                val body=buildJsonObject {
                    put("page_size",ListRules.PageSize)
                    cursor?.let { put("before_time",it.value); put("before_id",it.id) }
                }
                val rows=app.auth.request("/rest/v1/rpc/history_latest_page",body=body,authenticated=true)
                    .jsonArray.map { json.decodeFromJsonElement<HistoryEntry>(it) }
                if(generation!=historyGeneration || store!==workspace) return@launch
                workspace.cacheHistory(rows)
                if(generation!=historyGeneration || store!==workspace) return@launch
                historyPages=navigation
                cloudCursor=cursor
                
                mutable.update { it.copy(olderHistory=HistoryRules.latest(rows),historyEnd=rows.size<ListRules.PageSize,
                    historyError=null,historyPageNumber=pageNumber,cloudHistoryPrevious=historyPages.canPrevious,historyCloudPage=true) }
                refresh(analyticsChanged=false)
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) {
                if(generation!=historyGeneration || store!==workspace) return@launch
                android.util.Log.w("DaymarkHistory","History request failed (${(e as? ApiException)?.status ?: "transport-or-data"})")
                mutable.update { it.copy(historyError="Cloud history is unavailable.") }
            }
            finally { if(generation==historyGeneration) mutable.update { it.copy(historyLoading=false) } }
        }
    }
    fun loadAnalytics(days:Int) {
        analyticsJob?.cancel()
        val workspace=store ?: return
        analyticsDays=days
        val generation=++analyticsGeneration
        val zone=java.time.ZoneId.systemDefault().id
        mutable.update { it.copy(analyticsLoading=true,analyticsSummary=null) }
        analyticsJob=viewModelScope.launch {
            try {
                if(!workspace.localOnly) {
                    val cached=workspace.cachedAnalytics(days,zone)
                    if(generation==analyticsGeneration) mutable.update {it.copy(analyticsSummary=cached)}
                }
                refreshLocalAnalytics(workspace)
                if(workspace.localOnly) return@launch
                val response=app.auth.request("/rest/v1/rpc/analytics_summary",body=buildJsonObject {put("period_days",days);put("timezone_name",zone)},authenticated=true)
                val summary=json.decodeFromJsonElement<HistoryAnalytics>(response)
                workspace.cacheAnalytics(days,zone,summary)
                if(generation==analyticsGeneration) {
                    mutable.update {it.copy(analyticsSummary=summary)}
                    refreshLocalAnalytics(workspace)
                }
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) { /* Keep SQL local aggregates and saved cloud summary offline. */ }
            finally {if(generation==analyticsGeneration) mutable.update {it.copy(analyticsLoading=false)}}
        }
    }
    private suspend fun refreshLocalAnalytics(workspace:TodoStore) {
        val days=analyticsDays ?: return
        val generation=analyticsGeneration
        val summary=mutable.value.analyticsSummary
        val zone=java.time.ZoneId.systemDefault().id
        val local=if(summary==null || summary.priorityTrends.isEmpty()) workspace.localAnalytics(days,zone) else null
        val overlay=if(summary!=null) workspace.localAnalytics(days,zone,summary.revision) else null
        if(generation==analyticsGeneration && store===workspace && mutable.value.analyticsSummary==summary)
            mutable.update {it.copy(localAnalytics=local,pendingAnalytics=overlay)}
    }
    fun closeAnalytics() {
        analyticsDays=null;analyticsGeneration++;analyticsJob?.cancel()
        mutable.update {it.copy(analyticsLoading=false,localAnalytics=null,pendingAnalytics=null)}
    }
    fun loadMoreConflicts() {
        if(conflictOffset+ListRules.PageSize>=mutable.value.snapshot.conflictCount) return
        conflictOffset+=ListRules.PageSize
        viewModelScope.launch {runCatching {refresh(analyticsChanged=false)}}
    }
    fun previousConflicts() {
        conflictOffset=(conflictOffset-ListRules.PageSize).coerceAtLeast(0)
        viewModelScope.launch {runCatching {refresh(analyticsChanged=false)}}
    }
    fun showSavedHistory() {
        savedHistoryPages.reset()
        historyJob?.cancel();historyGeneration++;cloudCursor=null
        mutable.update {it.copy(historyCloudPage=false,historyPageNumber=1,olderHistory=emptyList(),historyLoading=false,historyError=null)}
        viewModelScope.launch {runCatching {refresh(analyticsChanged=false)}}
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
    suspend fun notificationTarget(id:String): Todo? {
        val workspace=store ?: return null
        val todo=workspace.activeTodo(id)
        return if(store===workspace) todo else null
    }
    fun openTask(id:String,onFound:(Todo?)->Unit) {
        viewModelScope.launch {onFound(runCatching {notificationTarget(id)}.getOrNull())}
    }
}
