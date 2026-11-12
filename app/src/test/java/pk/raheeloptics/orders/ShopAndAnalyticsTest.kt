package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ShopAndAnalyticsTest {
    @Test fun sundayCanOpenAndExceptionsOverrideWeeklyHours() {
        val sunday=LocalDate.of(2026,10,18)
        val schedule=ShopSchedule(days=List(7) {ShopDay(open=true)})
        schedule.validate()
        assertTrue(schedule.effective(sunday).open)
        val holiday=schedule.copy(exceptions=mapOf(sunday.toString() to ShopDay(open=false)))
        holiday.validate()
        assertFalse(holiday.effective(sunday).open)
        assertTrue(holiday.readyMessage(Order("o","Customer"),date=sunday).contains("If the shop is closed"))
    }
    @Test fun overnightHoursCannotOverlapTheNextDay() {
        val days=List(7) {ShopDay(open=false)}.toMutableList()
        days[0]=ShopDay(intervals=listOf(OpeningInterval(1320,120)))
        days[1]=ShopDay(intervals=listOf(OpeningInterval(60,180)))
        assertThrows(IllegalArgumentException::class.java) {ShopSchedule(days=days).validate()}
    }
    @Test fun editingPaidAmountPreservesEarlierPaymentDates() {
        val prior=listOf(Payment("p",10000,receivedAt=10))
        val payments=Money.payments(prior,15000,20)
        assertEquals(prior.first(),payments.first())
        assertEquals(5000L,payments.last().amountPaisa)
        assertEquals(20L,payments.last().receivedAt)
        assertThrows(IllegalArgumentException::class.java) {Money.payments(prior,5000,20)}
        assertEquals(12345L,Money.parse("123.45"))
    }
    @Test fun salesAndCashUseTheirOwnDatesAndCancelledOrdersDoNotInflateSales() {
        val report=OrderAnalyticsAccumulator(100,200,now=150)
        report.add(Order("old","Older customer",orderedAt=50,amounts=OrderAmounts(framePaisa=10000),payments=listOf(Payment("p",4000,receivedAt=120))))
        report.add(Order("new","New customer",orderedAt=110,orderStatus=OrderStatus.READY_FOR_PICKUP,amounts=OrderAmounts(lensPaisa=5000)))
        report.add(Order("cancel","Cancelled",orderedAt=110,orderStatus=OrderStatus.CANCEL,amounts=OrderAmounts(framePaisa=9000)))
        val result=report.result()
        assertEquals(5000L,result.sales)
        assertEquals(4000L,result.collected)
        assertEquals(11000L,result.outstanding)
        assertEquals(1,result.orders)
        assertEquals(1,result.statuses[OrderStatus.READY_FOR_PICKUP])
    }
    @Test fun quarterlyBoundsUsePakistanCalendar() {
        val today=LocalDate.of(2026,10,16)
        val zone=ZoneId.of("Asia/Karachi")
        val bounds=OrderAnalyticsQuery(OrderAnalyticsPeriod.QUARTER).bounds(today)
        assertEquals(LocalDate.of(2026,10,1).atStartOfDay(zone).toInstant().toEpochMilli(),bounds.first)
        assertEquals(today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),bounds.second)
    }
}
