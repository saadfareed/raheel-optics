package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class OrderAlertTextTest {
    private val zone=ZoneId.of("Asia/Karachi")
    private fun at(day:Int,hour:Int,minute:Int=0)=LocalDateTime.of(2026,11,day,hour,minute).atZone(zone).toInstant().toEpochMilli()
    private val now=at(13,15)
    private fun order(status:OrderStatus=OrderStatus.ACTIVE,due:Long?=at(13,16),total:Long=500000,paid:Long=200000)=
        Order("o","Ali Khan",orderNumber="RO-12",dueAt=due,orderStatus=status,customerMobile="0300 1234567",
            amounts=OrderAmounts(framePaisa=total),payments=if(paid>0) listOf(Payment("p",paid)) else emptyList())

    @Test fun reminderNamesTheCustomerOrderTimeAndBalance() {
        val copy=OrderAlerts.text(order(),AlertKind.Reminder,now,zone)
        assertEquals("Ali Khan's glasses are due today 4:00 PM",copy.title)
        assertEquals("Order RO-12 · Not ready yet · PKR 3,000 due",copy.text)
        assertTrue(copy.details.contains("Phone: 0300 1234567"))
        assertTrue(copy.details.contains("Make sure it is ready on time."))
        assertEquals("0300 1234567",copy.phone)
    }

    @Test fun promisedTimeReachedSaysWhatToDoNext() {
        val late=OrderAlerts.text(order(),AlertKind.Deadline,at(13,16),zone)
        assertEquals("Ali Khan's glasses are due now",late.title)
        assertTrue(late.details.contains("Check with the lab"))
        val waiting=OrderAlerts.text(order(OrderStatus.READY_FOR_PICKUP),AlertKind.Deadline,at(13,17),zone)
        assertEquals("Ali Khan hasn't collected their glasses",waiting.title)
        assertTrue(waiting.details.contains("collect PKR 3,000"))
        val paid=OrderAlerts.text(order(OrderStatus.READY_FOR_PICKUP,paid=500000),AlertKind.Reminder,now,zone)
        assertEquals("Order RO-12 · Ready for pickup · Fully paid",paid.text)
    }

    @Test fun datesReadNaturally() {
        assertEquals("tomorrow 11:30 AM",OrderAlerts.moment(at(14,11,30),now,zone))
        assertEquals("Wed 18 Nov, 9:00 AM",OrderAlerts.moment(at(18,9),now,zone))
        assertEquals("Follow up with Ali Khan",OrderAlerts.text(order(due=null),AlertKind.Reminder,now,zone).title)
    }
}
