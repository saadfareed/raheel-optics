package com.daymark.todo.core

/** Stores only a bounded navigation trail, never page payloads. First is always available. */
class CursorPages(private val retainedPages: Int = 128) {
    init { require(retainedPages >= 2) }
    private val anchors = ArrayDeque<ListCursor?>().apply { addLast(null) }
    private var firstNumber = 1
    var number: Int = 1
        private set
    val cursor: ListCursor? get() = anchors[number - firstNumber]
    val canPrevious: Boolean get() = number > firstNumber
    fun reset() { anchors.clear(); anchors.addLast(null); firstNumber = 1; number = 1 }
    fun next(cursor: ListCursor) {
        while (anchors.size > number - firstNumber + 1) anchors.removeLast()
        anchors.addLast(cursor); number++
        if (anchors.size > retainedPages) { anchors.removeFirst(); firstNumber++ }
    }
    fun previous() { if (canPrevious) number-- }
    fun copy():CursorPages = CursorPages(retainedPages).also {
        it.anchors.clear();it.anchors.addAll(anchors);it.firstNumber=firstNumber;it.number=number
    }
}
