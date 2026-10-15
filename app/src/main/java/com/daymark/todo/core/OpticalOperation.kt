package com.daymark.todo.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OpticalOperation(
    val id: String,
    val payload: OpticalPayload,
    val action: String,
    val baseRevision: Long,
    val previousOperation: String? = null,
    val occurredAt: Long = System.currentTimeMillis(),
    val beforeStatus: String = "absent",
)

@Serializable
sealed interface OpticalPayload {
    @Serializable @SerialName("customer_upsert")
    data class CustomerUpsert(val customer: Customer) : OpticalPayload

    @Serializable @SerialName("order_create")
    data class OrderCreate(val order: OpticalOrder) : OpticalPayload

    @Serializable @SerialName("order_edit")
    data class OrderEdit(val order: OpticalOrder) : OpticalPayload

    @Serializable @SerialName("order_status")
    data class OrderStatusChange(val orderId: String, val status: OrderStatus, val deliveredAt: Long? = null) : OpticalPayload

    @Serializable @SerialName("payment_add")
    data class PaymentAdd(val orderId: String, val payment: Payment) : OpticalPayload

    @Serializable @SerialName("payment_refund")
    data class PaymentRefund(val orderId: String, val payment: Payment) : OpticalPayload

    @Serializable @SerialName("image_attach")
    data class ImageAttach(val orderId: String, val imageId: String, val objectPath: String, val contentType: String, val byteSize: Long) : OpticalPayload

    @Serializable @SerialName("image_remove")
    data class ImageRemove(val orderId: String, val imageId: String) : OpticalPayload
}

/** Canonical action names; kept in lockstep with the OpticalPayload @SerialName tags. */
object OpticalActions {
    const val CUSTOMER_UPSERT = "customer_upsert"
    const val ORDER_CREATE = "order_create"
    const val ORDER_EDIT = "order_edit"
    const val ORDER_STATUS = "order_status"
    const val PAYMENT_ADD = "payment_add"
    const val PAYMENT_REFUND = "payment_refund"
    const val IMAGE_ATTACH = "image_attach"
    const val IMAGE_REMOVE = "image_remove"
}
