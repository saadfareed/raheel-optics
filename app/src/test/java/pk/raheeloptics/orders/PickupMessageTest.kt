package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class PickupMessageTest {
    private val friday=LocalDate.of(2026,11,13)
    private val jazz=PaymentAccount(PaymentAccountType.JAZZCASH,title="Raheel",number="0300 1234567")
    private val ubl=PaymentAccount(PaymentAccountType.BANK,bank="UBL",title="Raheel Optics",number="0123 4567 8901")
    private val shop=ShopSchedule(name="Raheel Optics",phone="0310 4585225",days=List(7) {ShopDay(intervals=listOf(OpeningInterval(600,1200)))},paymentAccounts=listOf(jazz,ubl))
    private fun order(total:Long,paid:Long)=Order("o","Ali",orderNumber="RO-1",amounts=OrderAmounts(framePaisa=total),
        payments=if(paid>0) listOf(Payment("p",paid)) else emptyList())

    @Test fun openingHoursAreCutIntoTwoHourSlotsAndPastSlotsAreDroppedToday() {
        assertEquals(listOf(600,720,840,960,1080),shop.pickupSlots(friday).map {it.start})
        assertEquals(1200,shop.pickupSlots(friday).last().end)
        assertEquals(listOf(720,840,960,1080),shop.pickupSlots(friday,LocalDateTime.of(friday,java.time.LocalTime.of(12,0))).map {it.start})
        assertEquals(5,shop.pickupSlots(friday.plusDays(1),LocalDateTime.of(friday,java.time.LocalTime.of(23,0))).size)
        val overnight=shop.copy(days=List(7) {ShopDay(intervals=listOf(OpeningInterval(1320,60)))})
        assertEquals(listOf(OpeningInterval(1320,0),OpeningInterval(0,60)),overnight.pickupSlots(friday))
    }

    @Test fun messageAlwaysShowsTheDueAndListsSharedAccountsOnlyWhenSomethingIsOwed() {
        val owing=shop.readyMessage(order(500000,200000),friday,OpeningInterval(840,960))
        assertTrue(owing.contains("Pickup: Fri 13 Nov, 2:00 PM – 4:00 PM"))
        assertTrue(owing.contains("Amount due: PKR 3,000"))
        assertTrue(owing.contains("• JazzCash 0300 1234567 · Raheel"))
        assertTrue(owing.contains("• UBL 0123 4567 8901 · Raheel Optics"))
        assertTrue(owing.contains("payment screenshot"))
        val paid=shop.readyMessage(order(500000,500000),friday,OpeningInterval(840,960))
        assertTrue(paid.contains("fully paid"))
        assertFalse(paid.contains("JazzCash"))
        val hidden=shop.copy(paymentAccounts=listOf(jazz.copy(shared=false))).readyMessage(order(500000,0),friday,null)
        assertFalse(hidden.contains("JazzCash"));assertFalse(hidden.contains("screenshot"))
        assertTrue(hidden.contains("Amount due: PKR 5,000"))
    }

    @Test fun accountsAreValidatedByMethod() {
        shop.validate()
        PaymentAccount(PaymentAccountType.BANK,bank="Meezan Bank",title="Raheel Optics",number="PK36 MEZN 0001 2345 6789 0123").validate()
        assertThrows(IllegalArgumentException::class.java) {PaymentAccount(PaymentAccountType.EASYPAISA,number="12345").validate()}
        assertThrows(IllegalArgumentException::class.java) {ubl.copy(title="").validate()}
        assertThrows(IllegalArgumentException::class.java) {ubl.copy(bank="").validate()}
        assertThrows(IllegalArgumentException::class.java) {ubl.copy(number="12ab").validate()}
        assertThrows(IllegalArgumentException::class.java) {shop.copy(paymentAccounts=List(7) {jazz}).validate()}
    }

    @Test fun placedMessageGreetsAndSummarisesTheOrder() {
        val promised=java.time.ZonedDateTime.of(2026,11,14,15,0,0,0,java.time.ZoneId.of("Asia/Karachi")).toInstant().toEpochMilli()
        val rx=Prescription(EyePrescription("-2.25","-0.75",90),EyePrescription("-1.75"),addRight="+1.50",addLeft="+1.50",pd="62")
        val placed=shop.placedMessage(order(1700000,500000).copy(frame=FrameDetails(brand="Ray-Ban",model="RB2140"),lens=LensDetails(material="Blue cut"),dueAt=promised,prescription=rx))
        assertTrue(placed.contains("Expected ready: Sat 14 Nov, 3:00 PM\n\nPrescription\nRight (OD): SPH -2.25 · CYL -0.75 · AXIS 90\nLeft (OS): SPH -1.75\nADD: +1.50 / +1.50\nPD: 62 mm\n"))
        assertTrue(placed.startsWith("Hello Ali, thank you for choosing Raheel Optics!"))
        assertTrue(placed.contains("Your order RO-1 has been placed."))
        assertTrue(placed.contains("Frame: Ray-Ban RB2140 · Lens: Blue cut"))
        assertTrue(placed.contains("Total: PKR 17,000 · Paid: PKR 5,000 · Due: PKR 12,000"))
        assertTrue(placed.contains("Expected ready: Sat 14 Nov, 3:00 PM"))
        val bare=shop.placedMessage(order(300000,300000))
        assertFalse(bare.contains("Frame:"));assertFalse(bare.contains("Expected ready"))
        assertTrue(bare.contains("Total: PKR 3,000 · Paid: PKR 3,000 · Fully paid"))
        assertFalse(bare.contains("Prescription"))
        assertFalse(shop.placedMessage(order(0,0)).contains("Total"))
    }

    @Test fun completedMessageAsksForFeedbackAndReviewOnlyWhenALinkIsSet() {
        val done=shop.completedMessage(order(100,100))
        assertTrue(done.contains("How was your experience?"))
        assertTrue(done.contains(ShopSchedule.DEFAULT_REVIEW_URL))
        val noLink=shop.copy(reviewUrl="").completedMessage(order(100,100))
        assertFalse(noLink.contains("Google review"))
        shop.copy(reviewUrl="https://g.page/r/abc123/review").validate()
        assertThrows(IllegalArgumentException::class.java) {shop.copy(reviewUrl="g.page/r/abc").validate()}
    }

    @Test fun customPickupRangeWorksEvenOnAClosedDay() {
        val closed=shop.copy(exceptions=mapOf(friday.toString() to ShopDay(open=false)))
        assertTrue(closed.pickupSlots(friday).isEmpty())
        assertTrue(closed.readyMessage(order(100,0),friday,OpeningInterval(1020,1140)).contains("Pickup: Fri 13 Nov, 5:00 PM – 7:00 PM\n"))
    }
}
