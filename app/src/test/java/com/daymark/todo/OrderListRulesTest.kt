package com.daymark.todo

import com.daymark.todo.core.*
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
        val old=Todo("old","A",orderedAt=100,updatedAt=900)
        val recent=Todo("recent","B",orderedAt=200,updatedAt=300)
        assertTrue(ListRules.sortKey(recent,TodoSort.Newest)<ListRules.sortKey(old,TodoSort.Newest))
        assertTrue(ListRules.sortKey(old,TodoSort.Oldest)<ListRules.sortKey(recent,TodoSort.Oldest))
    }
}
