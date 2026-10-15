package com.daymark.todo

import com.daymark.todo.core.*
import com.daymark.todo.history.HistoryRules
import org.junit.Assert.*
import org.junit.Test

class HistoryRulesTest {
    @Test fun pendingOverlayWinsOverCloudCopyUntilAcknowledgment() {
        val pending=HistoryEntry("op",Todo("task","title"),"complete",1,true)
        val rows=HistoryRules.merge(listOf(pending),listOf(pending.copy(pending=false)))
        assertEquals(1,rows.size);assertTrue(rows.single().pending)
    }
    @Test fun completeAndDeleteRemainSeparateHistoryEntriesForSameTask() {
        val done=HistoryEntry("1",Todo("task","title"),"complete",1)
        val deleted=done.copy(id="2",action="delete",occurredAt=2)
        val merged=HistoryRules.merge(listOf(done),listOf(deleted))
        assertEquals(listOf(deleted),HistoryRules.filter(merged,"Deleted"))
        assertEquals(listOf(done),HistoryRules.filter(merged,"Completed"))
        assertEquals(2,HistoryRules.filter(merged,"All").size)
    }
    @Test fun historyListKeepsOneLatestRowPerTodoAcrossPagesAndPendingChanges() {
        val old=HistoryEntry("1",Todo("task","Task",revision=2,status="completed"),"complete",500)
        val reopened=old.copy(id="2",todo=old.todo.copy(revision=3,status="active"),action="reopen",occurredAt=200)
        val done=reopened.copy(id="3",todo=reopened.todo.copy(revision=4,status="completed"),action="complete",occurredAt=700)
        val deleted=done.copy(id="4",todo=done.todo.copy(revision=5,status="deleted"),action="delete",occurredAt=100,pending=true)
        assertEquals(listOf(deleted),HistoryRules.latest(listOf(old,reopened,done,deleted,old)))
        assertEquals(listOf(done),HistoryRules.latest(listOf(old,reopened,done)))
    }

}
