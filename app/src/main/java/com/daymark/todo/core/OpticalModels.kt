package com.daymark.todo.core

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
enum class OrderStatus(val label: String) {
    @kotlinx.serialization.json.JsonNames("NEW", "IN_PRODUCTION") ACTIVE("Active"),
    @kotlinx.serialization.json.JsonNames("READY") READY_FOR_PICKUP("Ready for pickup"),
    @kotlinx.serialization.json.JsonNames("DELIVERED") COMPLETE("Complete"),
    @kotlinx.serialization.json.JsonNames("CANCELLED") CANCEL("Cancel");

    val isTerminal: Boolean get() = this == COMPLETE || this == CANCEL
    val canMarkReady: Boolean get() = this == ACTIVE
}

@Serializable
enum class PaymentMethod { CASH, CARD, BANK_TRANSFER, JAZZCASH, EASYPAISA, OTHER }

@Serializable
enum class LensType { SINGLE_VISION, BIFOCAL, PROGRESSIVE, READING, PLANO, OTHER }

@Serializable
enum class PrismBase { UP, DOWN, IN, OUT }

@Serializable
data class EyePrescription(
    val sphere: String = "",
    val cylinder: String? = null,
    val axis: Int? = null,
    val prism: String? = null,
    val base: PrismBase? = null,
)

@Serializable
data class Prescription(
    val distanceRight: EyePrescription = EyePrescription(),
    val distanceLeft: EyePrescription = EyePrescription(),
    val nearRight: EyePrescription? = null,
    val nearLeft: EyePrescription? = null,
    val addRight: String? = null,
    val addLeft: String? = null,
    val pd: String? = null,
    val pdRight: String? = null,
    val pdLeft: String? = null,
    val prescribedAt: Long? = null,
    val prescribedBy: String = "",
)

@Serializable
data class Customer(
    val id: String,
    val name: String,
    val mobile: String,
    val normalizedMobile: String,
    val alternateMobile: String? = null,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val revision: Long = 0,
    val latestPrescription: Prescription? = null,
    val previousMobiles: List<String> = emptyList(),
)

@Serializable
data class FrameDetails(
    val imageKey: String? = null,
    val localImageUri: String? = null,
    val brand: String = "",
    val model: String = "",
    val color: String = "",
    val size: String = "",
)

@Serializable
data class LensDetails(
    val type: LensType = LensType.SINGLE_VISION,
    val material: String = "",
    val coating: String = "",
    val tint: String = "",
    val laboratory: String = "",
)

@Serializable
data class OrderAmounts(
    val framePaisa: Long = 0,
    val lensPaisa: Long = 0,
    val extrasPaisa: Long = 0,
    val discountPaisa: Long = 0,
    val taxPaisa: Long = 0,
) {
    val totalPaisa: Long
        get() = (framePaisa + lensPaisa + extrasPaisa - discountPaisa + taxPaisa).coerceAtLeast(0)
}

@Serializable
data class Payment(
    val id: String,
    val amountPaisa: Long,
    val method: PaymentMethod = PaymentMethod.CASH,
    val receivedAt: Long = System.currentTimeMillis(),
    val note: String = "",
)

@Serializable
data class OpticalOrder(
    val id: String,
    val orderNumber: String,
    val customer: Customer,
    val prescription: Prescription = Prescription(),
    val frame: FrameDetails = FrameDetails(),
    val lens: LensDetails = LensDetails(),
    val amounts: OrderAmounts = OrderAmounts(),
    val payments: List<Payment> = emptyList(),
    val status: OrderStatus = OrderStatus.ACTIVE,
    val orderedAt: Long = System.currentTimeMillis(),
    val promisedAt: Long? = null,
    val reminderAt: Long? = null,
    val deliveredAt: Long? = null,
    val notes: String = "",
    val revision: Long = 0,
    val updatedAt: Long = orderedAt,
) {
    val paidPaisa: Long get() = payments.sumOf(Payment::amountPaisa)
    val balancePaisa: Long get() = (amounts.totalPaisa - paidPaisa).coerceAtLeast(0)
}

