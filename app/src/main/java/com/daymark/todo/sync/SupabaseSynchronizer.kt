package com.daymark.todo.sync

import com.daymark.todo.core.*
import com.daymark.todo.data.TodoStore
import com.daymark.todo.identity.BackendTransport
import com.daymark.todo.identity.ApiException
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*

/** SDK SQLite keeps the durable command queue; Supabase is the only hosted service. */
class SupabaseSynchronizer(private val store: TodoStore, private val backend: BackendTransport) {
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    private suspend fun uploadOne(op:Operation):JsonElement = try {
        backend.request("/rest/v1/rpc/apply_operation",body=buildJsonObject {put("payload",json.encodeToJsonElement(op))},authenticated=true)
    } catch(e:ApiException) {
        if(e.status !in setOf(400,413)) throw e
        buildJsonObject {
            put("accepted",false);put("accountRevision",store.syncCursor().value);put("todoRevision",op.baseRevision)
            put("message","Operation could not be validated. Save a corrected copy or keep the remote version.")
        }
    }
    // Reserve 64 KiB below the server's JSONB-text cap for its formatting overhead.
    private val batchByteBudget=192*1024
    private fun receipt(op:Operation,result:JsonElement)=buildJsonObject {
        put("id",op.id);put("result",result)
    }
    private suspend fun uploadGroup(operations:List<Operation>):List<JsonElement> {
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        if(operations.size==1) return listOf(receipt(operations.single(),uploadOne(operations.single())))
        val responses=try {
            backend.request("/rest/v1/rpc/apply_operations",body=buildJsonObject {
                put("payloads",json.encodeToJsonElement(operations))
            },authenticated=true).jsonArray
        } catch(e:ApiException) {
            when(e.status) {
                404 -> return operations.map {receipt(it,uploadOne(it))}
                413 -> {
                    // A rejected batch is never acknowledged. Retry ordered halves, down to singles.
                    val middle=operations.size/2
                    val prefix=uploadGroup(operations.take(middle))
                    if(prefix.size!=middle || prefix.last().jsonObject["error"]!=null) return prefix
                    return prefix+uploadGroup(operations.drop(middle))
                }
                else -> throw e
            }
        }
        check(responses.size in 1..operations.size) {"Invalid mutation response."}
        responses.forEachIndexed {index,row ->
            check(row.jsonObject["id"]?.jsonPrimitive?.content==operations[index].id) {"Mutation receipt does not match operation."}
        }
        // The server can also return a successful prefix followed by an oversized operation.
        val oversized=responses.indexOfFirst {it.jsonObject["error"]?.jsonPrimitive?.content=="PT413"}
        if(oversized>=0) {
            check(oversized==responses.lastIndex) {"Invalid mutation response."}
            return responses.take(oversized)+receipt(operations[oversized],uploadOne(operations[oversized]))+
                if(oversized+1<operations.size) uploadGroup(operations.drop(oversized+1)) else emptyList()
        }
        return responses
    }
    private suspend fun uploadPacked(operations:List<Operation>):List<JsonElement> {
        val groups=mutableListOf<List<Operation>>()
        var group=mutableListOf<Operation>();var bytes=2
        for(op in operations) {
            val size=json.encodeToString(op).toByteArray(Charsets.UTF_8).size+1
            if(group.isNotEmpty() && bytes+size>batchByteBudget) {
                groups+=group;group=mutableListOf();bytes=2
            }
            group+=op;bytes+=size
        }
        if(group.isNotEmpty()) groups+=group
        val results=mutableListOf<JsonElement>()
        for(chunk in groups) {
            val responses=uploadGroup(chunk)
            results+=responses
            if(responses.size!=chunk.size || responses.last().jsonObject["error"]!=null) break
        }
        return results
    }
    /** One HTTP request per bounded FIFO prefix; a clean initialized workspace makes none. */
    suspend fun exchange(forceRefresh:Boolean=false):Boolean = exchangeBounded(forceRefresh,100)

