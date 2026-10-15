package com.daymark.todo.data

import com.daymark.todo.core.*
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID

data class CachePolicy(val days:Int=30,val entries:Int=300,val bytes:Long=2L*1024*1024)
data class StorageStats(val allocatedBytes:Long,val reusableBytes:Long,val historyBytes:Long,val historyEntries:Long,val queuedOperations:Long,val activeTasks:Long=0,val deviceTaskLimit:Int=10000,val cloudTaskLimit:Int?=null,val quotaReceivedAt:Long?=null,val cloudResources:ResourceLimits?=null,val quarantinedChanges:Long=0)
data class AdmissionPolicy(val tasks:Int=10000,val pendingOperations:Int=5000,val localHistoryEvents:Int=100000,val minFreeBytes:Long=16L*1024*1024)

class TodoStore(val db: PowerSyncDatabase, val accountId: String, val localOnly: Boolean, private val cachePolicy:CachePolicy=CachePolicy(), private val admission:AdmissionPolicy=AdmissionPolicy(), private val availableBytes:()->Long={Long.MAX_VALUE}) {
    suspend fun storageStats():StorageStats {
        val pageSize=db.get("PRAGMA page_size") {it.getLong(0)!!}
        val pages=db.get("PRAGMA page_count") {it.getLong(0)!!}
        val free=db.get("PRAGMA freelist_count") {it.getLong(0)!!}
        val history=db.get("SELECT count(*),coalesce(sum(length(CAST(body AS BLOB))),0) FROM history_cache WHERE state=?",listOf(if(localOnly) "local" else "confirmed")) {it.getLong(0)!! to it.getLong(1)!!}
        val pending=db.get("SELECT (SELECT count(*) FROM journal WHERE state IN ('queued','conflict'))+(SELECT count(*) FROM quarantined_changes)") {it.getLong(0)!!}
        val quarantined=db.get("SELECT count(*) FROM quarantined_changes") {it.getLong(0)!!}
        val active=if(localOnly) db.get("SELECT count(*) FROM local_state WHERE status='active'") {it.getLong(0)!!}
            else db.get("WITH effective AS ($effectiveTodos) SELECT count(*) FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active'") {it.getLong(0)!!}
        val policy=db.getOptional("SELECT body,received_at FROM resource_limits WHERE id='policy'") {json.decodeFromString<ResourceLimits>(it.getString(0)!!) to it.getLong(1)!!}
        return StorageStats(pages*pageSize,free*pageSize,history.second,history.first,pending,active,admission.tasks,policy?.first?.maxActiveTasks,policy?.second,policy?.first,quarantined)
    }
    suspend fun clearConfirmedHistory() {
        check(!localOnly) {"Local-only history has no cloud backup."}
        db.execute("DELETE FROM history_cache WHERE state='confirmed'")
    }
    suspend fun clearLocalHistory() = writes.withLock {
        check(localOnly) {"Use cloud history cache cleanup for this account."}
        db.execute("DELETE FROM history_cache WHERE state='local'")
    }

    /** Consistent, bounded-page JSONL export. No session tokens/configuration are included. */
    suspend fun exportTo(output:Appendable) {
        val exportContext=currentCoroutineContext()
        db.readTransaction { tx ->
        output.append("{\"format\":\"daymark-export\",\"version\":1}\n")
        val sources=listOf(
            "task" to "SELECT id,body AS payload FROM ($effectiveTodos)",
            "history" to "SELECT id,body AS payload FROM history_cache",
            "operation" to "SELECT id,json_object('operation',CASE WHEN json_valid(payload) THEN json(payload) ELSE payload END,'state',state,'message',message) AS payload FROM journal",
            "localState" to "SELECT id,json_object('id',id,'status',status,'revision',revision) AS payload FROM local_state",
            "quarantinedChange" to "SELECT id,json_object('sdkData',sdk_data,'transactionId',transaction_id,'operationId',operation_id,'message',message,'createdAt',created_at) AS payload FROM quarantined_changes",
            "queuedChange" to "SELECT CAST(id AS TEXT) AS id,json_object('sdkData',data,'transactionId',tx_id) AS payload FROM ps_crud"
        )
        for((type,source) in sources) {
            var cursor=""
            while(true) {
                exportContext.ensureActive()
                val rows=tx.getAll("SELECT id,payload FROM ($source) WHERE id>? ORDER BY id LIMIT 100",listOf(cursor)) {it.getString(0)!! to it.getString(1)!!}
                for((_,payload) in rows) output.append("{\"type\":\"$type\",\"data\":$payload}\n")
                if(rows.size<100) break
                cursor=rows.last().first
            }
        }
    }
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writes = Mutex()
    private val effectiveTodos = """
        SELECT id,body FROM server_todos WHERE id NOT IN (SELECT id FROM drafts)
        UNION ALL SELECT id,body FROM active_todos WHERE id NOT IN (SELECT id FROM drafts) AND id NOT IN (SELECT id FROM server_todos)
        UNION ALL SELECT id,body FROM drafts
    """.trimIndent()

    // Terminal local orders live in history; include their latest snapshot once.
    private val allOrders = """
        $effectiveTodos
        UNION ALL SELECT json_extract(h.body,'$.todo.id') AS id,json_extract(h.body,'$.todo') AS body
        FROM history_cache h WHERE h.state<>'conflict'
        AND NOT EXISTS(SELECT 1 FROM ($effectiveTodos) e WHERE e.id=json_extract(h.body,'$.todo.id'))
        AND h.id=(SELECT h2.id FROM history_cache h2 WHERE h2.state<>'conflict'
          AND json_extract(h2.body,'$.todo.id')=json_extract(h.body,'$.todo.id')
          ORDER BY CAST(json_extract(h2.body,'$.todo.revision') AS INTEGER) DESC,h2.occurred_at DESC,h2.id DESC LIMIT 1)
    """.trimIndent()
    private val opticalStatus = """CASE
        WHEN json_extract(body,'$.status')='completed' THEN 'COMPLETE'
        WHEN json_extract(body,'$.status')='deleted' THEN 'CANCEL'
        WHEN json_extract(body,'$.orderStatus') IN ('READY','READY_FOR_PICKUP') THEN 'READY_FOR_PICKUP'
        WHEN json_extract(body,'$.orderStatus') IN ('DELIVERED','COMPLETE') THEN 'COMPLETE'
        WHEN json_extract(body,'$.orderStatus') IN ('CANCELLED','CANCEL') THEN 'CANCEL'
        ELSE 'ACTIVE' END""".trimIndent()

    /** Full diagnostic read for integration checks; application refresh uses uiSnapshot. */
    internal suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) { db.readTransaction { tx ->
        val remote = tx.getAll("SELECT body FROM server_todos WHERE json_extract(body,'$.status')='active' UNION ALL SELECT body FROM active_todos WHERE id NOT IN (SELECT id FROM server_todos)") { json.decodeFromString<Todo>(it.getString(0)!!) }
        val drafts = tx.getAll("SELECT body FROM drafts") { json.decodeFromString<Todo>(it.getString(0)!!) }
        val pending = tx.getAll("SELECT payload,state,ack_revision,message FROM journal ORDER BY created_at,id") {
            PendingOperation(json.decodeFromString<Operation>(it.getString(0)!!), it.getString(1)!!, it.getLong(2) ?: 0, it.getString(3).orEmpty())
        }
        val totals = tx.getOptional("WITH effective_totals AS (SELECT active,completed,deleted,revision FROM server_totals UNION ALL SELECT active,completed,deleted,revision FROM user_totals WHERE NOT EXISTS (SELECT 1 FROM server_totals)) SELECT active,completed,deleted,revision FROM ${if(localOnly) "local_totals" else "effective_totals"} LIMIT 1") {
            Totals((it.getLong(0) ?: 0).toInt(), (it.getLong(1) ?: 0).toInt(), (it.getLong(2) ?: 0).toInt(), it.getLong(3) ?: 0)
        } ?: Totals()
        val history = tx.getAll("SELECT body,state FROM history_cache WHERE state<>'conflict' ORDER BY occurred_at DESC,id DESC${if(localOnly) "" else " LIMIT 600"}") {
            json.decodeFromString<HistoryEntry>(it.getString(0)!!).copy(pending = it.getString(1) == "queued" || it.getString(1) == "conflict")
        }
        val inbox = tx.getAll("SELECT id,todo_id,title,time,read FROM inbox ORDER BY time DESC LIMIT 100") {
            InboxItem(it.getString(0)!!, it.getString(1)!!, it.getString(2)!!, it.getLong(3)!!, it.getLong(4) == 1L)
        }
        val overlaid = (remote.associateBy { it.id } + drafts.associateBy { it.id }).values.filter { it.status == "active" }.sortedWith(compareBy<Todo> { when(it.priority) { "High" -> 0; "Medium" -> 1; else -> 2 } }.thenBy { it.dueAt ?: Long.MAX_VALUE })
        val localStates = if(localOnly) tx.getAll("SELECT id,status,revision FROM local_state") {
            it.getString(0)!! to (it.getString(1)!! to it.getLong(2)!!)
        }.toMap() else emptyMap()
        val serverStates=tx.getAll("SELECT id,json_extract(body,'$.status'),json_extract(body,'$.revision') FROM server_todos WHERE json_extract(body,'$.status')='active' OR id IN (SELECT json_extract(body,'$.todo.id') FROM history_cache)") {
            it.getString(0)!! to (it.getString(1)!! to it.getLong(2)!!)
        }.toMap()
        val currentStates=serverStates + remote.associate {it.id to (it.status to it.revision)} + localStates + drafts.associate {it.id to (it.status to it.revision)}
        val draftById = drafts.associateBy { it.id }
        val remoteIds = remote.map { it.id }.toSet()
        val candidates = history.groupBy { it.todo.id }.mapValues { (_, entries) -> entries.maxBy { it.todo.revision } }
        val completed = candidates.values.filter { it.action == "complete" }.map { it.todo }.filter { todo ->
            if(localOnly) localStates[todo.id] == ("completed" to todo.revision)
            else {
                val draft = draftById[todo.id]
                if(draft!=null) draft==todo else todo.id !in remoteIds && (serverStates[todo.id]==null || serverStates[todo.id]==("completed" to todo.revision))
            }
        }.associateBy { it.id }
        Snapshot(overlaid, history, pending, Rules.overlayTotals(totals, pending), inbox, completed, currentStates)
    } }

