package pk.raheeloptics.orders.core

import kotlinx.serialization.Serializable

@Serializable
data class Order(
    val id: String,
    val title: String,
    val notes: String = "",
    val priority: String = "Medium",
    val dueAt: Long? = null,
    val reminderAt: Long? = null,
    val status: String = "active",
    val revision: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    // Optical order fields. Legacy todos decode with these safe defaults during migration.
    val customerMobile: String = "",
    val normalizedMobile: String = "",
    val orderNumber: String = "",
    val prescription: Prescription = Prescription(),
    val frame: FrameDetails = FrameDetails(),
    val lens: LensDetails = LensDetails(),
    val amounts: OrderAmounts = OrderAmounts(),
    val payments: List<Payment> = emptyList(),
    val orderStatus: OrderStatus = OrderStatus.ACTIVE,
    val orderedAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null,
    val customerId: String? = null,
)

@Serializable
data class Operation(
    val id: String,
    val todo: Order,
    val action: String,
    val baseRevision: Long,
    val previousOperation: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val beforeStatus: String = "absent",
)

@Serializable
data class HistoryEntry(
    val id: String,
    val todo: Order,
    val action: String,
    val occurredAt: Long,
    val pending: Boolean = false,
)

data class PendingOperation(val operation: Operation, val state: String, val ackRevision: Long = 0, val message: String = "")
@Serializable
data class ResourceLimits(val version:Int,val taskScope:String,val maxActiveTasks:Int,val maxHistoryEvents:Long?=null,val maxReceipts:Long?=null,val historyEvents:Long?=null,val receipts:Long?=null) {
    fun validate() {require(version==1 && taskScope=="active" && maxActiveTasks in 1..1000000 &&
        (maxHistoryEvents==null || maxHistoryEvents>0) && (maxReceipts==null || maxReceipts>0) &&
        (historyEvents==null || historyEvents>=0) && (receipts==null || receipts>=0)) {"Unsupported task quota policy. Update the app or contact support."}}
}
@Serializable
data class UploadPause(val category:String,val message:String,val retryAt:Long?=null)
@Serializable
data class Totals(val active: Int = 0, val completed: Int = 0, val deleted: Int = 0, val revision: Long = 0)
data class InboxItem(val id: String, val todoId: String, val title: String, val time: Long, val read: Boolean)
data class Snapshot(
    val todos: List<Order> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val pending: List<PendingOperation> = emptyList(),
    val totals: Totals = Totals(),
    val inbox: List<InboxItem> = emptyList(),
    val currentStates: Map<String, Pair<String, Long>> = emptyMap(),
    val queuedCount: Int = 0,
    val conflictCount: Int = 0,
    val activeTaskIds: Set<String> = emptySet(),
    val recoveryCount: Int = 0,
    val orderStatusCounts: Map<OrderStatus, Int> = emptyMap(),
)

