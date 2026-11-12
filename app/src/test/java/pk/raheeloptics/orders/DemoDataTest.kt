package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import org.junit.Assert.*
import org.junit.Test

class DemoDataTest {
    @Test fun demoOrdersPassTheSameRulesAsRealOrders() {
        val now=System.currentTimeMillis()
        val orders=DemoData.all("acct",now)
        orders.forEach {Rules.validate(it,now,checkReminder=true,checkDeadline=true)}
        assertEquals(OrderStatus.entries.toSet(),orders.map {it.orderStatus}.toSet())
        assertEquals(orders.size,orders.map {it.id}.toSet().size);assertEquals(orders.size,orders.map {it.orderNumber}.toSet().size)
        assertEquals("deterministic ids so loading twice never duplicates",orders.map {it.id},DemoData.all("acct",now+5).map {it.id})
        assertTrue(orders.filter {it.customerMobile==DemoData.CUSTOMER_MOBILE}.all {it.normalizedMobile=="+923334909162"})
        val customers=orders.groupBy {it.normalizedMobile}
        assertEquals("30+ customers so lists need more than one page",DemoData.EXTRA_CUSTOMERS+1,customers.size)
        assertTrue("more than 25 orders so the orders list paginates",orders.size>40)
        assertTrue("one name per mobile",customers.values.all {rows ->rows.map {it.title}.toSet().size==1})
        assertNotNull(PakistanPhone.normalize(DemoData.SHOP_MOBILE))
    }
}