    /** Page payloads plus scalar aggregates; never decodes the whole account or queue. */
    suspend fun uiSnapshot(todos: List<Todo>, history: List<HistoryEntry>, additionalIds: Collection<String> = emptyList(),
                           conflictLimit: Int = ListRules.PageSize, now: Long = System.currentTimeMillis(), conflictOffset: Int = 0): Snapshot {
        require(conflictLimit>0 && conflictOffset>=0)
        return db.readTransaction { tx ->
            val base=tx.getOptional("WITH base AS (SELECT active,completed,deleted,revision FROM server_totals UNION ALL SELECT active,completed,deleted,revision FROM user_totals WHERE NOT EXISTS(SELECT 1 FROM server_totals)) SELECT active,completed,deleted,revision FROM ${if(localOnly) "local_totals" else "base"} LIMIT 1") {
                Totals((it.getLong(0) ?: 0).toInt(),(it.getLong(1) ?: 0).toInt(),(it.getLong(2) ?: 0).toInt(),it.getLong(3) ?: 0)
            } ?: Totals()
            val totals=if(localOnly) base else tx.get("""
                SELECT coalesce(sum(CASE WHEN coalesce(json_extract(payload,'$.todo.status'),'active')='active' THEN 1 ELSE 0 END-CASE WHEN json_extract(payload,'$.beforeStatus')='active' THEN 1 ELSE 0 END),0),
                    coalesce(sum(CASE WHEN coalesce(json_extract(payload,'$.todo.status'),'active')='completed' THEN 1 ELSE 0 END-CASE WHEN json_extract(payload,'$.beforeStatus')='completed' THEN 1 ELSE 0 END),0),
                    coalesce(sum(CASE WHEN coalesce(json_extract(payload,'$.todo.status'),'active')='deleted' THEN 1 ELSE 0 END-CASE WHEN json_extract(payload,'$.beforeStatus')='deleted' THEN 1 ELSE 0 END),0)
                FROM journal WHERE state<>'conflict' AND (state<>'acked' OR ack_revision>?)
            """.trimIndent(),listOf(base.revision)) {
                base.copy(active=(base.active+it.getLong(0)!!.toInt()).coerceAtLeast(0),completed=(base.completed+it.getLong(1)!!.toInt()).coerceAtLeast(0),deleted=(base.deleted+it.getLong(2)!!.toInt()).coerceAtLeast(0))
            }
            val recoveryCount=tx.get("SELECT count(*) FROM quarantined_changes") {it.getLong(0)!!.toInt()}
            val counts=tx.get("SELECT count(CASE WHEN state='queued' THEN 1 END),count(DISTINCT CASE WHEN state='conflict' THEN json_extract(payload,'$.todo.id') END) FROM journal") { it.getLong(0)!!.toInt() to it.getLong(1)!!.toInt() }
            val conflicts=tx.getAll("""
                WITH ranked AS (SELECT payload,state,ack_revision,message,created_at,id,
                    row_number() OVER(PARTITION BY json_extract(payload,'$.todo.id') ORDER BY created_at DESC,id DESC) AS rank
                    FROM journal WHERE state='conflict')
                SELECT payload,state,ack_revision,message FROM ranked WHERE rank=1 ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?
            """.trimIndent(),listOf(conflictLimit,conflictOffset)) { PendingOperation(json.decodeFromString<Operation>(it.getString(0)!!),it.getString(1)!!,it.getLong(2)!!,it.getString(3).orEmpty()) }
            val distribution=tx.getAll("WITH effective AS ($effectiveTodos) SELECT coalesce(json_extract(body,'$.priority'),'Medium'),count(*),count(CASE WHEN json_extract(body,'$.dueAt')<? THEN 1 END) FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' GROUP BY coalesce(json_extract(body,'$.priority'),'Medium')",listOf(now)) {
                Triple(it.getString(0)!!,it.getLong(1)!!.toInt(),it.getLong(2)!!.toInt())
            }
            val (todayStart,todayEnd)=ListRules.timeBounds(now,java.time.ZoneId.systemDefault())
            val dueToday=tx.get("WITH effective AS ($effectiveTodos) SELECT count(*) FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' AND json_extract(body,'$.dueAt')>=? AND json_extract(body,'$.dueAt')<?",listOf(todayStart,todayEnd)) {it.getLong(0)!!.toInt()}
            val completedToday=if(localOnly) tx.get("""
                SELECT count(*) FROM local_state s WHERE s.status='completed' AND EXISTS(
                    SELECT 1 FROM history_cache h WHERE json_extract(h.body,'$.todo.id')=s.id
                    AND json_extract(h.body,'$.todo.revision')=s.revision AND json_extract(h.body,'$.action')='complete'
                    AND h.occurred_at>=? AND h.occurred_at<?)
            """.trimIndent(),listOf(todayStart,todayEnd)) {it.getLong(0)!!.toInt()}
            else tx.get("WITH effective AS ($effectiveTodos) SELECT count(*) FROM effective WHERE json_extract(body,'$.status')='completed' AND json_extract(body,'$.completedAt')>=? AND json_extract(body,'$.completedAt')<?",listOf(todayStart,todayEnd)) {it.getLong(0)!!.toInt()}
            val focus=tx.getOptional("WITH effective AS ($effectiveTodos) SELECT body FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' ORDER BY coalesce(json_extract(body,'$.dueAt'),9223372036854775807),CASE json_extract(body,'$.priority') WHEN 'High' THEN 0 WHEN 'Medium' THEN 1 ELSE 2 END,id LIMIT 1") {json.decodeFromString<Todo>(it.getString(0)!!)}
            val inbox=tx.getAll("SELECT id,todo_id,title,time,read FROM inbox ORDER BY time DESC LIMIT 100") { InboxItem(it.getString(0)!!,it.getString(1)!!,it.getString(2)!!,it.getLong(3)!!,it.getLong(4)==1L) }
            val ids=(todos.map {it.id}+history.map {it.todo.id}+inbox.map {it.todoId}+additionalIds).distinct()
            val states=mutableMapOf<String,Pair<String,Long>>()
            val conflictedIds=mutableSetOf<String>()
            for(chunk in ids.chunked(400)) {
                val placeholders=chunk.joinToString(",") {"?"}
                val remote=tx.getAll("SELECT id,json_extract(body,'$.status'),coalesce(json_extract(body,'$.revision'),0) FROM server_todos WHERE id IN ($placeholders)",chunk) { it.getString(0)!! to (it.getString(1).orEmpty() to it.getLong(2)!!) }
                val legacy=tx.getAll("SELECT id,coalesce(json_extract(body,'$.status'),'active'),coalesce(json_extract(body,'$.revision'),0) FROM active_todos WHERE id IN ($placeholders) AND id NOT IN(SELECT id FROM server_todos)",chunk) { it.getString(0)!! to (it.getString(1)!! to it.getLong(2)!!) }
                states.putAll(legacy);states.putAll(remote)
                if(localOnly) states.putAll(tx.getAll("SELECT id,status,revision FROM local_state WHERE id IN ($placeholders)",chunk) {it.getString(0)!! to (it.getString(1)!! to it.getLong(2)!!)})
                states.putAll(tx.getAll("SELECT id,json_extract(body,'$.status'),json_extract(body,'$.revision') FROM drafts WHERE id IN ($placeholders)",chunk) {it.getString(0)!! to (it.getString(1)!! to it.getLong(2)!!)})
                conflictedIds.addAll(tx.getAll("SELECT DISTINCT json_extract(payload,'$.todo.id') FROM journal WHERE state='conflict' AND json_extract(payload,'$.todo.id') IN ($placeholders)",chunk) {it.getString(0)!!})
            }
            val completed=history.filter {it.action=="complete" && (states[it.todo.id]==null || states[it.todo.id]==("completed" to it.todo.revision))}.associate {it.todo.id to it.todo}
            val statusCounts=tx.getAll("WITH effective AS ($allOrders) SELECT $opticalStatus,count(*) FROM effective GROUP BY $opticalStatus") {
                OrderStatus.valueOf(it.getString(0)!!) to it.getLong(1)!!.toInt()
            }.toMap()
            Snapshot(todos,history,conflicts,totals,inbox,completed,states,counts.first,counts.second,
                distribution.sumOf {it.third},distribution.associate {it.first to it.second},states.filterValues {it.first=="active"}.keys,conflictedIds,recoveryCount,dueToday,completedToday,focus,statusCounts)
        }
    }

