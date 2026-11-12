package com.daymark.todo.data

import com.daymark.todo.core.*
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** A normalized mobile already belongs to a different customer; callers must resolve, never silently merge. */
class PhoneCollisionException(val existingCustomerId: String, val attemptedCustomerId: String, val normalizedMobile: String) :
    IllegalStateException("This mobile number already belongs to another customer.")

/** Full-dataset read of optical state: drafts overlaid on synced rows. */
data class OpticalSnapshot(val customers: List<Customer>, val orders: List<OpticalOrder>)

internal val customerProfilesSql = """
 SELECT id,body FROM optical_drafts WHERE json_extract(body,'$.kind')='customer'
 UNION ALL SELECT id,body FROM optical_customer_cache WHERE id NOT IN
 (SELECT id FROM optical_drafts WHERE json_extract(body,'$.kind')='customer')
 UNION ALL SELECT id,json_object('id',id,'name',name,'mobile',mobile,'normalizedMobile',normalized_mobile,
 'alternateMobile',alternate_mobile,'notes',coalesce(notes,''),'createdAt',created_at,'updatedAt',updated_at,'revision',revision,
 'latestPrescription',json(latest_prescription),'previousMobiles',json(coalesce(previous_mobiles,'[]')))
 FROM optical_customers WHERE id NOT IN (SELECT id FROM optical_drafts WHERE json_extract(body,'$.kind')='customer')
 AND id NOT IN (SELECT id FROM optical_customer_cache)
""".trimIndent()

/**
 * Client write path for the optical model, parallel to [TodoStore]. Every mutation runs in ONE
 * writeTransaction guarded by [writes] + [admit], writing an insert-only operation_requests row keyed
 * by the [OpticalOperation] id (idempotency key), an optical_drafts overlay row, an optical_journal
 * row state='queued', and the synced optical_* rows the client owns. Replaying an op id is a no-op.
 *
 * Status is persisted UPPERCASE client-side (ACTIVE/READY_FOR_PICKUP/COMPLETE/CANCEL); the later sync
 * task maps it to the backend lowercase enum (active/ready_for_pickup/complete/cancel, migration 017).
 * total_paisa is server-generated (migration 016): the client only persists the component amounts and
 * the payment ledger and treats totalPaisa/paidPaisa/balancePaisa as computed, never authoritative.
 */
