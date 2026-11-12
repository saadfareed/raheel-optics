package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import org.junit.Assert.*
import org.junit.Test

class OpticalRulesTest {
    @Test fun pakistanPhonesNormalizeToE164() {
        listOf("0300 1234567", "+92 300 1234567", "0092-300-1234567", "923001234567", "3001234567").forEach {
            assertEquals("+923001234567", PakistanPhone.normalize(it))
        }
        assertNull(PakistanPhone.normalize("02134567890"))
        assertNull(PakistanPhone.normalize("0300123"))
    }

    @Test fun cylinderRequiresAxisAndPrismRequiresBase() {
        assertThrows(IllegalArgumentException::class.java) { OpticalRules.validateEye(EyePrescription(cylinder = "-0.50")) }
        assertThrows(IllegalArgumentException::class.java) { OpticalRules.validateEye(EyePrescription(prism = "0.5")) }
        OpticalRules.validateEye(EyePrescription(sphere = "-4.00", cylinder = "-0.50", axis = 180, prism = "0.5", base = PrismBase.UP))
    }

    @Test fun readyOrderCanBeDeliveredAndClearsReminder() {
        val customer = Customer("c", "Raheel Khan", "03001234567", "+923001234567")
        val order = OpticalOrder("o", "RO-0001", customer, status = OrderStatus.READY_FOR_PICKUP, reminderAt = 100)
        val delivered = OpticalRules.transition(order, OrderStatus.COMPLETE, 200)
        assertEquals(OrderStatus.COMPLETE, delivered.status)
        assertEquals(200L, delivered.deliveredAt)
        assertNull(delivered.reminderAt)
    }

    @Test fun totalsTrackBookedCollectedAndOutstanding() {
        val amounts = OrderAmounts(framePaisa = 500_00, lensPaisa = 1_500_00, discountPaisa = 100_00)
        val customer = Customer("c", "Raheel Khan", "03001234567", "+923001234567")
        val order = OpticalOrder("o", "RO-0001", customer, amounts = amounts, payments = listOf(Payment("p", 700_00)))
        assertEquals(1_900_00, order.amounts.totalPaisa)
        assertEquals(700_00, order.paidPaisa)
        assertEquals(1_200_00, order.balancePaisa)
    }
}