    private suspend fun exchangeBounded(forceRefresh:Boolean,maxOperations:Int):Boolean {
        if(!forceRefresh && !store.needsExchange()) return false
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        val pause=store.uploadPause()
        if(pause!=null && !forceRefresh) throw pause
        val original=if(pause==null) store.db.getCrudBatch(maxOperations) else null
        val operations=mutableListOf<Operation>()
        var bytes=2
        for(entry in original?.crud.orEmpty()) {
            val op=try {
                check(entry.table=="operation_requests")
                val encoded=entry.opData?.jsonValues?.get("payload")?.jsonPrimitive?.content ?: error("Missing operation")
                json.decodeFromString<Operation>(encoded).also {check(it.id==entry.id)}
            } catch(e:Exception) {
                if(e is CancellationException) throw e
                if(operations.isEmpty()) {
                    val bad=store.db.getCrudBatch(1) ?: error("Queue changed")
                    check(bad.crud.single().clientId==entry.clientId)
                    store.quarantineQueuedChange(bad.crud.single());bad.complete(null)
                    return true
                }
                break
            }
            val size=json.encodeToString(op).toByteArray(Charsets.UTF_8).size+1
            if(operations.isNotEmpty() && bytes+size>batchByteBudget) break
            operations+=op;bytes+=size
        }
        val cursor=store.syncCursor()
        val response=try {backend.request("/rest/v1/rpc/sync_exchange",body=buildJsonObject {
            put("payloads",json.encodeToJsonElement(operations))
            put("after_revision",cursor.value);put("after_id",cursor.id);put("page_size",100)
        },authenticated=true).jsonObject} catch(e:ApiException) {
            // Older servers cap exchanges at 25. A rejected request has no receipts;
            // retry the unchanged FIFO prefix, never acknowledge unsent operations.
            if(e.status==413 && operations.size>25) return exchangeBounded(forceRefresh,25)
            throw e
        }
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        val receipts=response["receipts"]!!.jsonArray
        check(if(operations.isEmpty()) receipts.isEmpty() else receipts.size in 1..operations.size) {"Invalid exchange receipts."}
        receipts.forEachIndexed {index,row ->check(row.jsonObject["id"]?.jsonPrimitive?.content==operations[index].id) {"Receipt order does not match FIFO queue."}}
        val rows=response["changes"]!!.jsonArray.map {row ->val value=row.jsonObject
            ServerChange(value["id"]!!.jsonPrimitive.content,json.decodeFromJsonElement<Todo>(value["body"]!!),value["account_revision"]!!.jsonPrimitive.long)
        }
        val totals=json.decodeFromJsonElement<Totals>(response["totals"]!!)
        val more=response["has_more"]!!.jsonPrimitive.boolean
        check(!more || rows.isNotEmpty()) {"Invalid sync page."}
        val limits=response["resource_limits"]?.takeUnless {it is JsonNull}?.let {json.decodeFromJsonElement<ResourceLimits>(it)}
        var successful=0
        var failure:ApiException?=null
        for((index,row) in receipts.withIndex()) {
            val op=operations[index];val value=row.jsonObject
            when(val code=value["error"]?.jsonPrimitive?.content) {
                null -> {
                    val result=value["result"]!!.jsonObject
                    store.recordResult(op,result["accepted"]!!.jsonPrimitive.boolean,result["accountRevision"]!!.jsonPrimitive.long,result["todoRevision"]!!.jsonPrimitive.long,result["message"]?.jsonPrimitive?.content.orEmpty())
                    successful++
                }
                "PT429","PT413" -> {
                    check(index==receipts.lastIndex) {"Invalid failed suffix."}
                    failure=ApiException.fromResponse(if(code=="PT429") 429 else 413,value["message"]?.jsonPrimitive?.content ?: "Sync is paused.",value)
                    break
                }
                else -> {
                    check(code in setOf("P0001","22P02","22023")) {"Invalid receipt error."}
                    store.recordResult(op,false,cursor.value,op.baseRevision,value["message"]?.jsonPrimitive?.content.orEmpty());successful++
                }
            }
        }
        check(failure!=null || receipts.size==operations.size) {"Incomplete exchange receipts."}
        val next=if(more) rows.last().let {ListCursor(it.accountRevision,it.id)} else ListCursor(totals.revision,"ffffffff-ffff-ffff-ffff-ffffffffffff")
        store.applyServerPage(rows,next,if(more) null else totals,limits)
        if(successful>0) {
            val prefix=store.db.getCrudBatch(successful) ?: error("Queue changed")
            check(prefix.crud.map {it.clientId}==original!!.crud.take(successful).map {it.clientId})
            prefix.complete(null)
        }
        store.reconcile()
        failure?.let {if(it.admissionFailure!=null) store.pauseUploads(it);throw it}
        return operations.isNotEmpty() || rows.isNotEmpty() || totals.revision!=cursor.value || more
    }