    suspend fun completedTodo(id: String): Todo? = db.readTransaction { tx ->
        val draft=tx.getOptional("SELECT body FROM drafts WHERE id=?",listOf(id)) {json.decodeFromString<Todo>(it.getString(0)!!)}
        if(draft!=null) return@readTransaction draft.takeIf {it.status=="completed"}
        val state=if(localOnly) tx.getOptional("SELECT status,revision FROM local_state WHERE id=?",listOf(id)) {it.getString(0)!! to it.getLong(1)!!}
            else tx.getOptional("SELECT json_extract(body,'$.status'),json_extract(body,'$.revision') FROM server_todos WHERE id=?",listOf(id)) {it.getString(0)!! to it.getLong(1)!!}
        if(state!=null && state.first!="completed") return@readTransaction null
        val entry=tx.getOptional("SELECT body FROM history_cache WHERE state<>'conflict' AND json_extract(body,'$.todo.id')=? ORDER BY CAST(json_extract(body,'$.todo.revision') AS INTEGER) DESC,occurred_at DESC,id DESC LIMIT 1",listOf(id)) {json.decodeFromString<HistoryEntry>(it.getString(0)!!)}
        entry?.takeIf {it.action=="complete" && (state==null || state==("completed" to it.todo.revision))}?.todo
    }

    private val alertEvents="""
        WITH effective AS ($effectiveTodos), events AS (
            SELECT id,body,'reminder' AS kind,CAST(json_extract(body,'$.reminderAt') AS INTEGER) AS time FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' AND json_extract(body,'$.reminderAt') IS NOT NULL
            UNION ALL SELECT id,body,'deadline' AS kind,CAST(json_extract(body,'$.dueAt') AS INTEGER) AS time FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' AND json_extract(body,'$.dueAt') IS NOT NULL)
    """.trimIndent()
    suspend fun notificationSchedules(now:Long=System.currentTimeMillis()):Map<String,Long> = db.getAll("$alertEvents SELECT kind || ':' || id,time FROM events WHERE time>? ORDER BY time,kind,id LIMIT ?",listOf(now,ReminderPolicy.MaxScheduled)) {it.getString(0)!! to it.getLong(1)!!}.toMap()
    suspend fun dueTaskAlerts(now:Long=System.currentTimeMillis(),limit:Int=100):List<TaskAlert> {
        require(limit in 1..100)
        return db.getAll("""$alertEvents SELECT body,kind,time FROM events WHERE time<=? AND
            ((kind='reminder' AND NOT EXISTS(SELECT 1 FROM reminder_receipts r WHERE r.id=events.id || ':' || events.time)) OR
             (kind='deadline' AND NOT EXISTS(SELECT 1 FROM deadline_receipts r WHERE r.id='deadline:' || events.id || ':' || events.time)))
            ORDER BY time,kind,id LIMIT ?""",listOf(now,limit)) {
            TaskAlert(json.decodeFromString<Todo>(it.getString(0)!!),if(it.getString(1)=="deadline") AlertKind.Deadline else AlertKind.Reminder,it.getLong(2)!!)
        }
    }
    suspend fun addTaskAlert(alert:TaskAlert):Boolean {
        return db.writeTransaction {tx ->
            val current=tx.getOptional("WITH effective AS ($effectiveTodos) SELECT body FROM effective WHERE id=?",listOf(alert.todo.id)) {json.decodeFromString<Todo>(it.getString(0)!!)} ?: return@writeTransaction false
            if(!alert.matches(current)) return@writeTransaction false
            val deadline=alert.kind==AlertKind.Deadline
            val id=if(deadline) "deadline:${current.id}:${alert.time}" else "${current.id}:${alert.time}"
            val table=if(deadline) "deadline_receipts" else "reminder_receipts"
            val timeColumn=if(deadline) "deadline_at" else "reminder_at"
            if(tx.getOptional("SELECT id FROM $table WHERE id=?",listOf(id)) {it.getString(0)!!}!=null) return@writeTransaction false
            tx.execute("INSERT INTO $table(id,todo_id,$timeColumn) VALUES(?,?,?)",listOf(id,current.id,alert.time))
            tx.execute("INSERT OR IGNORE INTO inbox(id,todo_id,title,time,read) VALUES(?,?,?,?,0)",listOf(id,current.id,current.title,System.currentTimeMillis()))
            true
        }
    }
    suspend fun deliverDueTaskAlerts(notify:(List<TaskAlert>)->Unit):Boolean {
        val delivered=dueTaskAlerts().filter {addTaskAlert(it)}
        if(delivered.isNotEmpty()) notify(delivered)
        return dueTaskAlerts(limit=1).isNotEmpty()
    }

