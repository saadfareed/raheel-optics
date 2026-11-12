package pk.raheeloptics.orders.sync

import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.data.TodoStore
import pk.raheeloptics.orders.data.OpticalStore
import pk.raheeloptics.orders.identity.BackendTransport
import pk.raheeloptics.orders.identity.ApiException
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*

/**
 * Cloud sync for orders and customers. The SDK's SQLite queue keeps every local change durable; uploads go one
 * queued change at a time (customers through upsert_optical_customer, orders through apply_optical_order), then
 * orders are downloaded from optical_order_feed. Nothing here reads or writes the legacy todos tables.
 */
class SupabaseSynchronizer(private val store: TodoStore, private val backend: BackendTransport) {
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    private var apiChecked=false
    private val rejectedCodes=setOf(400,404,409,413,422)

    private suspend fun requireOrderApi() {
        if(apiChecked) return
        val version=try {
            backend.request("/rest/v1/rpc/${OrderWire.API_VERSION}",body=buildJsonObject {},authenticated=true).jsonPrimitive.intOrNull
        } catch(e:ApiException) {
            if(e.status !in setOf(400,404)) throw e
            null
        }
        check((version ?: 0)>=OrderWire.REQUIRED_VERSION) {"Cloud order sync needs the latest server update. Orders remain saved on this device."}
        check(backend.accountId()==store.accountId) {"Account changed. Local work is retained."}
        apiChecked=true
    }

    private fun queuedValue(entry:com.powersync.db.crud.CrudEntry):JsonObject? {
        if(entry.table!="operation_requests") return null
        val encoded=entry.opData?.jsonValues?.get("payload")?.jsonPrimitive?.content ?: return null
        return runCatching {json.parseToJsonElement(encoded).jsonObject}.getOrNull()
    }

    private suspend fun completeHead(entry:com.powersync.db.crud.CrudEntry) {
        val current=store.db.getCrudBatch(1) ?: error("Queue changed")
        check(current.crud.single().clientId==entry.clientId) {"Queue changed"}
        current.complete(null)
    }

    /** Customer profile changes (queued before the orders that use them). */
    private suspend fun uploadCustomer(entry:com.powersync.db.crud.CrudEntry,value:JsonObject) {
        val op=json.decodeFromJsonElement<OpticalOperation>(value)
        check(op.id==entry.id && op.payload is OpticalPayload.CustomerUpsert) {"Queued customer change does not match."}
        val profiles=OpticalStore(store.db,store.accountId)
        val superseded=store.db.getOptional("SELECT state FROM optical_journal WHERE id=?",listOf(op.id)) {it.getString(0)!!}=="superseded"
        if(!superseded) {
            val result=backend.request("/rest/v1/rpc/upsert_optical_customer",body=buildJsonObject {put("payload",value)},authenticated=true).jsonObject
            check(backend.accountId()==store.accountId) {"Account changed. Local work is retained."}
            if(result["accepted"]!!.jsonPrimitive.boolean) {
                val customer=json.decodeFromJsonElement<Customer>(result["customer"]!!)
                check(customer.id==(op.payload as OpticalPayload.CustomerUpsert).customer.id)
                profiles.acceptCustomer(op,customer)
            } else profiles.recordResult(op,false,op.baseRevision,result["message"]?.jsonPrimitive?.content ?: "Customer update needs review.")
        }
        completeHead(entry)
    }

    /** One order change: the queued record carries the full order, so it is a single apply_optical_order call. */
    private suspend fun uploadOrder(entry:com.powersync.db.crud.CrudEntry,value:JsonObject) {
        val op=json.decodeFromJsonElement<Operation>(value)
        check(op.id==entry.id) {"Queued change identity does not match."}
        val timed=op.copy(occurredAt=value["occurredAt"]?.jsonPrimitive?.longOrNull ?: store.operationTime(op.id) ?: op.occurredAt)
        val result=try {
            backend.request("/rest/v1/rpc/${OrderWire.APPLY}",body=buildJsonObject {put("payload",OrderWire.applyPayload(timed))},authenticated=true).jsonObject
        } catch(e:ApiException) {
            // Invalid data can never succeed: keep it for review as a conflict so later changes still upload.
            if(e.status !in rejectedCodes || e.admissionFailure!=null) throw e
            check(backend.accountId()==store.accountId) {"Account changed. Local work is retained."}
            store.recordResult(op,false,store.syncCursor().value,op.baseRevision,e.message ?: "This change could not be saved in the cloud. Review the order.")
            completeHead(entry)
            return
        }
        check(backend.accountId()==store.accountId) {"Account changed. Local work is retained."}
        val order=result["order"]?.takeUnless {it is JsonNull}?.jsonObject
        if(result["accepted"]?.jsonPrimitive?.boolean==true) {
            store.recordResult(op,true,order!!["updatedAt"]!!.jsonPrimitive.long,order["revision"]!!.jsonPrimitive.long,"")
        } else {
            store.recordResult(op,false,store.syncCursor().value,op.baseRevision,result["message"]?.jsonPrimitive?.content ?: "This order changed on another device.")
        }
        completeHead(entry)
    }

