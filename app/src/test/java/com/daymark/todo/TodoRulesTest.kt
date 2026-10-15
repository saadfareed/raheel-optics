package com.daymark.todo

import com.daymark.todo.core.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TodoRulesTest {
    @Test fun completionCancelsReminderAndReopenDoesNotReplayIt() {
        val active=Todo("task","Pay bill",dueAt=100,reminderAt=90)
        val complete=Rules.transition(active,"complete",80)
        assertEquals("completed",complete.status);assertEquals(80L,complete.completedAt);assertNull(complete.reminderAt)
        val reopened=Rules.transition(complete,"reopen",110)
        assertEquals("active",reopened.status);assertNull(reopened.completedAt);assertNull(reopened.reminderAt);assertEquals(100L,reopened.dueAt)
    }
    @Test fun deletionRetainsTaskSnapshotForHistory() {
        val original=Todo("task","Original title",notes="Important detail",priority="High",dueAt=50)
        val deleted=Rules.transition(original,"delete",100)
        assertEquals("deleted",deleted.status);assertEquals(original.title,deleted.title);assertEquals(original.notes,deleted.notes)
    }
    @Test fun durableOperationRoundTripKeepsPredecessorAndSnapshot() {
        val op=Operation("operation",Todo("task","offline","notes"),"edit",3,"previous",400,"active")
        assertEquals(op,Json.decodeFromString<Operation>(Json.encodeToString(op)))
    }
    @Test fun titleLengthAndFutureTimesAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(Todo("task"," "),10)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(Todo("task","x".repeat(Rules.MaxTitleLength+1)),10)}
        Rules.validate(Todo("task","x".repeat(Rules.MaxTitleLength)),10)
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(Todo("task","name",reminderAt=10),10)}
        assertThrows(IllegalArgumentException::class.java) {Rules.validate(Todo("task","name",dueAt=10),10,checkDeadline=true)}
        Rules.validate(Todo("task","name",dueAt=11),10,checkDeadline=true)
        Rules.validate(Todo("task","name",dueAt=1),10,checkDeadline=false)
    }
    @Test fun invalidTransitionCannotResurrectDeletion() {
        assertThrows(IllegalArgumentException::class.java) {Rules.transition(Todo("task","name",status="deleted"),"reopen",100)}
    }
}