    suspend fun reminderSchedules(now:Long=System.currentTimeMillis(),limit:Int=ReminderPolicy.MaxScheduled):Map<String,Long> {
        require(limit in 1..ReminderPolicy.MaxScheduled)
        return db.getAll("WITH effective AS ($effectiveTodos) SELECT id,json_extract(body,'$.reminderAt') FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' AND json_extract(body,'$.reminderAt')>? ORDER BY json_extract(body,'$.reminderAt'),id LIMIT ?",listOf(now,limit)) {it.getString(0)!! to it.getLong(1)!!}.toMap()
    }

    /** SQL buckets preserve timezone/DST boundaries without deserializing event payloads. */
    suspend fun localAnalytics(days:Int,zone:String,afterRevision:Long?=null):HistoryAnalytics {
        require(days in 1..30)
        val timezone=java.time.ZoneId.of(zone)
        val today=java.time.LocalDate.now(timezone)
        val boundaries=(days-1 downTo -1).map {today.minusDays(it.toLong()).atStartOfDay(timezone).toInstant().toEpochMilli()}
        val eligible="state<>'conflict' AND (state<>'acked' OR ack_revision>?)"
        val events=if(afterRevision!=null) "SELECT id,payload AS body FROM journal WHERE $eligible" else
            "SELECT id,body FROM history_cache WHERE state<>'conflict' AND id NOT IN(SELECT id FROM journal WHERE $eligible) UNION ALL SELECT id,payload AS body FROM journal WHERE $eligible"
        val eventArgs=if(afterRevision!=null) listOf(afterRevision) else listOf(0L,0L)
        val eventTime="CAST(json_extract(body,'$.occurredAt') AS INTEGER)"
        val bucket=(0 until days).joinToString(" ") {"WHEN $eventTime>=? AND $eventTime<? THEN $it"}
        val bounds=(0 until days).flatMap {listOf(boundaries[it],boundaries[it+1])}
        return db.readTransaction {tx ->
            val rows=tx.getAll("WITH events AS ($events) SELECT CASE $bucket END AS day,coalesce(json_extract(body,'$.todo.priority'),'Medium') AS priority,count(*) FROM events WHERE json_extract(body,'$.action')='complete' AND $eventTime>=? AND $eventTime<? GROUP BY day,priority",
                eventArgs+bounds+listOf(boundaries.first(),boundaries.last())) {Triple(it.getLong(0)!!.toInt(),it.getString(1)!!,it.getLong(2)!!.toInt())}
            val deadlines=tx.get("WITH events AS ($events) SELECT count(CASE WHEN json_extract(body,'$.todo.dueAt') IS NOT NULL THEN 1 END),count(CASE WHEN json_extract(body,'$.todo.dueAt') IS NOT NULL AND $eventTime<=CAST(json_extract(body,'$.todo.dueAt') AS INTEGER) THEN 1 END) FROM events WHERE json_extract(body,'$.action')='complete'",eventArgs) {it.getLong(0)!!.toInt() to it.getLong(1)!!.toInt()}
            val priority=listOf("High","Medium","Low").associateWith {priority -> (0 until days).map {day -> rows.filter {it.first==day && it.second==priority}.sumOf {it.third}}}
            HistoryAnalytics((0 until days).map {day -> rows.filter {it.first==day}.sumOf {it.third}},deadlines.first,deadlines.second,afterRevision ?: 0,priority)
        }
    }

    suspend fun hasQueuedOperations(): Boolean = db.get("SELECT EXISTS(SELECT 1 FROM journal WHERE state='queued')") { it.getLong(0)==1L }
    suspend fun needsSync():Boolean = hasQueuedOperations() || db.get("SELECT EXISTS(SELECT 1 FROM ps_crud)") {it.getLong(0)==1L} || db.get("SELECT EXISTS(SELECT 1 FROM sync_checkpoint WHERE id='checkpoint' AND todo_id<>'ffffffff-ffff-ffff-ffff-ffffffffffff')") {it.getLong(0)==1L}

    /** Reminders don't need history, portfolio totals or the upload journal. */
    suspend fun reminderTodos(dueBefore: Long? = null, undelivered: Boolean = false, limit:Int=100): List<Todo> = withContext(Dispatchers.Default) {
        require(limit in 1..100)
        val condition=if(dueBefore==null) "" else "AND json_extract(body,'$.reminderAt')<=?"
        val delivery=if(!undelivered) "" else "AND NOT EXISTS(SELECT 1 FROM reminder_receipts r WHERE r.id=effective.id || ':' || json_extract(effective.body,'$.reminderAt'))"
        db.getAll("WITH effective AS ($effectiveTodos) SELECT body FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active' AND json_extract(body,'$.reminderAt') IS NOT NULL $condition $delivery LIMIT ?",
            (if(dueBefore==null) emptyList() else listOf(dueBefore))+listOf(limit)) { json.decodeFromString<Todo>(it.getString(0)!!) }
    }

    suspend fun activeTodo(id: String): Todo? = db.getOptional("WITH effective AS ($effectiveTodos) SELECT body FROM effective WHERE id=? AND coalesce(json_extract(body,'$.status'),'active')='active'",listOf(id)) {
        json.decodeFromString<Todo>(it.getString(0)!!)
    }

    suspend fun cachedAnalytics(days: Int, zone: String): HistoryAnalytics? = db.getOptional("SELECT body FROM analytics_cache WHERE id=?",listOf("$days:$zone")) {
        json.decodeFromString<HistoryAnalytics>(it.getString(0)!!)
    }
    suspend fun cacheAnalytics(days: Int, zone: String, summary: HistoryAnalytics) {
        db.execute("INSERT OR REPLACE INTO analytics_cache(id,body) VALUES(?,?)",listOf("$days:$zone",json.encodeToString(summary)))
    }

    /** Preserve the exact SDK row before acknowledging a single unreadable command. */
    suspend fun quarantineQueuedChange(entry:com.powersync.db.crud.CrudEntry) = writes.withLock {
        db.writeTransaction { tx ->
            val raw=tx.getOptional("SELECT data,tx_id FROM ps_crud WHERE id=?",listOf(entry.clientId)) {
                it.getString(0)!! to it.getLong(1)
            } ?: error("Queued change disappeared; recovery stopped.")
            val existing=tx.getOptional("SELECT sdk_data FROM quarantined_changes WHERE id=?",listOf(entry.clientId.toString())) {it.getString(0)!!}
            check(existing==null || existing==raw.first) {"Recovery record does not match queued change."}
            if(existing==null) {
                val count=tx.get("SELECT count(*) FROM quarantined_changes") {it.getLong(0)!!}
                check(count<admission.pendingOperations) {"Recovery storage is full. Export device data and contact support. Queued work is retained."}
                tx.execute("INSERT INTO quarantined_changes(id,sdk_data,transaction_id,operation_id,message,created_at) VALUES(?,?,?,?,?,?)",
                    listOf(entry.clientId.toString(),raw.first,raw.second,entry.id,"Saved change could not be read. Export device data and contact support.",System.currentTimeMillis()))
            }
            // A valid journal copy can use the ordinary conflict-resolution flow.
            val saved=tx.getOptional("SELECT payload FROM journal WHERE id=?",listOf(entry.id)) {it.getString(0)!!}
            if(saved!=null) {
                val decoded=runCatching {json.decodeFromString<Operation>(saved)}.getOrNull()
                check(decoded!=null && decoded.id==entry.id) {"Journal recovery is required. Original queued work is retained."}
                tx.execute("UPDATE journal SET state='conflict',message=? WHERE id=?",listOf("Queued change could not be read. Save a corrected copy or keep the remote version; the original is retained in device export.",entry.id))
                tx.execute("UPDATE history_cache SET state='conflict' WHERE id=?",listOf(entry.id))
            }
        }
    }
    suspend fun operationTime(id: String): Long? = db.getOptional("SELECT created_at FROM journal WHERE id=?",listOf(id)) {it.getLong(0)!!}

