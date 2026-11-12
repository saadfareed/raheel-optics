package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import org.junit.Assert.*
import org.junit.Test

class OpticalTransitionTest {
    private val customer = Customer("c", "Raheel Khan", "03001234567", "+923001234567")

    private fun orderAt(status: OrderStatus) = OpticalOrder("o", "RO-0001", customer, status = status, reminderAt = 100, deliveredAt = if (status == OrderStatus.COMPLETE) 90 else null)

    private val allowed = mapOf(
        OrderStatus.ACTIVE to setOf(OrderStatus.READY_FOR_PICKUP, OrderStatus.COMPLETE, OrderStatus.CANCEL),
        OrderStatus.READY_FOR_PICKUP to setOf(OrderStatus.COMPLETE, OrderStatus.ACTIVE, OrderStatus.CANCEL),
        OrderStatus.COMPLETE to emptySet(),
        OrderStatus.CANCEL to emptySet(),
    )

    @Test fun everyAllowedTransitionSucceedsWithSideEffects() {
        for (from in OrderStatus.entries) for (to in allowed.getValue(from)) {
            val result = OpticalRules.transition(orderAt(from), to, 200)
            assertEquals("$from->$to", to, result.status)
            assertEquals(200L, result.updatedAt)
            if (to == OrderStatus.COMPLETE) assertEquals(200L, result.deliveredAt) else assertNull(result.deliveredAt)
            if (to == OrderStatus.COMPLETE || to == OrderStatus.CANCEL) assertNull(result.reminderAt) else assertEquals(100L, result.reminderAt)
        }
    }

    @Test fun everyDisallowedTransitionThrowsIncludingTerminalAndSelf() {
        var disallowed = 0
        for (from in OrderStatus.entries) for (to in OrderStatus.entries) if (to !in allowed.getValue(from)) {
            disallowed++
            assertThrows("$from->$to", IllegalArgumentException::class.java) { OpticalRules.transition(orderAt(from), to, 200) }
        }
        // 16 ordered pairs total minus 6 allowed = 10 disallowed, covering all moves out of COMPLETE/CANCEL and all self-transitions.
        assertEquals(10, disallowed)
    }
}
