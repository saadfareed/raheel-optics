package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import org.junit.Assert.*
import org.junit.Test

class OrderListRulesTest {
    @Test fun phoneSearchMatchesLocalInternationalAndFormattedInput() {
        listOf("0300 123-4567", "+92 (300) 1234567", "00923001234567").forEach {
            assertEquals("3001234567",ListRules.phoneSearch(it))
        }
        assertEquals("300123",ListRules.phoneSearch("0300 123"))
        assertNull(ListRules.phoneSearch("RO-123456"))
    }
    @Test fun orderSortingUsesOrderDateRatherThanLastEdit() {
        val old=Order("old","A",orderedAt=100,updatedAt=900)
        val recent=Order("recent","B",orderedAt=200,updatedAt=300)
        assertTrue(ListRules.sortKey(recent,OrderSort.Newest)<ListRules.sortKey(old,OrderSort.Newest))
        assertTrue(ListRules.sortKey(old,OrderSort.Oldest)<ListRules.sortKey(recent,OrderSort.Oldest))
    }
}
