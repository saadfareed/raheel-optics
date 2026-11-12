package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import org.junit.Assert.*
import org.junit.Test

class ReminderPolicyTest {
    @Test fun deadlineAloneNeverSchedulesReminder() {assertTrue(ReminderPolicy.future(listOf(Order("t","Task",dueAt=500)),100).isEmpty())}
    @Test fun onlyActiveFutureRemindersAreScheduled() {
        val active=Order("a","Active",reminderAt=200)
        val completed=active.copy(id="c",status="completed")
        assertEquals(listOf(active),ReminderPolicy.future(listOf(active,completed,active.copy(id="past",reminderAt=50)),100))
    }
    @Test fun staleAlarmDoesNotMatchEditedReminder() {assertFalse(ReminderPolicy.matches(Order("t","Task",reminderAt=300),200))}
    @Test fun countdownPersistsAbsoluteSaveTime() {
        assertEquals(1801000L,Rules.countdown("30",1000))
        assertThrows(IllegalArgumentException::class.java) {Rules.countdown("0",1000)}
        assertThrows(IllegalArgumentException::class.java) {Rules.countdown("not a number",1000)}
    }
    @Test fun remindersCanBeBeforeOrAfterDeadline() {
        assertEquals(2400000L,Rules.deadlineReminder(4200000L,30,true))
        assertEquals(6000000L,Rules.deadlineReminder(4200000L,30,false))
        Rules.validate(Order("t","Task",dueAt=4200000L,reminderAt=6000000L),1000)
        assertThrows(IllegalArgumentException::class.java) {Rules.deadlineReminder(null,30,true)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(Order("t","Task",reminderAt=Rules.deadlineReminder(1000000L,30,true)),1000)}
    }
    @Test fun exactlyDueRemindersAppearInInboxPolicy() {val t=Order("t","Task",reminderAt=100);assertEquals(listOf(t),ReminderPolicy.due(listOf(t),100))}
}
