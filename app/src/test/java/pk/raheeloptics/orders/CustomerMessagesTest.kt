package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CustomerMessagesTest {
    private val shop=ShopSchedule(name="Raheel Optics",phone="0300 1234567")
    private val monday=LocalDate.of(2026,10,19)
    private fun order(no:String,total:Long,paid:Long,status:OrderStatus=OrderStatus.ACTIVE)=Order("id-$no","Ayesha Khan",orderNumber=no,
        amounts=OrderAmounts(framePaisa=total),payments=if(paid>0) listOf(Payment("p-$no",paid)) else emptyList(),orderStatus=status)

    @Test fun statementListsEachDueOrderAndTheTotal() {
        val text=shop.duesMessage("Ayesha Khan",listOf(order("RO-1",190000,70000),order("RO-2",50000,50000),order("RO-3",100000,0)),monday)
        assertTrue(text,text.contains("RO-1: total PKR 1900.00, paid PKR 700.00, due PKR 1200.00"))
        assertTrue(text.contains("RO-3: total PKR 1000.00, paid PKR 0.00, due PKR 1000.00"))
        assertFalse("settled orders are not listed",text.contains("RO-2"))
        assertTrue(text.contains("Total outstanding: PKR 2200.00"))
        assertFalse(text.contains("Ready for delivery"))
        assertTrue(text.contains("0300 1234567"))
    }
    @Test fun readyOrdersGetADeliveryNoticeAndCancelledOrdersAreIgnored() {
        val text=shop.duesMessage("Ayesha Khan",listOf(order("RO-1",100000,40000,OrderStatus.READY_FOR_PICKUP),order("RO-9",90000,0,OrderStatus.CANCEL)),monday)
        assertTrue(text.contains("Ready for delivery: RO-1"))
        assertTrue(text.contains("Pickup hours:"))
        assertTrue(text.contains("due PKR 600.00"))
        assertFalse(text.contains("RO-9"))
    }
    @Test fun customerWithNothingOwedGetsAFriendlyNote() {
        val text=shop.duesMessage("Ayesha Khan",listOf(order("RO-1",1000,1000,OrderStatus.COMPLETE)),monday)
        assertTrue(text.contains("no outstanding dues"))
        assertFalse(text.contains("Total outstanding"))
    }
    @Test fun missingShopNumberFallsBackToGenericContact() {
        assertTrue(ShopSchedule(phone="").duesMessage("A",listOf(order("RO-1",1000,0)),monday).contains("contact the shop"))
    }
    @Test fun orderViewsPartitionTheStatuses() {
        assertEquals(setOf(OrderStatus.ACTIVE),OrderView.ACTIVE.statuses);assertEquals(setOf(OrderStatus.READY_FOR_PICKUP),OrderView.READY.statuses)
        val covered=listOf(OrderView.ACTIVE,OrderView.READY,OrderView.COMPLETE,OrderView.CANCEL).flatMap {it.statuses!!}
        assertEquals(OrderStatus.entries.toSet(),covered.toSet());assertEquals(covered.size,covered.toSet().size)
    }
}
