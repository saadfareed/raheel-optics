package com.daymark.todo

import com.daymark.todo.core.*
import org.junit.Assert.*
import org.junit.Test

class InvoiceTest {
    private val shop=ShopSchedule(name="Raheel Optics",phone="0310 4585225",address="Main Bazaar")
    private val order=Todo("o1","Saad",orderNumber="RO-261112-AB2C",customerMobile="0333 4909162",normalizedMobile="+923334909162",
        frame=FrameDetails(model="Ray-Ban metal"),lens=LensDetails(material="Blue cut"),amounts=OrderAmounts(framePaisa=250000,lensPaisa=100000),
        payments=listOf(Payment("p1",100000,PaymentMethod.JAZZCASH,1_760_000_000_000)),dueAt=1_760_100_000_000,orderStatus=OrderStatus.READY_FOR_PICKUP)

    @Test fun invoiceCarriesTotalsPaymentsOutstandingAndReceiveTime() {
        val inv=InvoiceBuilder.build(shop,"Saad","0333 4909162",listOf(order),1_760_000_000_000)
        val lines=inv.sections.single().lines
        assertEquals("PKR 3,500",lines.first {it.label=="Order total"}.value)
        assertEquals("PKR 1,000",lines.first {it.label=="Paid"}.value)
        assertEquals("PKR 2,500",lines.first {it.label=="Outstanding"}.value)
        assertTrue(lines.first {it.label=="Outstanding"}.alert)
        assertTrue(lines.any {it.label=="Ready since" && Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2} (AM|PM)").matches(it.value)})
        assertTrue(lines.any {it.label.startsWith("Payment · Jazzcash")})
        assertTrue(lines.any {l ->l.label=="Frame" && l.value=="Ray-Ban metal"})
        assertEquals("PKR 2,500",inv.totals.last().value)
        assertTrue(inv.footer.contains("0310 4585225"))
    }
    @Test fun cancelledOrdersAreNeverInvoicedAndTotalsAddUp() {
        val second=order.copy(id="o2",orderNumber="RO-2",amounts=OrderAmounts(framePaisa=50000),payments=emptyList(),orderStatus=OrderStatus.ACTIVE,dueAt=null)
        val inv=InvoiceBuilder.build(shop,"Saad","x",listOf(order,second,order.copy(id="o3",orderNumber="RO-3",orderStatus=OrderStatus.CANCEL)),0)
        assertEquals(2,inv.sections.size);assertFalse(inv.sections.any {it.title.contains("RO-3")})
        assertEquals("PKR 4,000",inv.totals[0].value);assertEquals("PKR 1,000",inv.totals[1].value);assertEquals("PKR 3,000",inv.totals[2].value)
    }
    @Test fun dateTextUsesTheRequestedPattern() {
        assertEquals("2026-11-12 08-09 AM",DateText.dateTime(java.time.ZonedDateTime.of(2026,11,12,8,9,0,0,java.time.ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli()))
        assertEquals("2026-11-12 08-09 PM",DateText.dateTime(java.time.ZonedDateTime.of(2026,11,12,20,9,0,0,java.time.ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli()))
    }
    @Test fun moneyDisplayHasSeparatorsAndNoNeedlessDecimals() {
        assertEquals("PKR 3,000",Money.pkr(300000));assertEquals("PKR 1,234,500",Money.pkr(123450000));assertEquals("PKR 0",Money.pkr(0))
    }
}
