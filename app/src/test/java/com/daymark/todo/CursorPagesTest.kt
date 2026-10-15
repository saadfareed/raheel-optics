package com.daymark.todo

import com.daymark.todo.core.CursorPages
import com.daymark.todo.core.ListCursor
import org.junit.Assert.*
import org.junit.Test

class CursorPagesTest {
    @Test fun longNavigationEvictsTrailAndFirstRemainsReachable() {
        val pages=CursorPages(3)
        repeat(1000) {pages.next(ListCursor(it.toLong(),"task-$it"))}
        assertEquals(1001,pages.number)
        pages.previous();pages.previous();pages.previous()
        assertEquals(999,pages.number)
        assertFalse(pages.canPrevious)
        pages.reset()
        assertEquals(1,pages.number);assertNull(pages.cursor)
    }
    @Test fun revisitingNextAfterBackDiscardsStaleFutureAnchors() {
        val pages=CursorPages()
        pages.next(ListCursor(1,"a"));pages.next(ListCursor(2,"b"))
        pages.previous();pages.next(ListCursor(3,"changed"))
        assertEquals(ListCursor(3,"changed"),pages.cursor)
        pages.previous();assertEquals(ListCursor(1,"a"),pages.cursor)
    }
    @Test fun failedNavigationCanDiscardTrialWithoutChangingCurrentPage() {
        val current=CursorPages(3)
        current.next(ListCursor(1,"a"))
        val trial=current.copy().apply {next(ListCursor(2,"b"))}
        assertEquals(2,current.number);assertEquals(ListCursor(1,"a"),current.cursor)
        assertEquals(3,trial.number)
        trial.reset()
        assertEquals(2,current.number)
    }
}