    suspend fun uploadPause():com.daymark.todo.identity.ApiException? {
        val pause=db.getOptional("SELECT body FROM upload_pause WHERE id='pause'") {json.decodeFromString<UploadPause>(it.getString(0)!!)} ?: return null
        if(pause.retryAt!=null && System.currentTimeMillis()>=pause.retryAt) {
            db.execute("DELETE FROM upload_pause WHERE id='pause'");return null
        }
        return com.daymark.todo.identity.ApiException(429,pause.message,com.daymark.todo.identity.AdmissionFailure.valueOf(pause.category))
    }
    suspend fun pauseUploads(error:com.daymark.todo.identity.ApiException) {
        val category=error.admissionFailure ?: return
        val retryAt=if(error.permanentQuota) null else System.currentTimeMillis()+(error.retryAfterSeconds ?: 60)*1000L+kotlin.random.Random.nextLong(1000)
        db.execute("INSERT OR REPLACE INTO upload_pause(id,body) VALUES('pause',?)",listOf(json.encodeToString(UploadPause(category.name,error.message.orEmpty(),retryAt))))
    }

    suspend fun needsInitialSync():Boolean=db.get("SELECT NOT EXISTS(SELECT 1 FROM sync_checkpoint WHERE id='checkpoint')") {it.getLong(0)==1L}
    suspend fun needsExchange():Boolean=!localOnly && (needsSync() || needsInitialSync())

    suspend fun syncCursor(): ListCursor = db.getOptional("SELECT revision,todo_id FROM sync_checkpoint WHERE id='checkpoint'") {
        ListCursor(it.getLong(0)!!,it.getString(1)!!)
    } ?: ListCursor(0,"00000000-0000-0000-0000-000000000000")

    suspend fun applyServerPage(rows: List<ServerChange>, cursor: ListCursor, totals: Totals?, limits:ResourceLimits?=null) {
        limits?.validate()
        db.writeTransaction { tx ->
            val previous=tx.getOptional("SELECT revision,todo_id FROM sync_checkpoint WHERE id='checkpoint'") { ListCursor(it.getLong(0)!!,it.getString(1)!!) }
            val previousTotals=if(totals==null) null else tx.getOptional("SELECT active,completed,deleted,revision FROM server_totals WHERE id='totals'") {
                Totals((it.getLong(0) ?: 0).toInt(),(it.getLong(1) ?: 0).toInt(),(it.getLong(2) ?: 0).toInt(),it.getLong(3) ?: 0)
            }
            for(row in rows) {
                check(row.id==row.todo.id) { "Invalid server task." }
                tx.execute("INSERT OR REPLACE INTO server_todos(id,body,account_revision) VALUES(?,?,?)",listOf(row.id,json.encodeToString(if(row.todo.status=="active") row.todo else row.todo.copy(title="",notes="",dueAt=null,reminderAt=null)),row.accountRevision))
            }
            if(totals!=null && totals!=previousTotals) tx.execute("INSERT OR REPLACE INTO server_totals(id,active,completed,deleted,revision) VALUES('totals',?,?,?,?)",listOf(totals.active,totals.completed,totals.deleted,totals.revision))
            val previousLimits=if(limits==null) null else tx.getOptional("SELECT body FROM resource_limits WHERE id='policy'") {json.decodeFromString<ResourceLimits>(it.getString(0)!!)}
            if(limits!=null && limits!=previousLimits) tx.execute("INSERT OR REPLACE INTO resource_limits(id,body,received_at) VALUES('policy',?,?)",listOf(json.encodeToString(limits),System.currentTimeMillis()))
            val pause=tx.getOptional("SELECT body FROM upload_pause WHERE id='pause'") {json.decodeFromString<UploadPause>(it.getString(0)!!)}
            val quotaAvailable=when(pause?.category) {
                "TASK_QUOTA" -> limits!=null && totals!=null && totals.active<limits.maxActiveTasks
                "HISTORY_QUOTA" -> limits?.historyEvents!=null && limits.maxHistoryEvents!=null && limits.historyEvents<limits.maxHistoryEvents
                "RECEIPT_QUOTA" -> limits?.receipts!=null && limits.maxReceipts!=null && limits.receipts<limits.maxReceipts
                else -> false
            }
            if(quotaAvailable) tx.execute("DELETE FROM upload_pause WHERE id='pause'")
            if(cursor!=previous) tx.execute("INSERT OR REPLACE INTO sync_checkpoint(id,revision,todo_id) VALUES('checkpoint',?,?)",listOf(cursor.value,cursor.id))
        }
    }