    /** Uploads the queue head; false when the queue is empty. */
    private suspend fun uploadHead():Boolean {
        val batch=store.db.getCrudBatch(1) ?: return false
        val entry=batch.crud.single()
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        val value=queuedValue(entry)
        try {
            when {
                value==null -> error("Unreadable queued change.")
                value["action"]?.jsonPrimitive?.contentOrNull==OpticalActions.CUSTOMER_UPSERT -> uploadCustomer(entry,value)
                value["todo"]!=null -> {requireOrderApi();uploadOrder(entry,value)}
                else -> error("Unsupported queued change.")
            }
        } catch(e:Exception) {
            if(e is CancellationException || e is ApiException) throw e
            if(e.message?.startsWith("Cloud order sync needs")==true || e.message?.startsWith("Account changed")==true) throw e
            // A change this app version cannot read is preserved for recovery and never blocks the queue.
            try {store.quarantineQueuedChange(entry)}
            catch(q:CancellationException) {throw q}
            catch(q:Exception) {throw IllegalStateException("A saved change cannot be read. Uploads are paused and your work is retained. Export device data in Settings and contact support for recovery.",q)}
            completeHead(entry)
        }
        return true
    }

    /** Downloads every order changed since the stored cursor (bounded per pass). */
    private suspend fun download():Boolean {
        requireOrderApi()
        var changed=false
        repeat(10) {
            val cursor=store.syncCursor()
            check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
            val page=backend.request("/rest/v1/rpc/${OrderWire.FEED}",body=buildJsonObject {
                put("after_updated",cursor.value);put("after_id",cursor.id);put("page_size",100)
            },authenticated=true).jsonObject
            val orders=page["rows"]!!.jsonArray.map {it.jsonObject}
            val photos=store.localPhotos(orders.map {it["id"]!!.jsonPrimitive.content})
            val rows=orders.map {o ->
                val order=OrderWire.fromServer(o,photos[o["id"]!!.jsonPrimitive.content])
                ServerChange(order.id,order,o["updatedAt"]!!.jsonPrimitive.long)
            }
            val totals=page["totals"]!!.jsonObject.let {
                Totals(it["active"]!!.jsonPrimitive.int,it["completed"]!!.jsonPrimitive.int,it["deleted"]!!.jsonPrimitive.int,it["revision"]!!.jsonPrimitive.long)
            }
            val more=page["has_more"]!!.jsonPrimitive.boolean
            check(!more || rows.isNotEmpty()) {"Invalid sync page."}
            val next=rows.lastOrNull()?.let {ListCursor(it.accountRevision,it.id)} ?: cursor
            store.applyServerPage(rows,next,if(more) null else totals)
            changed=changed || rows.isNotEmpty()
            if(!more) return changed
        }
        return changed
    }

    /** One sync pass: uploads (time-boxed so downloads always run), then downloads. Returns true if anything changed. */
    suspend fun exchange(forceRefresh:Boolean=false):Boolean {
        if(!forceRefresh && !store.needsExchange()) return false
        check(backend.accountId()==store.accountId) {"Account changed. Sign in again."}
        var changed=false
        var uploadFailure:Exception?=null
        val pause=store.uploadPause()
        try {
            pause?.let {throw it}
            withTimeout(15000) {
                repeat(200) {if(uploadHead()) changed=true else return@withTimeout}
            }
        } catch(e:TimeoutCancellationException) {
            coroutineContext.ensureActive();uploadFailure=e
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {
            if(pause==null && e is ApiException && e.admissionFailure!=null) store.pauseUploads(e)
            uploadFailure=e
        }
        changed=download() || changed
        store.reconcile()
        uploadFailure?.let {if(it !is TimeoutCancellationException) throw it}
        return changed
    }
}
