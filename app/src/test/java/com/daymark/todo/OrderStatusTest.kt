package com.daymark.todo

import com.daymark.todo.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class OrderStatusTest {
    @Test fun exactlyFourStatusesAndLabels() {
        assertEquals(listOf("Active","Ready for pickup","Complete","Cancel"),OrderStatus.entries.map {it.label})
    }
    @Test fun legacyRecordsDecodeAndWriteCanonicalStatus() {
        mapOf("NEW" to OrderStatus.ACTIVE,"IN_PRODUCTION" to OrderStatus.ACTIVE,"READY" to OrderStatus.READY_FOR_PICKUP,"DELIVERED" to OrderStatus.COMPLETE,"CANCELLED" to OrderStatus.CANCEL).forEach {(old,status) ->
            assertEquals(status,Json.decodeFromString<OrderStatus>("\"$old\""))
            assertEquals("\"${status.name}\"",Json.encodeToString(status))
        }
    }
    @Test fun cancellationAndReopenKeepDomainStatusConsistent() {
        val ready=Todo("1","Demo",orderStatus=OrderStatus.READY_FOR_PICKUP)
        assertEquals(OrderStatus.CANCEL,Rules.transition(ready,"delete",10).orderStatus)
        val complete=Rules.transition(ready,"complete",10)
        assertEquals(OrderStatus.COMPLETE,complete.orderStatus)
        assertEquals(OrderStatus.ACTIVE,Rules.transition(complete,"reopen",20).orderStatus)
    }
}