    suspend fun todoPage(query: TodoQuery = TodoQuery(), cursor: ListCursor? = null, limit: Int = ListRules.PageSize,
                         now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): TodoPage {
        require(limit > 0 && limit < Int.MAX_VALUE)
        val (start, end) = ListRules.timeBounds(now, zone)
        val weekEnd = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val where = mutableListOf("1=1")
        val args = mutableListOf<Any?>()
        if(query.search.isNotBlank()) {
            val term="%${query.search.trim().lowercase().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")}%"
            val phone=ListRules.phoneSearch(query.search)?.let {"%$it%"} ?: term
            where += "(lower(coalesce(json_extract(body,'$.title'),'')) LIKE ? ESCAPE '\\' OR lower(coalesce(json_extract(body,'$.customerMobile'),'')) LIKE ? ESCAPE '\\' OR lower(coalesce(json_extract(body,'$.normalizedMobile'),'')) LIKE ? ESCAPE '\\' OR lower(coalesce(json_extract(body,'$.orderNumber'),'')) LIKE ? ESCAPE '\\')"
            args += term;args += term;args += phone;args += term
        }
        if(query.priority != null) { where += "coalesce(json_extract(body,'$.priority'),'Medium')=?"; args += query.priority }
        if(query.orderStatus != null) { where += "($opticalStatus)=?"; args += query.orderStatus.name }
        when(query.deadline) {
            DeadlineFilter.Any -> Unit
            DeadlineFilter.Overdue -> { where += "json_extract(body,'$.dueAt')<?"; args += now }
            DeadlineFilter.Today -> { where += "json_extract(body,'$.dueAt')>=? AND json_extract(body,'$.dueAt')<?"; args += start; args += end }
            DeadlineFilter.Week -> { where += "json_extract(body,'$.dueAt')>=? AND json_extract(body,'$.dueAt')<?"; args += now; args += weekEnd }
            DeadlineFilter.None -> where += "json_extract(body,'$.dueAt') IS NULL"
        }
        val sort = when(query.sort) {
            TodoSort.Deadline -> "coalesce(json_extract(body,'$.dueAt'),9223372036854775807)"
            TodoSort.LatestDeadline -> "coalesce(-json_extract(body,'$.dueAt'),9223372036854775807)"
            TodoSort.Latest -> "-coalesce(json_extract(body,'$.updatedAt'),0)"
            TodoSort.Newest -> "-coalesce(json_extract(body,'$.orderedAt'),0)"
            TodoSort.Oldest -> "coalesce(json_extract(body,'$.orderedAt'),0)"
            TodoSort.Priority -> "CASE coalesce(json_extract(body,'$.priority'),'Medium') WHEN 'High' THEN 0 WHEN 'Medium' THEN 1 ELSE 2 END"
        }
        if(cursor != null) { where += "($sort,id)>(?,?)"; args += cursor.value; args += cursor.id }
        args += limit + 1
        val rows = db.getAll("""
            WITH effective AS ($allOrders) SELECT body,$opticalStatus FROM effective WHERE ${where.joinToString(" AND ")} ORDER BY $sort,id LIMIT ?
        """.trimIndent(), args) { json.decodeFromString<Todo>(it.getString(0)!!).copy(orderStatus=OrderStatus.valueOf(it.getString(1)!!)) }
        val page = rows.take(limit)
        return TodoPage(page, if(rows.size > limit) page.last().let { ListCursor(ListRules.sortKey(it,query.sort),it.id) } else null)
    }

    suspend fun historyPage(cursor: ListCursor? = null, limit: Int = ListRules.PageSize): HistoryPage {
        require(limit > 0 && limit < Int.MAX_VALUE)
        val args = mutableListOf<Any?>()
        val condition = if(cursor == null) "" else "AND (occurred_at,todo_id)<(?,?)".also { args += cursor.value; args += cursor.id }
        args += limit + 1
        val rows = db.getAll("""
            WITH ranked AS (
                SELECT body,state,occurred_at,json_extract(body,'$.todo.id') AS todo_id,
                    row_number() OVER (PARTITION BY json_extract(body,'$.todo.id') ORDER BY
                        CASE WHEN state='queued' THEN 1 ELSE 0 END DESC,
                        CAST(json_extract(body,'$.todo.revision') AS INTEGER) DESC,occurred_at DESC,id DESC) AS rank
                FROM history_cache WHERE state<>'conflict'
            ) SELECT body,state FROM ranked WHERE rank=1 $condition ORDER BY occurred_at DESC,todo_id DESC LIMIT ?
        """.trimIndent(),args) { json.decodeFromString<HistoryEntry>(it.getString(0)!!).copy(pending=it.getString(1)=="queued") }
        val page=rows.take(limit)
        return HistoryPage(page,if(rows.size>limit) page.last().let { ListCursor(it.occurredAt,it.todo.id) } else null)
    }

    private fun admit(tx:com.powersync.db.internal.PowerSyncTransaction, action:String, replacingConflictTodoId:String?=null) {
        check(availableBytes()>=admission.minFreeBytes) {"Device storage is low. Free device space before saving more changes. Your existing work is retained; cleanup and export are available in Settings."}
        if(!localOnly) {
            val pending=tx.get("SELECT count(*) FROM journal WHERE state IN ('queued','conflict') AND (? IS NULL OR json_extract(payload,'$.todo.id')<>?)",listOf(replacingConflictTodoId,replacingConflictTodoId)) {it.getLong(0)!!}
            val quarantined=tx.get("SELECT count(*) FROM quarantined_changes") {it.getLong(0)!!}
            check(pending+quarantined<admission.pendingOperations) {"Too many pending changes. Sync or resolve conflicts before saving more. Existing work is retained."}
        }
        if(action in listOf("create","reopen")) {
            val tasks=if(localOnly) tx.get("SELECT count(*) FROM local_state WHERE status='active'") {it.getLong(0)!!}
                else tx.get("WITH effective AS ($effectiveTodos) SELECT count(*) FROM effective WHERE coalesce(json_extract(body,'$.status'),'active')='active'") {it.getLong(0)!!}
            val cloudLimit=if(localOnly) null else tx.getOptional("SELECT body FROM resource_limits WHERE id='policy'") {json.decodeFromString<ResourceLimits>(it.getString(0)!!).maxActiveTasks}
            val limit=minOf(admission.tasks,cloudLimit ?: admission.tasks)
            check(tasks<limit) {"Active task limit reached ($limit). Complete or delete an active task before creating or reopening another. Offline saves await cloud confirmation."}
        }
        if(localOnly && action in listOf("complete","delete","reopen")) {
            val history=tx.get("SELECT count(*) FROM history_cache WHERE state='local'") {it.getLong(0)!!}
            check(history<admission.localHistoryEvents) {"Local history limit reached. Export and clear local history before saving more transitions."}
        }
    }

    /** Bounded source scan; task bodies already removed by explicit history cleanup are excluded. */
    suspend fun guestTransferPage(after:String,limit:Int=25):List<Todo> {
        check(localOnly)
        return db.getAll("""
            SELECT s.id,coalesce(d.body,(SELECT json_extract(h.body,'$.todo') FROM history_cache h
                WHERE json_extract(h.body,'$.todo.id')=s.id ORDER BY CAST(json_extract(h.body,'$.todo.revision') AS INTEGER) DESC,occurred_at DESC,id DESC LIMIT 1)) AS body
            FROM local_state s LEFT JOIN drafts d ON d.id=s.id
            WHERE s.id>? AND (d.body IS NOT NULL OR EXISTS(SELECT 1 FROM history_cache h WHERE json_extract(h.body,'$.todo.id')=s.id))
            ORDER BY s.id LIMIT ?
        """.trimIndent(),listOf(after,limit)) {json.decodeFromString<Todo>(it.getString(1)!!)}
    }
    suspend fun guestTaskHistory(id:String):List<HistoryEntry> {
        check(localOnly)
        return db.getAll("SELECT body FROM history_cache WHERE json_extract(body,'$.todo.id')=? ORDER BY occurred_at,id",listOf(id)) {json.decodeFromString<HistoryEntry>(it.getString(0)!!)}
    }
    suspend fun receiveGuestInbox(items:List<InboxItem>) = writes.withLock {
        check(!localOnly && items.size<=100)
        db.writeTransaction {tx ->
            for(item in items) {
                val taskId=TaskImport.copyId(accountId,GuestTransfer.sourceKey(item.todoId))
                val id=TaskImport.copyId(accountId,"guest-inbox-v1:${item.id}")
                tx.execute("INSERT OR IGNORE INTO inbox(id,todo_id,title,time,read) VALUES(?,?,?,?,?)",listOf(id,taskId,item.title,item.time,if(item.read) 1 else 0))
            }
            tx.execute("DELETE FROM inbox WHERE id NOT IN (SELECT id FROM inbox ORDER BY time DESC,id DESC LIMIT 100)")
        }
    }

    suspend fun guestTaskTransferred(sourceId:String):Boolean = db.getOptional("SELECT id FROM import_receipts WHERE id=?",listOf(TaskImport.copyId(accountId,GuestTransfer.sourceKey(sourceId)))) {it.getString(0)!!}!=null

    /** Chunk and progress commit together; a long history can drain through the bounded upload queue. */
    suspend fun receiveGuestTask(todo:Todo,history:List<HistoryEntry>):Boolean = writes.withLock {
        check(!localOnly)
        val operations=GuestTransfer.plan(accountId,todo,history,System.currentTimeMillis())
        val id=operations.first().todo.id
        db.writeTransaction {tx ->
            if(tx.getOptional("SELECT id FROM import_receipts WHERE id=?",listOf(id)) {it.getString(0)!!}!=null) return@writeTransaction false
            val progressId="guest-progress:$id"
            val progress=tx.getOptional("SELECT created_at FROM import_receipts WHERE id=?",listOf(progressId)) {it.getLong(0)!!.toInt()}
            if(progress==null) check(tx.getOptional("WITH effective AS ($effectiveTodos) SELECT id FROM effective WHERE id=?",listOf(id)) {it.getString(0)!!}==null) {"A guest task needs conflict review."}
            val start=progress ?: 0
            val pending=tx.get("SELECT (SELECT count(*) FROM journal WHERE state IN ('queued','conflict'))+(SELECT count(*) FROM quarantined_changes)") {it.getLong(0)!!.toInt()}
            val capacity=(admission.pendingOperations-pending).coerceAtLeast(0)
            check(capacity>0) {"Sync pending changes to continue moving guest tasks."}
            val end=minOf(operations.size,start+minOf(100,capacity))
            for(op in operations.subList(start,end)) {
                admit(tx,op.action)
                val payload=json.encodeToString(op)
                tx.execute("INSERT INTO journal(id,payload,state,ack_revision,message,created_at) VALUES(?,?,'queued',0,'',?)",listOf(op.id,payload,op.occurredAt))
                tx.execute("INSERT OR REPLACE INTO drafts(id,body,operation_id) VALUES(?,?,?)",listOf(id,json.encodeToString(op.todo),op.id))
                tx.execute("INSERT INTO operation_requests(id,payload) VALUES(?,?)",listOf(op.id,payload))
                if(op.action in listOf("complete","delete","reopen")) {
                    val entry=HistoryEntry(op.id,op.todo,op.action,op.occurredAt,true)
                    tx.execute("INSERT INTO history_cache(id,body,occurred_at,state) VALUES(?,?,?,'queued')",listOf(op.id,json.encodeToString(entry),op.occurredAt))
                }
            }
            if(end==operations.size) {
                tx.execute("DELETE FROM import_receipts WHERE id=?",listOf(progressId))
                tx.execute("INSERT INTO import_receipts(id,created_at) VALUES(?,?)",listOf(id,System.currentTimeMillis()))
            } else tx.execute("INSERT OR REPLACE INTO import_receipts(id,created_at) VALUES(?,?)",listOf(progressId,end))
            true
        }
    }

    suspend fun hasTaskId(id: String): Boolean = db.getOptional("""
        SELECT id FROM drafts WHERE id=?
        UNION ALL SELECT id FROM server_todos WHERE id=?
        UNION ALL SELECT id FROM active_todos WHERE id=?
        UNION ALL SELECT id FROM local_state WHERE id=?
        UNION ALL SELECT id FROM import_receipts WHERE id=?
        UNION ALL SELECT id FROM history_cache WHERE json_extract(body,'$.todo.id')=?
        LIMIT 1
    """.trimIndent(),listOf(id,id,id,id,id,id)) {it.getString(0)!!}!=null

    suspend fun save(todo: Todo, action: String, before: Todo?, importedSourceId: String? = null) = writes.withLock {
        val now = System.currentTimeMillis()
        Rules.validate(todo, now,
            checkReminder=action in listOf("create", "edit") && (before == null || todo.reminderAt != before.reminderAt),
            checkDeadline=false)
        if(importedSourceId!=null) check(action=="create" && before==null && todo.id==TaskImport.copyId(accountId,importedSourceId)) {"Invalid import identity."}
        db.writeTransaction { tx ->
            check(tx.getOptional("SELECT id FROM import_receipts WHERE id=?",listOf("guest-progress:${todo.id}")) {it.getString(0)!!}==null) {"This guest task is still moving. Try again shortly."}
            if(importedSourceId!=null) {
                check(tx.getOptional("SELECT id FROM import_receipts WHERE id=?",listOf(todo.id)) {it.getString(0)!!}==null) {"This task was already imported."}
                check(tx.get("SELECT count(*) FROM import_receipts") {it.getLong(0)!!}<100000) {"Import receipt limit reached. Keep the export and contact support."}
            }
            admit(tx,action)
            if (!localOnly && before != null) {
                val current = tx.getOptional("SELECT body FROM drafts WHERE id=?", listOf(todo.id)) { json.decodeFromString<Todo>(it.getString(0)!!) }
                    ?: tx.getOptional("SELECT body FROM server_todos WHERE id=?", listOf(todo.id)) { json.decodeFromString<Todo>(it.getString(0)!!) }
                    ?: tx.getOptional("SELECT body FROM active_todos WHERE id=?", listOf(todo.id)) { json.decodeFromString<Todo>(it.getString(0)!!) }
                if (current != null) check(if(before.status=="completed") current.status==before.status && current.revision==before.revision else current == before) { "This todo has changed. Open the latest version before editing." }
                else if (before.status == "completed") {
                    val latest = tx.getOptional("SELECT body FROM history_cache WHERE state<>'conflict' AND json_extract(body,'$.todo.id')=? ORDER BY CAST(json_extract(body,'$.todo.revision') AS INTEGER) DESC,occurred_at DESC,id DESC LIMIT 1",listOf(before.id)) { json.decodeFromString<HistoryEntry>(it.getString(0)!!) }
                    check(latest?.todo == before && latest.action == "complete") { "This todo has changed. Open the latest version before editing." }
                } else error("This todo has changed. Open the latest version before editing.")
            }
            val previous = tx.getOptional("SELECT operation_id FROM drafts WHERE id=?", listOf(todo.id)) { it.getString(0)!! }
            val op = Operation(UUID.randomUUID().toString(), todo.copy(updatedAt = now, revision=if(localOnly) todo.revision else (before?.revision ?: 0)+1), action, before?.revision ?: 0, previous, now, before?.status ?: "absent")
            val payload = json.encodeToString(op)
            if (localOnly) {
                val current=tx.getOptional("SELECT status,revision FROM local_state WHERE id=?",listOf(todo.id)) {it.getString(0)!! to it.getLong(1)!!}
                if(before==null) check(current==null) {"This todo already exists."}
                else check(current!=null && current.first==before.status && current.second==before.revision) {"This todo has changed. Open the latest version before editing."}
                val base = tx.getOptional("SELECT active,completed,deleted,revision FROM local_totals LIMIT 1") { Totals((it.getLong(0) ?: 0).toInt(),(it.getLong(1) ?: 0).toInt(),(it.getLong(2) ?: 0).toInt(),it.getLong(3) ?: 0) } ?: Totals()
                val updated = Rules.overlayTotals(base, listOf(PendingOperation(op,"queued"))).copy(revision=base.revision+1)
                tx.execute("INSERT OR REPLACE INTO local_totals(id,active,completed,deleted,revision) VALUES('totals',?,?,?,?)", listOf(updated.active,updated.completed,updated.deleted,updated.revision))
                if (todo.status == "active") tx.execute("INSERT OR REPLACE INTO drafts(id,body,operation_id) VALUES(?,?,?)", listOf(todo.id,json.encodeToString(op.todo.copy(revision=todo.revision+1)),op.id))
                else tx.execute("DELETE FROM drafts WHERE id=?",listOf(todo.id))
                tx.execute("INSERT OR REPLACE INTO local_state(id,status,revision) VALUES(?,?,?)",listOf(todo.id,todo.status,todo.revision+1))
            } else {
                tx.execute("INSERT INTO journal(id,payload,state,ack_revision,message,created_at) VALUES(?,?,'queued',0,'',?)", listOf(op.id, payload, now))
                tx.execute("INSERT OR REPLACE INTO drafts(id,body,operation_id) VALUES(?,?,?)", listOf(todo.id, json.encodeToString(op.todo), op.id))
                tx.execute("INSERT INTO operation_requests(id,payload) VALUES(?,?)", listOf(op.id, payload))
            }
            if(importedSourceId!=null) tx.execute("INSERT INTO import_receipts(id,created_at) VALUES(?,?)",listOf(todo.id,now))
            if (action in listOf("complete", "delete", "reopen")) {
                val h = HistoryEntry(op.id, if(localOnly) op.todo.copy(revision=todo.revision+1) else op.todo, action, now, !localOnly)
                tx.execute("INSERT INTO history_cache(id,body,occurred_at,state) VALUES(?,?,?,?)", listOf(op.id, json.encodeToString(h), now, if (localOnly) "local" else "queued"))
            }
        }
    }

    suspend fun transition(todo: Todo, action: String) = save(Rules.transition(todo, action, System.currentTimeMillis()), action, todo)

    suspend fun recordResult(op: Operation, accepted: Boolean, accountRevision: Long, todoRevision: Long, message: String) {
        db.writeTransaction { tx ->
            tx.execute("UPDATE journal SET state=?,ack_revision=?,message=? WHERE id=?", listOf(if(accepted) "acked" else "conflict", accountRevision, message, op.id))
            tx.execute("UPDATE history_cache SET state=? WHERE id=?", listOf(if(accepted) "confirmed" else "conflict", op.id))
            if(accepted && op.action in listOf("complete","delete","reopen")) {
                val confirmed=HistoryEntry(op.id,op.todo.copy(revision=todoRevision),op.action,op.occurredAt,false)
                tx.execute("UPDATE history_cache SET body=? WHERE id=?",listOf(json.encodeToString(confirmed),op.id))
            }
        }
    }

    suspend fun reconcile() {
        db.writeTransaction { tx ->
            val rev = tx.getOptional("SELECT revision FROM server_totals UNION ALL SELECT revision FROM user_totals WHERE NOT EXISTS (SELECT 1 FROM server_totals) LIMIT 1") { it.getLong(0) ?: 0 } ?: 0
            tx.execute("DELETE FROM drafts WHERE operation_id IN (SELECT id FROM journal WHERE state='acked' AND ack_revision<=?)", listOf(rev))
            // Keep acknowledgments used as predecessors until all queued chains have finished.
            tx.execute("DELETE FROM journal WHERE state='acked' AND ack_revision<=? AND id NOT IN (SELECT operation_id FROM drafts)", listOf(rev))
            // Local-only history has no remote copy, so it is never automatically evicted.
            if (!localOnly) tx.execute("DELETE FROM history_cache WHERE state='confirmed' AND (occurred_at<? OR id NOT IN (SELECT id FROM history_cache WHERE state='confirmed' ORDER BY occurred_at DESC LIMIT ?))", listOf(System.currentTimeMillis() - cachePolicy.days*86400000L,cachePolicy.entries))
            if (!localOnly) {
                val cache=tx.getAll("SELECT id,length(CAST(body AS BLOB)) FROM history_cache WHERE state='confirmed' ORDER BY occurred_at DESC,id DESC") {it.getString(0)!! to (it.getLong(1) ?: 0)}
                var bytes=0L
                for ((id,size) in cache) {bytes+=size;if(bytes>cachePolicy.bytes) tx.execute("DELETE FROM history_cache WHERE id=?",listOf(id))}
            }
            if(!localOnly) tx.execute("DELETE FROM server_todos WHERE json_extract(body,'$.status')<>'active' AND id NOT IN (SELECT id FROM active_todos UNION SELECT id FROM drafts UNION SELECT json_extract(body,'$.todo.id') FROM history_cache UNION SELECT json_extract(payload,'$.todo.id') FROM journal)")
            tx.execute("DELETE FROM analytics_cache WHERE id NOT IN (SELECT id FROM analytics_cache ORDER BY id DESC LIMIT 4)")
            tx.execute("WITH effective AS ($effectiveTodos) DELETE FROM deadline_receipts WHERE id NOT IN (SELECT id FROM inbox) AND NOT EXISTS (SELECT 1 FROM effective WHERE effective.id=deadline_receipts.todo_id AND json_extract(body,'$.status')='active' AND json_extract(body,'$.dueAt')=deadline_receipts.deadline_at)")
            tx.execute("WITH effective AS ($effectiveTodos) DELETE FROM reminder_receipts WHERE id NOT IN (SELECT id FROM inbox) AND NOT EXISTS (SELECT 1 FROM effective WHERE effective.id=reminder_receipts.todo_id AND json_extract(body,'$.status')='active' AND json_extract(body,'$.reminderAt')=reminder_receipts.reminder_at)")
            tx.execute("DELETE FROM inbox WHERE id NOT IN (SELECT id FROM inbox ORDER BY time DESC LIMIT 100)")
        }
    }

    suspend fun keepRemote(todoId: String, replacement: Todo? = null) = writes.withLock {
        db.writeTransaction { tx ->
            val ids = tx.getAll("SELECT id,payload,state FROM journal WHERE json_extract(payload,'$.todo.id')=?",listOf(todoId)) {
                Triple(it.getString(0)!!, json.decodeFromString<Operation>(it.getString(1)!!), it.getString(2)!!)
            }
            check(ids.all { it.third != "queued" }) { "Wait for sync to finish before resolving this conflict." }
            if (replacement != null) {
                check(!localOnly && replacement.id != todoId) { "Use a new todo ID for the replacement." }
                Rules.validate(replacement, System.currentTimeMillis())
                admit(tx,"create",todoId)
                val op = Operation(UUID.randomUUID().toString(), replacement, "create", 0)
                val payload = json.encodeToString(op)
                tx.execute("INSERT INTO journal(id,payload,state,ack_revision,message,created_at) VALUES(?,?,'queued',0,'',?)", listOf(op.id,payload,op.occurredAt))
                tx.execute("INSERT INTO drafts(id,body,operation_id) VALUES(?,?,?)", listOf(replacement.id,json.encodeToString(replacement),op.id))
                tx.execute("INSERT INTO operation_requests(id,payload) VALUES(?,?)", listOf(op.id,payload))
            }
            for ((id, _, _) in ids) { tx.execute("DELETE FROM journal WHERE id=?", listOf(id)); tx.execute("DELETE FROM history_cache WHERE id=? AND state='conflict'", listOf(id)) }
            tx.execute("DELETE FROM drafts WHERE id=?", listOf(todoId))
        }
    }
    private fun cacheConfirmedHistory(tx:com.powersync.db.internal.PowerSyncTransaction, entry:HistoryEntry) {
        tx.execute("INSERT OR REPLACE INTO history_cache(id,body,occurred_at,state) SELECT ?,?,?,'confirmed' WHERE NOT EXISTS (SELECT 1 FROM history_cache WHERE id=? AND state IN ('queued','conflict','local'))",
            listOf(entry.id,json.encodeToString(entry.copy(pending=false)),entry.occurredAt,entry.id))
    }
    suspend fun rememberHistoryForAction(entry: HistoryEntry) {
        db.writeTransaction {tx -> cacheConfirmedHistory(tx,entry)}
    }
    suspend fun cacheHistory(entries: List<HistoryEntry>) {
        db.writeTransaction {tx -> entries.forEach {cacheConfirmedHistory(tx,it)}}
        reconcile()
    }

    /** Inbox-only refresh avoids task, history, journal and analytics queries. */
    suspend fun inboxItems():List<InboxItem> = db.getAll("SELECT id,todo_id,title,time,read FROM inbox ORDER BY time DESC,id DESC LIMIT 100") {
        InboxItem(it.getString(0)!!,it.getString(1)!!,it.getString(2)!!,it.getLong(3)!!,it.getLong(4)==1L)
    }
    suspend fun markRead(id: String) { db.execute("UPDATE inbox SET read=1 WHERE id=?", listOf(id)) }
    suspend fun addAlert(todo: Todo): Boolean = db.writeTransaction { tx ->
        require(todo.reminderAt!=null)
        val id = "${todo.id}:${todo.reminderAt}"
        if(tx.getOptional("SELECT id FROM reminder_receipts WHERE id=?",listOf(id)) {it.getString(0)!!}!=null) return@writeTransaction false
        val existing=tx.getOptional("SELECT id FROM inbox WHERE id=?",listOf(id)) {it.getString(0)!!}!=null
        tx.execute("INSERT INTO reminder_receipts(id,todo_id,reminder_at) VALUES(?,?,?)",listOf(id,todo.id,todo.reminderAt))
        if(existing) return@writeTransaction false
        tx.execute("INSERT INTO inbox(id,todo_id,title,time,read) VALUES(?,?,?,?,0)", listOf(id, todo.id, todo.title, System.currentTimeMillis()))
        true
    }
    suspend fun missedReminders(limit:Int=100): List<Todo> {
        val delivered=mutableListOf<Todo>()
        for(todo in reminderTodos(dueBefore=System.currentTimeMillis(),undelivered=true,limit=limit)) if(addAlert(todo)) delivered+=todo
        return delivered
    }

    /** One bounded batch; receipts checkpoint progress before any continuation. */
    suspend fun deliverMissedReminderBatch(notifyBatch:(List<Todo>)->Unit,limit:Int=100):Boolean {
        require(limit in 1..100)
        val batch=missedReminders(limit)
        if(batch.isNotEmpty()) notifyBatch(batch)
        return reminderTodos(dueBefore=System.currentTimeMillis(),undelivered=true,limit=1).isNotEmpty()
    }
    suspend fun deliverMissedReminders(notify:(Todo)->Unit):Boolean =
        deliverMissedReminderBatch({it.forEach(notify)},100)
}
