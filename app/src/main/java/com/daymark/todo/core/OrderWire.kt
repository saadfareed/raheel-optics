package com.daymark.todo.core

import kotlinx.serialization.json.*

/**
 * Wire format between the app's local order record and the server's optical order API (docs/OPTICAL-API.md).
 * Uploads: every queued change carries the full order, so it maps to one apply_optical_order call.
 * Downloads: optical_order_feed rows map back to the local record.
 */
object OrderWire {
    const val APPLY = "apply_optical_order"
    const val FEED = "optical_order_feed"
    const val API_VERSION = "optical_orders_api_version"
    /** The feed arrived in API version 3 (migration 022). */
    const val REQUIRED_VERSION = 3

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun serverStatus(order: Todo): OrderStatus = when (order.status) {
        "completed" -> OrderStatus.COMPLETE
        "deleted" -> OrderStatus.CANCEL
        else -> order.orderStatus
    }

    /** apply_optical_order payload. A create is only a create when nothing was confirmed before. */
    fun applyPayload(op: Operation): JsonObject {
        val o = op.todo
        return buildJsonObject {
            put("id", op.id)
            put("action", if (op.action == "create" && op.baseRevision == 0L) "order_create" else "order_edit")
            put("baseRevision", op.baseRevision)
            put("occurredAt", op.occurredAt)
            put("order", buildJsonObject {
                put("id", o.id)
                put("customerId", o.customerId)
                put("orderNumber", o.orderNumber)
                put("status", serverStatus(o).name)
                put("orderedAt", o.orderedAt)
                put("promisedAt", o.dueAt)
                put("reminderAt", if (serverStatus(o).isTerminal) null else o.reminderAt)
                put("deliveredAt", o.deliveredAt ?: o.completedAt)
                put("notes", o.notes)
                put("prescription", json.encodeToJsonElement(o.prescription))
                // The photo stays on the device that took it; its local path means nothing elsewhere.
                put("frame", JsonObject(json.encodeToJsonElement(o.frame).jsonObject - "localImageUri"))
                put("lens", json.encodeToJsonElement(o.lens))
                put("amounts", buildJsonObject {
                    put("framePaisa", o.amounts.framePaisa); put("lensPaisa", o.amounts.lensPaisa); put("extrasPaisa", o.amounts.extrasPaisa)
                    put("discountPaisa", o.amounts.discountPaisa); put("taxPaisa", o.amounts.taxPaisa)
                })
                put("payments", JsonArray(o.payments.map { p ->
                    buildJsonObject { put("id", p.id); put("amountPaisa", p.amountPaisa); put("method", p.method.name); put("receivedAt", p.receivedAt); put("note", p.note) }
                }))
            })
        }
    }

    /** A server order (feed row or apply result) as the local record. [localPhoto] keeps this device's photo path. */
    fun fromServer(order: JsonObject, localPhoto: String? = null): Todo {
        fun long(key: String) = order[key]?.jsonPrimitive?.longOrNull
        val status = OrderStatus.valueOf(order["status"]!!.jsonPrimitive.content)
        val customer = order["customer"]?.jsonObject
        val amounts = order["amounts"]?.jsonObject
        fun amount(key: String) = amounts?.get(key)?.jsonPrimitive?.longOrNull ?: 0
        val frame = order["frame"]?.let { runCatching { json.decodeFromJsonElement<FrameDetails>(it) }.getOrNull() } ?: FrameDetails()
        return Todo(
            id = order["id"]!!.jsonPrimitive.content,
            title = customer?.get("name")?.jsonPrimitive?.content.orEmpty(),
            notes = order["notes"]?.jsonPrimitive?.content.orEmpty(),
            dueAt = long("promisedAt"),
            reminderAt = long("reminderAt"),
            status = when (status) { OrderStatus.COMPLETE -> "completed"; OrderStatus.CANCEL -> "deleted"; else -> "active" },
            revision = long("revision") ?: 0,
            updatedAt = long("updatedAt") ?: 0,
            completedAt = long("deliveredAt").takeIf { status == OrderStatus.COMPLETE },
            customerMobile = customer?.get("mobile")?.jsonPrimitive?.content.orEmpty(),
            normalizedMobile = customer?.get("normalizedMobile")?.jsonPrimitive?.content.orEmpty(),
            orderNumber = order["orderNumber"]!!.jsonPrimitive.content,
            prescription = order["prescription"]?.let { runCatching { json.decodeFromJsonElement<Prescription>(it) }.getOrNull() } ?: Prescription(),
            frame = frame.copy(localImageUri = localPhoto),
            lens = order["lens"]?.let { runCatching { json.decodeFromJsonElement<LensDetails>(it) }.getOrNull() } ?: LensDetails(),
            amounts = OrderAmounts(amount("framePaisa"), amount("lensPaisa"), amount("extrasPaisa"), amount("discountPaisa"), amount("taxPaisa")),
            payments = order["payments"]?.jsonArray?.map { p ->
                val e = p.jsonObject
                Payment(e["id"]!!.jsonPrimitive.content, e["amountPaisa"]!!.jsonPrimitive.long,
                    runCatching { PaymentMethod.valueOf(e["method"]!!.jsonPrimitive.content) }.getOrDefault(PaymentMethod.CASH),
                    e["receivedAt"]?.jsonPrimitive?.longOrNull ?: 0, e["note"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }.orEmpty(),
            orderStatus = status,
            orderedAt = long("orderedAt") ?: 0,
            deliveredAt = long("deliveredAt"),
            customerId = (customer?.get("id") ?: order["customerId"])?.jsonPrimitive?.contentOrNull,
        )
    }
}