    suspend fun synchronize(): Boolean {
        var changed=false
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        var uploadFailure:Exception?=null
        val existingPause=store.uploadPause()
        try {
            // Leave most of the 45-second pass available for canonical downloads.
            existingPause?.let {throw it}
            withTimeout(15000) {
                repeat(2) {
                    var batch=store.db.getCrudBatch(25) ?: return@withTimeout
                    val operations=mutableListOf<Operation>()
                    for(entry in batch.crud) {
                        val parsed=try {
                            check(entry.table=="operation_requests") {"Unexpected queued change."}
                            val payload=entry.opData?.jsonValues?.get("payload")?.jsonPrimitive?.content ?: error("Missing queued change.")
                            val encoded=json.parseToJsonElement(payload).jsonObject
                            val decoded=json.decodeFromJsonElement<Operation>(encoded)
                            check(decoded.id==entry.id) {"Queued change identity does not match."}
                            Triple(decoded,encoded["occurredAt"]?.jsonPrimitive?.long,encoded["todo"]?.jsonObject?.get("updatedAt")?.jsonPrimitive?.long)
                        } catch(e:Exception) {
                            if(e is CancellationException) throw e
                            break
                        }
                        val (decoded,encodedTime,updatedAt)=parsed
                        // Storage faults are retryable; only pure payload decoding can quarantine work.
                        val time=encodedTime ?: store.operationTime(decoded.id) ?: decoded.occurredAt
                        operations+=decoded.copy(occurredAt=time,todo=decoded.todo.copy(updatedAt=updatedAt ?: time))
                    }
                    if(operations.size!=batch.crud.size) {
                        // Complete only the validated prefix, or one durably preserved bad entry.
                        val prefix=store.db.getCrudBatch(maxOf(1,operations.size)) ?: error("Queued change disappeared.")
                        check(prefix.crud.map {it.clientId}==batch.crud.take(maxOf(1,operations.size)).map {it.clientId}) {"Queue changed during recovery."}
                        batch=prefix
                        if(operations.isEmpty()) {
                            check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
                            try {store.quarantineQueuedChange(batch.crud.single())}
                            catch(e:CancellationException) {throw e}
                            catch(e:Exception) {throw IllegalStateException("A saved change cannot be read. Uploads are paused and your work is retained. Export device data in Settings and contact support for recovery.",e)}
                            batch.complete(null)
                            changed=true
                            return@repeat
                        }
                    }
                    check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
                    val responses=uploadPacked(operations)
                    check(responses.size in 1..operations.size) {"Invalid mutation response."}
                    for((index,row) in responses.withIndex()) {
                        val op=operations[index];val value=row.jsonObject
                        check(value["id"]?.jsonPrimitive?.content==op.id) {"Mutation receipt does not match operation."}
                        val errorCode=value["error"]?.jsonPrimitive?.content
                        if(errorCode!=null) {
                            val message=value["message"]?.jsonPrimitive?.content ?: "Sync is paused. Local work is retained."
                            if(errorCode=="PT429") throw ApiException.fromResponse(429,message,value)
                            if(errorCode=="PT413") throw ApiException(413,message)
                            check(errorCode in setOf("P0001","22P02","22023")) {"Invalid mutation response."}
                            store.recordResult(op,false,store.syncCursor().value,op.baseRevision,value["message"]?.jsonPrimitive?.content.orEmpty())
                        } else {
                            val result=value["result"]!!.jsonObject
                            store.recordResult(op,result["accepted"]!!.jsonPrimitive.boolean,result["accountRevision"]!!.jsonPrimitive.long,
                                result["todoRevision"]!!.jsonPrimitive.long,result["message"]?.jsonPrimitive?.content.orEmpty())
                        }
                        changed=true
                    }
                    check(responses.size==operations.size) {"Sync is paused. Local work is retained."}
                    batch.complete(null)
                }
            }
        } catch(e:TimeoutCancellationException) {
            coroutineContext.ensureActive();uploadFailure=e
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            if(existingPause==null && e is ApiException && e.admissionFailure!=null) store.pauseUploads(e)
            uploadFailure=e
        }
        var downloadedPages=0
        while(true) {
            val cursor=store.syncCursor()
            check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
            val result=backend.request("/rest/v1/rpc/sync_changes",body=buildJsonObject {
                put("after_revision",cursor.value);put("after_id",cursor.id);put("page_size",100)
            },authenticated=true).jsonObject
            val rows=result["changes"]!!.jsonArray.map {row ->
                val value=row.jsonObject
                ServerChange(value["id"]!!.jsonPrimitive.content,json.decodeFromJsonElement<Todo>(value["body"]!!),value["account_revision"]!!.jsonPrimitive.long)
            }
            val totals=json.decodeFromJsonElement<Totals>(result["totals"]!!)
            changed=changed || rows.isNotEmpty() || totals.revision!=cursor.value
            val more=result["has_more"]!!.jsonPrimitive.boolean
            check(!more || rows.isNotEmpty()) {"Invalid sync page."}
            val next=if(more) rows.last().let {ListCursor(it.accountRevision,it.id)} else ListCursor(totals.revision,"ffffffff-ffff-ffff-ffff-ffffffffffff")
            val limits=result["resource_limits"]?.takeUnless {it is JsonNull}?.let {json.decodeFromJsonElement<ResourceLimits>(it)}
            store.applyServerPage(rows,next,if(more) null else totals,limits)
            downloadedPages++
            if(!more || downloadedPages>=10) break
        }
        store.reconcile()
        uploadFailure?.let {throw it}
        return changed
    }
}
