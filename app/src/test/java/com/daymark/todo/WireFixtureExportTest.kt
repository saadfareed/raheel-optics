package com.daymark.todo

import com.daymark.todo.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Writes the exact RPC payloads the app sends for one order's life (customer, create, payment, refund, ready, complete)
 * to app/build/wire-fixtures.tsv. scripts/wire-contract-check.sh replays them against the real migrations and reads
 * the result back through the feed, so the app and the server contract cannot drift apart unnoticed.
 */
class WireFixtureExportTest {
    private val json=Json {encodeDefaults=true}
    @Test fun exportOrderLifecycle() {
        val account="acct"
        val base=DemoData.orders(account,1_760_000_000_000).first {it.orderStatus==OrderStatus.ACTIVE}.copy(payments=emptyList())
        val customer=Customer(java.util.UUID.nameUUIDFromBytes("$account:customer".toByteArray()).toString(),base.title,base.customerMobile,base.normalizedMobile,latestPrescription=base.prescription)
        var order=base.copy(customerId=customer.id)
        var t=1_760_000_000_000;var revision=0L
        val lines=mutableListOf<String>()
        lines+="upsert_optical_customer\t"+json.encodeToJsonElement(OpticalOperation(java.util.UUID.nameUUIDFromBytes("c".toByteArray()).toString(),
            OpticalPayload.CustomerUpsert(customer),OpticalActions.CUSTOMER_UPSERT,0,occurredAt=t++))
        fun change(action:String,next:Todo) {
            val op=Operation(java.util.UUID.nameUUIDFromBytes("op-$t".toByteArray()).toString(),next.copy(revision=revision+1),action,revision,occurredAt=t++)
            lines+="${OrderWire.APPLY}\t${OrderWire.applyPayload(op)}";revision++;order=next
        }
        change("create",order)
        change("edit",order.copy(payments=listOf(Payment("00000000-0000-4000-8000-000000000001",200000,PaymentMethod.CASH,t))))
        change("edit",order.copy(payments=order.payments+Payment("00000000-0000-4000-8000-000000000002",-50000,PaymentMethod.CASH,t)))
        change("edit",order.copy(orderStatus=OrderStatus.READY_FOR_PICKUP))
        change("complete",Rules.transition(order,"complete",t))
        File("build/wire-fixtures.tsv").apply {parentFile.mkdirs()}.writeText(lines.joinToString("\n")+"\n")
        assertEquals(6,lines.size)
        assertTrue(lines.none {"localImageUri" in it})
    }
}
