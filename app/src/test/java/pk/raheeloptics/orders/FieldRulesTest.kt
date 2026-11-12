package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class FieldRulesTest {
    @Test fun customerNameIsRequiredBoundedAndContainsLetters() {
        assertNotNull(FieldRules.name(""));assertNotNull(FieldRules.name(" a "));assertNotNull(FieldRules.name("12345"))
        assertNotNull(FieldRules.name("x".repeat(FieldRules.NameMax+1)));assertNotNull(FieldRules.name("Ali\u0000"))
        assertNull(FieldRules.name("Ayesha Khan"));assertNull(FieldRules.name("x".repeat(FieldRules.NameMax)));assertNull(FieldRules.name("عائشہ خان"))
    }
    @Test fun mobileMustBeAValidPakistaniNumber() {
        assertNotNull(FieldRules.mobile(""));assertNotNull(FieldRules.mobile("12345"));assertNotNull(FieldRules.mobile("abc"))
        assertNull(FieldRules.mobile("0300 1234567"));assertNull(FieldRules.mobile("+92 300 1234567"))
    }
    @Test fun amountsAreWholeRupeesNeverStartingWithZero() {
        assertNull(FieldRules.amount("","Frame"));assertNull(FieldRules.amount("1500","Frame"));assertNull(FieldRules.amount("10000000","Frame"))
        assertNotNull("decimals",FieldRules.amount("1500.50","Frame"));assertNotNull(FieldRules.amount("1.5","Frame"))
        assertNotNull("leading zero",FieldRules.amount("0150","Frame"));assertNotNull("zero",FieldRules.amount("0","Frame"))
        assertNotNull(FieldRules.amount("-5","Frame"));assertNotNull(FieldRules.amount("abc","Frame"))
        assertNotNull(FieldRules.amount("10000001","Frame"));assertNotNull(FieldRules.amount("99999999999999999999","Frame"))
    }
    @Test fun wholeInputStripsEverythingButDigitsAndLeadingZeros() {
        assertEquals("1500",FieldRules.wholeInput("0001500"));assertEquals("15",FieldRules.wholeInput("1.5"));assertEquals("",FieldRules.wholeInput("000"));assertEquals("",FieldRules.wholeInput("-"))
        assertEquals(8,FieldRules.wholeInput("123456789012").length)
    }
    @Test fun discountAndPaymentCannotExceedWhatTheyApplyTo() {
        assertNull(FieldRules.discount(100,100));assertNotNull(FieldRules.discount(101,100))
        assertNull(FieldRules.paid(100,100));assertNotNull(FieldRules.paid(101,100))
    }
    @Test fun addAndPdHaveRealisticRanges() {
        assertNull(FieldRules.add("","ADD"));assertNull(FieldRules.add("+2.00","ADD"));assertNotNull(FieldRules.add("0","ADD"));assertNotNull(FieldRules.add("11","ADD"));assertNotNull(FieldRules.add("x","ADD"))
        assertNull(FieldRules.pd("62"));assertNull(FieldRules.pd(""));assertNotNull(FieldRules.pd("10"));assertNotNull(FieldRules.pd("200"))
    }
    @Test fun textFieldsAreLengthLimited() {
        assertNull(FieldRules.text("a".repeat(80),"Frame brand",80));assertNotNull(FieldRules.text("a".repeat(81),"Frame brand",80))
        assertNull(FieldRules.notes("n".repeat(FieldRules.NotesMax)));assertNotNull(FieldRules.notes("n".repeat(FieldRules.NotesMax+1)))
    }
    @Test fun eyeRangesAreChecked() {
        assertNull(FieldRules.eye(EyePrescription("-2.00","-0.50",90)));assertNotNull(FieldRules.eye(EyePrescription("-45")));assertNotNull(FieldRules.eye(EyePrescription("-2","-1.00",200)))
    }
    @Test fun decimalInputKeepsDigitsAndOneDot() {
        assertEquals("12.5",FieldRules.decimalInput("1a2.5"));assertEquals("1.25",FieldRules.decimalInput("1.2.5"));assertEquals("123",FieldRules.decimalInput("1-2,3"));assertEquals(5,FieldRules.decimalInput("123456",5).length)
    }
    @Test fun finalCheckRejectsWhatTheFormRejects() {
        val ok=Order("t","Ayesha",customerMobile="0300 1234567",normalizedMobile="+923001234567")
        Rules.validate(ok,0)
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(ok.copy(amounts=OrderAmounts(framePaisa=1000,discountPaisa=2000)),0)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(ok.copy(amounts=OrderAmounts(framePaisa=FieldRules.MaxAmountPaisa+1)),0)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(ok.copy(frame=FrameDetails(brand="b".repeat(81))),0)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(ok.copy(prescription=Prescription(pd="5")),0)}
    }
    @Test fun orderNumbersAreShortReadableAndDifferent() {
        val n=OrderNumbers.generate(1_760_000_000_000,Random(1))
        assertTrue(n,Regex("RO-\\d{6}-[2-9A-HJ-NP-Z]{5}").matches(n))
        assertNotEquals(OrderNumbers.generate(1,Random(1)),OrderNumbers.generate(1,Random(2)))
        val seeded=Random(7);assertEquals(300,(1..300).map {OrderNumbers.generate(0,seeded)}.toSet().size)
    }
}