object PakistanPhone {
    private val separators = Regex("[\\s()\\-]")

    /** Returns E.164 (+92...) for Pakistani mobile numbers, or null when invalid. */
    fun normalize(value: String): String? {
        val compact = value.trim().replace(separators, "")
        val national = when {
            compact.startsWith("+92") -> compact.drop(3)
            compact.startsWith("0092") -> compact.drop(4)
            compact.startsWith("92") -> compact.drop(2)
            compact.startsWith("0") -> compact.drop(1)
            else -> compact
        }
        return national.takeIf { it.matches(Regex("3\\d{9}")) }?.let { "+92$it" }
    }

    fun display(normalized: String): String =
        normalized.takeIf { it.matches(Regex("\\+923\\d{9}")) }
            ?.let { "0${it.drop(3).take(3)} ${it.drop(6).take(7)}" } ?: normalized
}

object OpticalRules {
    fun validateCustomer(customer: Customer) {
        require(customer.name.trim().length in 2..120) { "Enter the customer's full name." }
        require(PakistanPhone.normalize(customer.mobile) == customer.normalizedMobile) { "Enter a valid Pakistani mobile number." }
    }

    fun validateEye(eye: EyePrescription) {
        decimal(eye.sphere, "SPH", allowBlank = true)?.let { require(it in BigDecimal("-30.00")..BigDecimal("30.00")) { "SPH must be between -30.00 and +30.00." } }
        val cylinder = eye.cylinder?.let { decimal(it, "CYL") }
        cylinder?.let { require(it in BigDecimal("-12.00")..BigDecimal("12.00")) { "CYL must be between -12.00 and +12.00." } }
        require((eye.axis == null) == (cylinder == null)) { "AXIS is required when CYL is added." }
        eye.axis?.let { require(it in 1..180) { "AXIS must be between 1 and 180." } }
        val prism = eye.prism?.let { decimal(it, "PRISM") }
        prism?.let { require(it >= BigDecimal.ZERO) { "PRISM cannot be negative." } }
        require((eye.base == null) == (prism == null)) { "BASE is required when PRISM is added." }
    }

    fun validate(order: OpticalOrder) {
        validateCustomer(order.customer)
        validateEye(order.prescription.distanceRight)
        validateEye(order.prescription.distanceLeft)
        order.prescription.nearRight?.let(::validateEye)
        order.prescription.nearLeft?.let(::validateEye)
        require(order.orderNumber.isNotBlank()) { "Order number is required." }
        require(order.amounts.run { listOf(framePaisa, lensPaisa, extrasPaisa, discountPaisa, taxPaisa).all { it >= 0 } }) { "Amounts cannot be negative." }
        require(order.payments.all { it.amountPaisa > 0 }) { "Payment must be greater than zero." }
        require(order.paidPaisa <= order.amounts.totalPaisa) { "Payments cannot exceed the order total." }
    }

    fun transition(order: OpticalOrder, next: OrderStatus, now: Long = System.currentTimeMillis()): OpticalOrder {
        val allowed = when (order.status) {
            OrderStatus.ACTIVE -> setOf(OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETE, OrderStatus.CANCEL)
            OrderStatus.READY_FOR_PICKUP -> setOf(OrderStatus.COMPLETE, OrderStatus.ACTIVE, OrderStatus.CANCEL)
            OrderStatus.COMPLETE -> emptySet()
            OrderStatus.CANCEL -> emptySet()
        }
        require(next in allowed) { "${order.status} cannot move to $next." }
        return order.copy(status = next, deliveredAt = now.takeIf { next == OrderStatus.COMPLETE }, reminderAt = order.reminderAt.takeUnless { next in setOf(OrderStatus.COMPLETE, OrderStatus.CANCEL) }, updatedAt = now)
    }

    private fun decimal(value: String, label: String, allowBlank: Boolean = false): BigDecimal? {
        if (allowBlank && value.isBlank()) return null
        if (value.equals("plano", true) || value.equals("pl", true)) return BigDecimal.ZERO
        return value.trim().removePrefix("+").toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
            ?: throw IllegalArgumentException("Enter a valid $label value.")
    }
}