class OpticalStore(val db: PowerSyncDatabase, val accountId: String, private val cachePolicy: CachePolicy = CachePolicy(), private val admission: AdmissionPolicy = AdmissionPolicy(), private val availableBytes: () -> Long = { Long.MAX_VALUE },private val localOnly:Boolean=false) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val writes = Mutex()

    /**
     * Serializes a write and unwraps precondition failures. PowerSync wraps a lambda's throwable in a
     * PowerSyncException; we rethrow our own typed causes (collision, validation) so callers can branch on them
     * rather than parsing a wrapped message. Mirrors how TodoStore surfaces its guards, in catchable form.
     */
    private suspend fun <T> write(block: (com.powersync.db.internal.PowerSyncTransaction) -> T): T = writes.withLock {
        try {
            db.writeTransaction { tx -> block(tx) }
        } catch (e: Throwable) {
            throw generateSequence(e) { it.cause }.firstOrNull { it is PhoneCollisionException || it is IllegalArgumentException || it is IllegalStateException } ?: e
        }
    }

    // optical_drafts holds both customer and order overlays in one table (mirrors TodoStore's single drafts
    // table). Customer drafts key on the customer id, order drafts on the order id; a "kind" discriminator in
    // the serialized body separates them for the snapshot read.
    private val customerBody = """SELECT body FROM optical_drafts WHERE id=? AND json_extract(body,'$.kind')='customer'"""
    private val orderBody = """SELECT body FROM optical_drafts WHERE id=? AND json_extract(body,'$.kind')='order'"""

    private fun customerDraft(json: String): Customer = this.json.decodeFromString<Customer>(json)
    private fun orderDraft(json: String): OpticalOrder = this.json.decodeFromString<OpticalOrder>(json)
    private fun encodeCustomerDraft(customer: Customer): String = encodeKinded("customer", json.encodeToString(customer))
    private fun encodeOrderDraft(order: OpticalOrder): String = encodeKinded("order", json.encodeToString(order))
    private fun encodeKinded(kind: String, body: String): String =
        buildString { append("{\"kind\":\""); append(kind); append("\","); append(body.trimStart().removePrefix("{")) }

    private fun admit(tx: com.powersync.db.internal.PowerSyncTransaction, action: String) {
        check(availableBytes() >= admission.minFreeBytes) { "Device storage is low. Free device space before saving more changes. Your existing work is retained." }
        val pending = tx.get("SELECT count(*) FROM optical_journal WHERE state IN ('queued','conflict')") { it.getLong(0)!! }
        check(pending < admission.pendingOperations) { "Too many pending changes. Sync or resolve conflicts before saving more. Existing work is retained." }
        if (action == OpticalActions.ORDER_CREATE) {
            // Synced optical_orders expose a real status column; drafts carry status inside the JSON body.
            val orders = tx.get("""
                SELECT (SELECT count(*) FROM optical_orders WHERE status NOT IN ('COMPLETE','CANCEL') AND id NOT IN (SELECT id FROM optical_drafts WHERE json_extract(body,'$.kind')='order'))
                     + (SELECT count(*) FROM optical_drafts WHERE json_extract(body,'$.kind')='order' AND json_extract(body,'$.status') NOT IN ('COMPLETE','CANCEL'))
            """.trimIndent()) { it.getLong(0)!! }
            check(orders < admission.tasks) { "Active order limit reached (${admission.tasks}). Complete or cancel an order before creating another." }
        }
    }

    /** Resolves the existing customer id for a normalized mobile: drafts overlay first, then synced rows. */
    private fun existingCustomerId(tx: com.powersync.db.internal.PowerSyncTransaction, normalizedMobile: String): String? =
        tx.getOptional("SELECT id FROM ($customerProfilesSql) WHERE json_extract(body,'$.normalizedMobile')=? OR EXISTS(SELECT 1 FROM json_each(json_extract(body,'$.previousMobiles')) WHERE value=?) LIMIT 1",listOf(normalizedMobile,normalizedMobile)) {it.getString(0)!!}

    private fun currentCustomer(tx: com.powersync.db.internal.PowerSyncTransaction, id: String): Customer? =
        tx.getOptional("SELECT body FROM ($customerProfilesSql) WHERE id=?",listOf(id)) {customerDraft(it.getString(0)!!)}

    private fun currentOrder(tx: com.powersync.db.internal.PowerSyncTransaction, id: String): OpticalOrder? =
        tx.getOptional(orderBody, listOf(id)) { orderDraft(it.getString(0)!!) }
            ?: tx.getOptional("""
                SELECT customer_id,order_number,status,prescription,frame,lens,frame_paisa,lens_paisa,extras_paisa,discount_paisa,tax_paisa,ordered_at,promised_at,reminder_at,delivered_at,notes,revision,updated_at
                FROM optical_orders WHERE id=?
            """.trimIndent(), listOf(id)) { row ->
                val customer = currentCustomer(tx, row.getString(0)!!) ?: error("Order references a missing customer.")
                OpticalOrder(id, row.getString(1)!!, customer, json.decodeFromString(row.getString(3)!!), json.decodeFromString(row.getString(4)!!), json.decodeFromString(row.getString(5)!!),
                    OrderAmounts(row.getLong(6)!!, row.getLong(7)!!, row.getLong(8)!!, row.getLong(9)!!, row.getLong(10)!!),
                    syncedPayments(tx, id), OrderStatus.valueOf(row.getString(2)!!), row.getLong(11)!!, row.getLong(12), row.getLong(13), row.getLong(14), row.getString(15).orEmpty(), row.getLong(16)!!, row.getLong(17)!!)
            }

    private fun syncedPayments(tx: com.powersync.db.internal.PowerSyncTransaction, orderId: String): List<Payment> =
        tx.getAll("SELECT id,amount_paisa,method,received_at,note FROM optical_payments WHERE order_id=? ORDER BY received_at,id", listOf(orderId)) {
            Payment(it.getString(0)!!, it.getLong(1)!!, PaymentMethod.valueOf(it.getString(2)!!), it.getLong(3)!!, it.getString(4).orEmpty())
        }

    /** True when this op id was already queued; the whole operation then replays as a no-op (optical_journal is the
     *  locally-queryable idempotency ledger — operation_requests is an insert-only CRUD table, not readable back). */
    private fun alreadyApplied(tx: com.powersync.db.internal.PowerSyncTransaction, opId: String): Boolean =
        tx.getOptional("SELECT id FROM optical_journal WHERE id=?", listOf(opId)) { it.getString(0)!! } != null

    private fun writeOp(tx: com.powersync.db.internal.PowerSyncTransaction, op: OpticalOperation) {
        val payload = json.encodeToString(op)
        tx.execute("INSERT INTO optical_journal(id,payload,state,ack_revision,message,created_at) VALUES(?,?,?,0,'',?)", listOf(op.id,payload,if(localOnly) "local" else "queued",op.occurredAt))
        if(!localOnly) tx.execute("INSERT INTO operation_requests(id,payload) VALUES(?,?)", listOf(op.id, payload))
    }

    // Mirrors TodoStore's cloud path: the client writes the overlay draft only; the synced optical_customers/
    // optical_orders rows are populated by the LATER sync task from operation_requests (never written here, just as
    // TodoStore never writes server_todos). total_paisa stays server-generated; status stays UPPERCASE client-side.
    private fun writeCustomerDraft(tx: com.powersync.db.internal.PowerSyncTransaction, customer: Customer, opId: String) {
        tx.execute("INSERT OR REPLACE INTO optical_drafts(id,body,operation_id) VALUES(?,?,?)", listOf(customer.id, encodeCustomerDraft(customer), opId))
    }

    private fun writeOrderDraft(tx: com.powersync.db.internal.PowerSyncTransaction, order: OpticalOrder, opId: String) {
        tx.execute("INSERT OR REPLACE INTO optical_drafts(id,body,operation_id) VALUES(?,?,?)", listOf(order.id, encodeOrderDraft(order), opId))
    }

    private fun writeEvent(tx: com.powersync.db.internal.PowerSyncTransaction, opId: String, orderId: String, eventType: String, occurredAt: Long, payload: Map<String, Any?>) {
        tx.execute("INSERT OR IGNORE INTO optical_order_events(id,order_id,event_type,occurred_at,payload) VALUES(?,?,?,?,?)",
            listOf(opId, orderId, eventType, occurredAt, jsonObject(payload)))
    }

    private fun jsonObject(values: Map<String, Any?>): String = JsonObject(values.mapValues { (_, v) ->
        when (v) {
            null -> JsonPrimitive(null as String?)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
    }).toString()

    /**
     * Resolves identity by (account, normalizedMobile). On [upsertCustomer] (reuse=false) a number that already
     * belongs to a DIFFERENT id is a collision and throws; on [createOrder] (reuse=true) a known number silently
     * reuses the existing customer id so no duplicate customer is created.
     */
    private fun resolveCustomer(tx: com.powersync.db.internal.PowerSyncTransaction, customer: Customer, now: Long, reuse: Boolean): Customer {
        val existingId = existingCustomerId(tx, customer.normalizedMobile)
        if (existingId != null && existingId != customer.id && !reuse) throw PhoneCollisionException(existingId, customer.id, customer.normalizedMobile)
        val id = existingId ?: customer.id
        val previous = currentCustomer(tx, id)
        return if (previous == null) customer.copy(id = id, createdAt = now, updatedAt = now, revision = 1)
        else customer.copy(id = id, createdAt = previous.createdAt, updatedAt = now, revision = previous.revision + 1,
            previousMobiles=(previous.previousMobiles + previous.normalizedMobile).distinct().filter {it!=customer.normalizedMobile})
    }

    suspend fun upsertCustomer(customer: Customer): Customer {
        OpticalRules.validateCustomer(customer)
        val now = System.currentTimeMillis()
        return write { tx ->
            currentCustomer(tx,customer.id)?.let {check(it.revision==customer.revision) {"Customer changed. Open their details again."}}
            upsertCustomerInTransaction(tx,customer,now)
        }
    }

    internal fun upsertCustomerInTransaction(tx:com.powersync.db.internal.PowerSyncTransaction,customer:Customer,now:Long=System.currentTimeMillis()):Customer {
        OpticalRules.validateCustomer(customer)
        customer.latestPrescription?.let {OpticalRules.validateEye(it.distanceRight);OpticalRules.validateEye(it.distanceLeft)}
        admit(tx,OpticalActions.CUSTOMER_UPSERT)
        val previous=currentCustomer(tx,customer.id)
        check(tx.getOptional("SELECT id FROM optical_journal WHERE state='conflict' AND json_extract(payload,'$.payload.customer.id')=? LIMIT 1",listOf(customer.id)) {it.getString(0)!!}==null) {"Review this customer's cloud changes before saving."}
        if(previous!=null && previous.copy(updatedAt=customer.updatedAt,revision=customer.revision)==customer) return previous
        val resolved=resolveCustomer(tx,customer,now,reuse=false)
        val op=OpticalOperation(UUID.randomUUID().toString(),OpticalPayload.CustomerUpsert(resolved),OpticalActions.CUSTOMER_UPSERT,resolved.revision-1,previousOp(tx,resolved.id),now)
        writeOp(tx,op);writeCustomerDraft(tx,resolved,op.id)
        return resolved
    }

    suspend fun customerByMobile(normalized:String):Customer?=db.readTransaction {tx ->existingCustomerId(tx,normalized)?.let {currentCustomer(tx,it)}}

    internal fun linkOrderCustomer(tx:com.powersync.db.internal.PowerSyncTransaction,order:Todo,updateProfile:Boolean=true):Customer? {
        if(order.normalizedMobile.isBlank()) return null
        val matched=existingCustomerId(tx,order.normalizedMobile)
        val id=order.customerId ?: matched ?: UUID.nameUUIDFromBytes("$accountId:customer:${order.normalizedMobile}".toByteArray()).toString()
        val previous=currentCustomer(tx,id)
        if(previous!=null && !updateProfile) return previous
        val customer=(previous ?: Customer(id,order.title,order.customerMobile,order.normalizedMobile)).copy(
            name=order.title,mobile=order.customerMobile,normalizedMobile=order.normalizedMobile,latestPrescription=order.prescription)
        return upsertCustomerInTransaction(tx,customer)
    }

    suspend fun cacheCustomers(customers:List<Customer>) = db.writeTransaction {tx ->
        customers.forEach {customer ->
            val cached=tx.getOptional("SELECT body FROM optical_customer_cache WHERE id=?",listOf(customer.id)) {customerDraft(it.getString(0)!!)}
            if(cached==null || cached.revision<=customer.revision) tx.execute("INSERT OR REPLACE INTO optical_customer_cache(id,body) VALUES(?,?)",listOf(customer.id,json.encodeToString(customer)))
        }
    }

    suspend fun acceptCustomer(op:OpticalOperation,customer:Customer) = db.writeTransaction {tx ->
        tx.execute("INSERT OR REPLACE INTO optical_customer_cache(id,body) VALUES(?,?)",listOf(customer.id,json.encodeToString(customer)))
        tx.execute("DELETE FROM optical_drafts WHERE id=? AND operation_id=?",listOf(customer.id,op.id))
        tx.execute("UPDATE optical_journal SET state='confirmed',ack_revision=? WHERE id=?",listOf(customer.revision,op.id))
    }

    suspend fun resolveCustomerConflict(customerId:String,keepLocal:Boolean) = write {tx ->
        val local=currentCustomer(tx,customerId) ?: error("Customer no longer exists.")
        val remote=tx.getOptional("SELECT body FROM optical_customer_cache WHERE id=?",listOf(customerId)) {customerDraft(it.getString(0)!!)}
            ?: error("Refresh Customers while online to load the cloud profile first.")
        tx.execute("UPDATE optical_journal SET state='superseded' WHERE state IN ('queued','conflict') AND json_extract(payload,'$.payload.customer.id')=?",listOf(customerId))
        tx.execute("DELETE FROM optical_drafts WHERE id=? AND json_extract(body,'$.kind')='customer'",listOf(customerId))
        if(keepLocal) upsertCustomerInTransaction(tx,local.copy(revision=remote.revision,createdAt=remote.createdAt))
    }

    suspend fun retryCustomerChanges(customer:Customer) = write {tx ->
        val remote=tx.getOptional("SELECT body FROM optical_customer_cache WHERE id=?",listOf(customer.id)) {customerDraft(it.getString(0)!!)}
        tx.execute("UPDATE optical_journal SET state='superseded' WHERE state IN ('queued','conflict') AND json_extract(payload,'$.payload.customer.id')=?",listOf(customer.id))
        tx.execute("DELETE FROM optical_drafts WHERE id=? AND json_extract(body,'$.kind')='customer'",listOf(customer.id))
        upsertCustomerInTransaction(tx,customer.copy(revision=remote?.revision ?: 0,createdAt=remote?.createdAt ?: customer.createdAt,previousMobiles=remote?.previousMobiles.orEmpty()))
    }

    suspend fun createOrder(order: OpticalOrder): OpticalOrder {
        OpticalRules.validate(order)
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.ORDER_CREATE)
            // Atomically upsert the customer (reusing an existing id by normalized mobile) and create the order.
            val resolved = resolveCustomer(tx, order.customer, now, reuse = true)
            if (currentCustomer(tx, resolved.id)?.let { it.name != resolved.name || it.mobile != resolved.mobile || it.alternateMobile != resolved.alternateMobile || it.notes != resolved.notes } != false) {
                val customerOp = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.CustomerUpsert(resolved), OpticalActions.CUSTOMER_UPSERT,
                    baseRevision = resolved.revision - 1, previousOperation = previousOp(tx, resolved.id), occurredAt = now)
                writeOp(tx, customerOp)
                writeCustomerDraft(tx, resolved, customerOp.id)
            }
            val stored = order.copy(customer = resolved, status = order.status, orderedAt = now, updatedAt = now, revision = 1)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.OrderCreate(stored), OpticalActions.ORDER_CREATE,
                baseRevision = 0, previousOperation = null, occurredAt = now, beforeStatus = "absent")
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, stored.id, "created", now, mapOf("status" to stored.status.name))
            stored
        }
    }

    suspend fun editOrder(order: OpticalOrder, before: OpticalOrder): OpticalOrder {
        OpticalRules.validate(order)
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.ORDER_EDIT)
            val current = currentOrder(tx, order.id)
            check(current != null && current.revision == before.revision) { "This order has changed. Open the latest version before editing." }
            // Edits the order's own body (its own prescription snapshot); never rewrites the customer's shared record.
            val stored = order.copy(revision = before.revision + 1, updatedAt = now)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.OrderEdit(stored), OpticalActions.ORDER_EDIT,
                baseRevision = before.revision, previousOperation = previousOp(tx, order.id), occurredAt = now, beforeStatus = before.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, stored.id, "edited", now, emptyMap())
            stored
        }
    }

    suspend fun changeStatus(orderId: String, next: OrderStatus): OpticalOrder {
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.ORDER_STATUS)
            val current = currentOrder(tx, orderId) ?: error("This order no longer exists.")
            val stored = OpticalRules.transition(current, next, now).copy(revision = current.revision + 1)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.OrderStatusChange(orderId, next, stored.deliveredAt), OpticalActions.ORDER_STATUS,
                baseRevision = current.revision, previousOperation = previousOp(tx, orderId), occurredAt = now, beforeStatus = current.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, orderId, "status_changed", now, mapOf("status" to next.name))
            stored
        }
    }

    suspend fun addPayment(orderId: String, payment: Payment): OpticalOrder {
        require(payment.amountPaisa > 0) { "Payment must be greater than zero." }
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.PAYMENT_ADD)
            val current = currentOrder(tx, orderId) ?: error("This order no longer exists.")
            require(current.paidPaisa + payment.amountPaisa <= current.amounts.totalPaisa) { "Payments cannot exceed the order total." }
            // Append-only ledger: payments accumulate, never collapse.
            val stored = current.copy(payments = current.payments + payment, revision = current.revision + 1, updatedAt = now)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.PaymentAdd(orderId, payment), OpticalActions.PAYMENT_ADD,
                baseRevision = current.revision, previousOperation = previousOp(tx, orderId), occurredAt = now, beforeStatus = current.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            // Synced ledger row the client owns (amount_paisa>0, migration 016); user_id injected by the sync layer.
            tx.execute("INSERT OR IGNORE INTO optical_payments(id,order_id,amount_paisa,method,received_at,note,created_at) VALUES(?,?,?,?,?,?,?)",
                listOf(payment.id, orderId, payment.amountPaisa, payment.method.name, payment.receivedAt, payment.note, now))
            writeEvent(tx, op.id, orderId, "payment_added", now, mapOf("paymentId" to payment.id, "amountPaisa" to payment.amountPaisa))
            stored
        }
    }

    suspend fun refundPayment(orderId: String, payment: Payment): OpticalOrder {
        require(payment.amountPaisa > 0) { "Refund must be greater than zero." }
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.PAYMENT_REFUND)
            val current = currentOrder(tx, orderId) ?: error("This order no longer exists.")
            require(payment.amountPaisa <= current.paidPaisa) { "Refund cannot exceed the amount paid." }
            // Append-only ledger: a refund is a distinct negative-amount entry so paidPaisa drops and balancePaisa
            // rises; the original payment is never deleted. The backend refund row shape is finalized by the sync task
            // (optical_payments.amount_paisa>0 forbids a negative synced row here), so no synced optical_payments row.
            val refundEntry = payment.copy(amountPaisa = -payment.amountPaisa)
            val stored = current.copy(payments = current.payments + refundEntry, revision = current.revision + 1, updatedAt = now)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.PaymentRefund(orderId, payment), OpticalActions.PAYMENT_REFUND,
                baseRevision = current.revision, previousOperation = previousOp(tx, orderId), occurredAt = now, beforeStatus = current.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, orderId, "payment_refunded", now, mapOf("paymentId" to payment.id, "amountPaisa" to payment.amountPaisa))
            stored
        }
    }

    suspend fun attachImage(orderId: String, imageId: String, objectPath: String, contentType: String, byteSize: Long): OpticalOrder {
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.IMAGE_ATTACH)
            val current = currentOrder(tx, orderId) ?: error("This order no longer exists.")
            // Bookkeeping only; the upload pipeline is a later task.
            val stored = current.copy(frame = current.frame.copy(imageKey = imageId), revision = current.revision + 1, updatedAt = now)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.ImageAttach(orderId, imageId, objectPath, contentType, byteSize), OpticalActions.IMAGE_ATTACH,
                baseRevision = current.revision, previousOperation = previousOp(tx, orderId), occurredAt = now, beforeStatus = current.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, orderId, "image_added", now, mapOf("imageId" to imageId, "objectPath" to objectPath, "contentType" to contentType, "byteSize" to byteSize))
            stored
        }
    }

    suspend fun removeImage(orderId: String, imageId: String): OpticalOrder {
        val now = System.currentTimeMillis()
        return write { tx ->
            admit(tx, OpticalActions.IMAGE_REMOVE)
            val current = currentOrder(tx, orderId) ?: error("This order no longer exists.")
            val stored = current.copy(frame = if (current.frame.imageKey == imageId) current.frame.copy(imageKey = null) else current.frame, revision = current.revision + 1, updatedAt = now)
            val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.ImageRemove(orderId, imageId), OpticalActions.IMAGE_REMOVE,
                baseRevision = current.revision, previousOperation = previousOp(tx, orderId), occurredAt = now, beforeStatus = current.status.name)
            writeOp(tx, op)
            writeOrderDraft(tx, stored, op.id)
            writeEvent(tx, op.id, orderId, "image_removed", now, mapOf("imageId" to imageId))
            stored
        }
    }

    private fun previousOp(tx: com.powersync.db.internal.PowerSyncTransaction, entityId: String): String? =
        tx.getOptional("SELECT operation_id FROM optical_drafts WHERE id=?", listOf(entityId)) { it.getString(0)!! }

    /**
     * Re-drives a previously constructed operation (the replay/sync path). Idempotent on [OpticalOperation.id]:
     * an op already in the journal is a clean no-op, so replaying it writes no duplicate rows.
     */
    suspend fun apply(op: OpticalOperation) {
        write { tx ->
            if (alreadyApplied(tx, op.id)) return@write
            writeOp(tx, op)
            when (val payload = op.payload) {
                is OpticalPayload.CustomerUpsert -> writeCustomerDraft(tx, payload.customer, op.id)
                is OpticalPayload.OrderCreate -> {
                    writeOrderDraft(tx, payload.order, op.id)
                    writeEvent(tx, op.id, payload.order.id, "created", op.occurredAt, mapOf("status" to payload.order.status.name))
                }
                is OpticalPayload.OrderEdit -> {
                    writeOrderDraft(tx, payload.order, op.id)
                    writeEvent(tx, op.id, payload.order.id, "edited", op.occurredAt, emptyMap())
                }
                is OpticalPayload.OrderStatusChange -> {
                    val current = currentOrder(tx, payload.orderId) ?: error("This order no longer exists.")
                    writeOrderDraft(tx, OpticalRules.transition(current, payload.status, op.occurredAt).copy(revision = current.revision + 1), op.id)
                    writeEvent(tx, op.id, payload.orderId, "status_changed", op.occurredAt, mapOf("status" to payload.status.name))
                }
                is OpticalPayload.PaymentAdd -> {
                    val current = currentOrder(tx, payload.orderId) ?: error("This order no longer exists.")
                    writeOrderDraft(tx, current.copy(payments = current.payments + payload.payment, revision = current.revision + 1, updatedAt = op.occurredAt), op.id)
                    tx.execute("INSERT OR IGNORE INTO optical_payments(id,order_id,amount_paisa,method,received_at,note,created_at) VALUES(?,?,?,?,?,?,?)",
                        listOf(payload.payment.id, payload.orderId, payload.payment.amountPaisa, payload.payment.method.name, payload.payment.receivedAt, payload.payment.note, op.occurredAt))
                    writeEvent(tx, op.id, payload.orderId, "payment_added", op.occurredAt, mapOf("paymentId" to payload.payment.id, "amountPaisa" to payload.payment.amountPaisa))
                }
                is OpticalPayload.PaymentRefund -> {
                    val current = currentOrder(tx, payload.orderId) ?: error("This order no longer exists.")
                    writeOrderDraft(tx, current.copy(payments = current.payments + payload.payment.copy(amountPaisa = -payload.payment.amountPaisa), revision = current.revision + 1, updatedAt = op.occurredAt), op.id)
                    writeEvent(tx, op.id, payload.orderId, "payment_refunded", op.occurredAt, mapOf("paymentId" to payload.payment.id, "amountPaisa" to payload.payment.amountPaisa))
                }
                is OpticalPayload.ImageAttach -> {
                    val current = currentOrder(tx, payload.orderId) ?: error("This order no longer exists.")
                    writeOrderDraft(tx, current.copy(frame = current.frame.copy(imageKey = payload.imageId), revision = current.revision + 1, updatedAt = op.occurredAt), op.id)
                    writeEvent(tx, op.id, payload.orderId, "image_added", op.occurredAt, mapOf("imageId" to payload.imageId, "objectPath" to payload.objectPath, "contentType" to payload.contentType, "byteSize" to payload.byteSize))
                }
                is OpticalPayload.ImageRemove -> {
                    val current = currentOrder(tx, payload.orderId) ?: error("This order no longer exists.")
                    writeOrderDraft(tx, current.copy(frame = if (current.frame.imageKey == payload.imageId) current.frame.copy(imageKey = null) else current.frame, revision = current.revision + 1, updatedAt = op.occurredAt), op.id)
                    writeEvent(tx, op.id, payload.orderId, "image_removed", op.occurredAt, mapOf("imageId" to payload.imageId))
                }
            }
        }
    }

    /** Full-dataset read: drafts win over synced rows on id collision; orders expose computed balances. */
    internal suspend fun snapshot(): OpticalSnapshot = withContext(Dispatchers.Default) {
        db.readTransaction { tx ->
            // Drafts win over synced rows on id collision; synced rows reconstruct from their own columns (not a body
            // blob — the synced tables are decomposed). Optical synced tables start empty, so a queued order_create is
            // visible purely from the drafts overlay.
            val syncedCustomerIds = tx.getAll("SELECT id FROM optical_customers WHERE id NOT IN (SELECT id FROM optical_drafts WHERE json_extract(body,'$.kind')='customer')") { it.getString(0)!! }
            val draftCustomers = tx.getAll("SELECT body FROM optical_drafts WHERE json_extract(body,'$.kind')='customer'") { customerDraft(it.getString(0)!!) }
            val syncedOrderIds = tx.getAll("SELECT id FROM optical_orders WHERE id NOT IN (SELECT id FROM optical_drafts WHERE json_extract(body,'$.kind')='order')") { it.getString(0)!! }
            val draftOrders = tx.getAll("SELECT body FROM optical_drafts WHERE json_extract(body,'$.kind')='order'") { orderDraft(it.getString(0)!!) }
            val customers = tx.getAll("SELECT body FROM ($customerProfilesSql)") {customerDraft(it.getString(0)!!)}.sortedBy {it.name.lowercase()}
            val orders = (syncedOrderIds.mapNotNull { currentOrder(tx, it) } + draftOrders).sortedByDescending { it.orderedAt }
            OpticalSnapshot(customers, orders)
        }
    }

    /** Mirrors TodoStore.recordResult: records the apply receipt for an op on its journal row. */
    suspend fun recordResult(op: OpticalOperation, accepted: Boolean, ackRevision: Long, message: String) {
        db.writeTransaction { tx ->
            tx.execute("UPDATE optical_journal SET state=?,ack_revision=?,message=? WHERE id=?", listOf(if (accepted) "acked" else "conflict", ackRevision, message, op.id))
        }
    }

    /** Mirrors TodoStore.reconcile: evict acked overlays, then acked journal rows no draft still references. */
    suspend fun reconcile() {
        db.writeTransaction { tx ->
            tx.execute("DELETE FROM optical_drafts WHERE operation_id IN (SELECT id FROM optical_journal WHERE state='acked')")
            tx.execute("DELETE FROM optical_journal WHERE state='acked' AND id NOT IN (SELECT operation_id FROM optical_drafts)")
        }
    }
}
