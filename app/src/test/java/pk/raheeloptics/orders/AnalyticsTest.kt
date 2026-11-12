package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.analytics.Analytics
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AnalyticsTest {
    @Test fun emptyProgressAndDeadlineDenominatorsHaveNoPercentage() {assertNull(Analytics.progress(Totals()));assertNull(Analytics.onTime(emptyList()))}
    @Test fun deletingCompletedTaskPreservesHistoricalCompletion() {
        val todo=Order("t","Task",dueAt=200)
        val complete=HistoryEntry("c",todo,"complete",100)
        val delete=HistoryEntry("d",todo.copy(status="deleted"),"delete",300)
        assertEquals(1f,Analytics.onTime(listOf(complete,delete))!!,0.001f)
        assertEquals(1,Analytics.completionTrend(listOf(complete,delete),1,LocalDate.of(1970,1,1),ZoneId.of("UTC")).single().second)
    }
    @Test fun trendUsesLocalCalendarDaysAndDeduplicatesOperationIds() {
        val time=Instant.parse("2026-10-05T21:00:00Z").toEpochMilli()
        val h=HistoryEntry("one",Order("t","Task"),"complete",time)
        val trend=Analytics.completionTrend(listOf(h,h),2,LocalDate.of(2026,10,6),ZoneId.of("Asia/Karachi"))
        assertEquals(listOf(0,1),trend.map {it.second})
    }
    @Test fun acknowledgmentCoveredBySummaryIsNotCountedTwice() {
        val op=Operation("op",Order("t","Task",status="completed"),"complete",0,occurredAt=100,beforeStatus="active")
        assertEquals(Totals(0,1,0,9),Rules.overlayTotals(Totals(0,1,0,9),listOf(PendingOperation(op,"acked",9))))
        assertEquals(1,Rules.overlayTotals(Totals(1,0,0,8),listOf(PendingOperation(op,"acked",9))).completed)
    }
    @Test fun conflictDoesNotChangeConfirmedTotals() {
        val op=Operation("op",Order("t","Task",status="deleted"),"delete",0,beforeStatus="active")
        assertEquals(Totals(2,1),Rules.overlayTotals(Totals(2,1),listOf(PendingOperation(op,"conflict"))))
    }
    @Test fun offlineCreateCompleteDeleteChainUpdatesCurrentPortfolio() {
        val todo=Order("t","Task")
        val ops=listOf(Operation("1",todo,"create",0,beforeStatus="absent"),Operation("2",todo.copy(status="completed"),"complete",0,"1",beforeStatus="active"),Operation("3",todo.copy(status="deleted"),"delete",0,"2",beforeStatus="completed"))
        assertEquals(Totals(0,0,1),Rules.overlayTotals(Totals(),ops.map {PendingOperation(it,"queued")}))
    }
}