object Rules {
    const val MaxTitleLength = FieldRules.NameMax
    fun countdown(minutes: String, now: Long): Long {
        val value = minutes.toLongOrNull()
        require(value != null && value in 1..99999) { "Use a countdown from 1 to 99,999 minutes." }
        return Math.addExact(now, Math.multiplyExact(value, 60000L))
    }
    fun deadlineReminder(deadline: Long?, minutes: Int, before: Boolean): Long {
        require(deadline!=null) { "Add a deadline first." }
        require(minutes in 1..99999) { "Choose a positive reminder duration." }
        val offset=Math.multiplyExact(minutes.toLong(),60000L)
        return if(before) Math.subtractExact(deadline,offset) else Math.addExact(deadline,offset)
    }
    fun validate(todo: Order, now: Long, checkReminder: Boolean = true, checkDeadline: Boolean = false) {
        FieldRules.name(todo.title)?.let {require(false) {it}}
        FieldRules.notes(todo.notes)?.let {require(false) {it}}
        FieldRules.text(todo.frame.brand,"Frame brand",FieldRules.DetailMax)?.let {require(false) {it}}
        FieldRules.text(todo.frame.model,"Frame model",FieldRules.DetailMax)?.let {require(false) {it}}
        FieldRules.text(todo.lens.coating,"Lens coating",FieldRules.CoatingMax)?.let {require(false) {it}}
        FieldRules.pd(todo.prescription.pd.orEmpty())?.let {require(false) {it}}
        FieldRules.add(todo.prescription.addRight.orEmpty(),"ADD OD")?.let {require(false) {it}}
        FieldRules.add(todo.prescription.addLeft.orEmpty(),"ADD OS")?.let {require(false) {it}}
        require(todo.amounts.run {listOf(framePaisa,lensPaisa,extrasPaisa,discountPaisa,taxPaisa).all {it>=0}}) {"Amounts cannot be negative."}
        require(todo.amounts.run {listOf(framePaisa,lensPaisa,extrasPaisa,discountPaisa,taxPaisa).all {it<=FieldRules.MaxAmountPaisa}}) {"An amount is too large."}
        FieldRules.discount(todo.amounts.discountPaisa,todo.amounts.run {framePaisa+lensPaisa+extrasPaisa+taxPaisa})?.let {require(false) {it}}
        require(todo.payments.sumOf {it.amountPaisa} in 0..todo.amounts.totalPaisa) {"Payments must be between zero and the order total."}
        require(todo.priority in listOf("Low", "Medium", "High")) { "Choose a valid priority." }
        if (checkDeadline) require(todo.dueAt == null || todo.dueAt > now) { "Choose a deadline in the future." }
        if (checkReminder) require(todo.reminderAt == null || todo.reminderAt > now) { "Choose a future reminder time." }
        if (todo.customerMobile.isNotBlank()) require(PakistanPhone.normalize(todo.customerMobile)==todo.normalizedMobile) { "Enter a valid Pakistani mobile number." }
        validateEye(todo.prescription.distanceRight)
        validateEye(todo.prescription.distanceLeft)
    }

    private fun validateEye(eye:EyePrescription)=OpticalRules.validateEye(eye)

    fun transition(todo: Order, action: String, now: Long): Order = when (action) {
        "complete" -> { require(todo.status == "active"); todo.copy(status = "completed", completedAt = now, orderStatus = OrderStatus.COMPLETE, deliveredAt = now, reminderAt = null, updatedAt = now) }
        "delete" -> { require(todo.status != "deleted"); todo.copy(status = "deleted", orderStatus = OrderStatus.CANCEL, reminderAt = null, updatedAt = now) }
        "reopen" -> { require(todo.status == "completed"); todo.copy(status = "active", orderStatus = OrderStatus.ACTIVE, completedAt = null, deliveredAt = null, reminderAt = null, updatedAt = now) }
        else -> error("Unsupported transition")
    }

    fun overlayTotals(base: Totals, pending: List<PendingOperation>): Totals {
        var active = base.active; var completed = base.completed; var deleted = base.deleted
        for (p in pending.filter { it.state != "conflict" && (it.state != "acked" || it.ackRevision > base.revision) }) {
            val old = p.operation.beforeStatus; val new = p.operation.todo.status
            if (old == new) continue
            if (old == "active") active-- else if (old == "completed") completed-- else if (old == "deleted") deleted--
            if (new == "active") active++ else if (new == "completed") completed++ else if (new == "deleted") deleted++
        }
        return base.copy(active = active.coerceAtLeast(0), completed = completed.coerceAtLeast(0), deleted = deleted.coerceAtLeast(0))
    }
}

enum class AlertKind { Reminder, Deadline }
data class TaskAlert(val todo:Order,val kind:AlertKind,val time:Long) {
    val key:String get()="${kind.name.lowercase()}:${todo.id}"
    fun matches(current:Order):Boolean=current.status=="active" && time==if(kind==AlertKind.Deadline) current.dueAt else current.reminderAt
}

object ReminderPolicy {
    const val MaxScheduled = 64
    fun future(todos: List<Order>, now: Long): List<Order> = todos.filter { it.status=="active" && it.reminderAt!=null && it.reminderAt>now }
    fun due(todos: List<Order>, now: Long): List<Order> = todos.filter { it.status=="active" && it.reminderAt!=null && it.reminderAt<=now }
    fun matches(todo: Order, expectedTime: Long): Boolean = todo.status=="active" && todo.reminderAt==expectedTime
}

object WorkspaceIdentity {
    fun project(supabase: String, powerSync: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest((supabase.trimEnd('/') + "\n" + powerSync.trimEnd('/')).toByteArray())
        .joinToString("") { "%02x".format(it) }
}
