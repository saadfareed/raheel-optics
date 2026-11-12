package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OpticalOperationTest {
    private val json = Json
    private val customer = Customer("c", "Raheel Khan", "03001234567", "+923001234567", alternateMobile = "+923001112222", notes = "VIP", createdAt = 10, updatedAt = 20, revision = 2)
    private val payment = Payment("p", 700_00, PaymentMethod.CARD, 123, "advance")
    private val order = OpticalOrder("o", "RO-0001", customer, amounts = OrderAmounts(framePaisa = 500_00, lensPaisa = 1_500_00, discountPaisa = 100_00), payments = listOf(payment), status = OrderStatus.READY_FOR_PICKUP, orderedAt = 30, promisedAt = 50, reminderAt = 60, notes = "rush", revision = 4, updatedAt = 40)

    private fun op(payload: OpticalPayload, action: String) = OpticalOperation("op-$action", payload, action, baseRevision = 3, occurredAt = 777)

    @Test fun customerUpsertRoundTrips() {
        val original = op(OpticalPayload.CustomerUpsert(customer), OpticalActions.CUSTOMER_UPSERT)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.CUSTOMER_UPSERT, decoded.action)
        val payload = decoded.payload as OpticalPayload.CustomerUpsert
        assertEquals("+923001234567", payload.customer.normalizedMobile)
        assertEquals("VIP", payload.customer.notes)
    }

    @Test fun orderCreateRoundTrips() {
        val original = op(OpticalPayload.OrderCreate(order), OpticalActions.ORDER_CREATE)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.ORDER_CREATE, decoded.action)
        val payload = decoded.payload as OpticalPayload.OrderCreate
        assertEquals(1_900_00, payload.order.amounts.totalPaisa)
        assertEquals(OrderStatus.READY_FOR_PICKUP, payload.order.status)
        assertEquals(1, payload.order.payments.size)
    }

    @Test fun orderEditRoundTrips() {
        val original = op(OpticalPayload.OrderEdit(order.copy(notes = "edited")), OpticalActions.ORDER_EDIT)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.ORDER_EDIT, decoded.action)
        assertEquals("edited", (decoded.payload as OpticalPayload.OrderEdit).order.notes)
    }

    @Test fun orderStatusRoundTrips() {
        val original = op(OpticalPayload.OrderStatusChange("o", OrderStatus.READY_FOR_PICKUP, deliveredAt = null), OpticalActions.ORDER_STATUS).copy(previousOperation = "op-prev", beforeStatus = "ACTIVE")
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.ORDER_STATUS, decoded.action)
        assertEquals("op-prev", decoded.previousOperation)
        assertEquals("ACTIVE", decoded.beforeStatus)
        val payload = decoded.payload as OpticalPayload.OrderStatusChange
        assertEquals("o", payload.orderId)
        assertEquals(OrderStatus.READY_FOR_PICKUP, payload.status)
    }

    @Test fun paymentAddRoundTrips() {
        val original = op(OpticalPayload.PaymentAdd("o", payment), OpticalActions.PAYMENT_ADD)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.PAYMENT_ADD, decoded.action)
        val payload = decoded.payload as OpticalPayload.PaymentAdd
        assertEquals("o", payload.orderId)
        assertEquals(700_00, payload.payment.amountPaisa)
        assertEquals(PaymentMethod.CARD, payload.payment.method)
    }

    @Test fun paymentRefundRoundTrips() {
        val original = op(OpticalPayload.PaymentRefund("o", payment), OpticalActions.PAYMENT_REFUND)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.PAYMENT_REFUND, decoded.action)
        val payload = decoded.payload as OpticalPayload.PaymentRefund
        assertEquals("o", payload.orderId)
        assertEquals("p", payload.payment.id)
    }

    @Test fun imageAttachRoundTrips() {
        val original = op(OpticalPayload.ImageAttach("o", "img", "uid/o/img.jpg", "image/jpeg", 2048), OpticalActions.IMAGE_ATTACH)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.IMAGE_ATTACH, decoded.action)
        val payload = decoded.payload as OpticalPayload.ImageAttach
        assertEquals("uid/o/img.jpg", payload.objectPath)
        assertEquals("image/jpeg", payload.contentType)
        assertEquals(2048L, payload.byteSize)
    }

    @Test fun imageRemoveRoundTrips() {
        val original = op(OpticalPayload.ImageRemove("o", "img"), OpticalActions.IMAGE_REMOVE)
        val decoded = json.decodeFromString<OpticalOperation>(json.encodeToString(original))
        assertEquals(original, decoded)
        assertEquals(OpticalActions.IMAGE_REMOVE, decoded.action)
        val payload = decoded.payload as OpticalPayload.ImageRemove
        assertEquals("o", payload.orderId)
        assertEquals("img", payload.imageId)
    }
}
