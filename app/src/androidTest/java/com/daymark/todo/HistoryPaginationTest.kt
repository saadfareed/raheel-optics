package com.daymark.todo

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.core.*
import com.daymark.todo.identity.Session
import com.daymark.todo.ui.TodoViewModel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID

class HistoryPaginationTest {
    private val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DaymarkApp
    private val account="history-test-${UUID.randomUUID()}"
    private var previousTotals:Totals?=null
    private val fixture=object : ExternalResource() {
        override fun before()=runBlocking {
            app.logout()
            app.sessions.save(Session(account,"","","",Long.MAX_VALUE,true))
            val store=app.workspace()!!
            val json=Json {encodeDefaults=true}
            store.db.writeTransaction {tx ->
                previousTotals=tx.getOptional("SELECT active,completed,deleted,revision FROM local_totals LIMIT 1") {Totals(it.getLong(0)!!.toInt(),it.getLong(1)!!.toInt(),it.getLong(2)!!.toInt(),it.getLong(3)!!)}
                val totals=previousTotals ?: Totals()
                tx.execute("INSERT OR REPLACE INTO local_totals(id,active,completed,deleted,revision) VALUES('totals',?,?,?,?)",listOf(totals.active,totals.completed+26,totals.deleted,totals.revision))
                val baseTime=System.currentTimeMillis()
                repeat(26) {i ->
                val todo=Todo("$account-$i","History fixture $i",status="completed",revision=2)
                val entry=HistoryEntry("$account-entry-$i",todo,"complete",baseTime-i*1000L)
                tx.execute("INSERT INTO history_cache(id,body,occurred_at,state) VALUES(?,?,?,'local')",listOf(entry.id,json.encodeToString(entry),entry.occurredAt))
                tx.execute("INSERT INTO local_state(id,status,revision) VALUES(?,'completed',2)",listOf(todo.id))
            }}
        }
        override fun after() {runBlocking {
            try {
                app.workspace()?.db?.writeTransaction {tx ->
                    tx.execute("DELETE FROM history_cache WHERE json_extract(body,'$.todo.id') LIKE ?",listOf("$account-%"))
                    tx.execute("DELETE FROM drafts WHERE id LIKE ?",listOf("$account-%"))
                    tx.execute("DELETE FROM local_state WHERE id LIKE ?",listOf("$account-%"))
                    tx.execute("DELETE FROM local_totals")
                    previousTotals?.let {tx.execute("INSERT INTO local_totals(id,active,completed,deleted,revision) VALUES('totals',?,?,?,?)",listOf(it.active,it.completed,it.deleted,it.revision))}
                }
            } finally {app.logout()}
        }}
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(fixture).around(compose)

    @Test fun localHistoryLoadsOlderRowsWithMetadataAndCanReopenThem() {
        lateinit var model:TodoViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[TodoViewModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.loading && !model.state.value.busy}
        assertEquals(25,model.state.value.history.size)
        assertNotNull("Fixture must have a second saved-history page",runBlocking {app.workspace()!!.historyPage().next})
        compose.onNodeWithContentDescription("History").performClick()
        compose.waitForIdle()
        compose.runOnUiThread {model.loadMoreHistory()}
        compose.waitUntil(10000) {model.state.value.historyPageNumber==2 && !model.state.value.historyLoading}
        assertTrue(model.state.value.history.size<=ListRules.PageSize)
        assertEquals(2,model.state.value.historyPageNumber)
        assertTrue(model.state.value.savedHistoryPrevious)
        assertTrue(model.state.value.history.any {it.todo.id=="$account-25"})
        assertEquals("completed" to 2L,model.state.value.snapshot.currentStates["$account-25"])
        val older=model.state.value.history.single {it.todo.id=="$account-25"}
        compose.runOnUiThread {model.transition(older.todo,"reopen")}
        compose.waitUntil(10000) {!model.state.value.busy}
        compose.runOnUiThread {model.firstSavedHistory()}
        compose.waitUntil(10000) {model.state.value.history.any {it.todo.id=="$account-25" && it.action=="reopen"} && !model.state.value.historyLoading}
        assertTrue("$account-25" in model.state.value.snapshot.activeTaskIds)
    }
    @Test fun taskPagesReplacePayloadsAndCanReturnToFirstPage() {
        lateinit var model:TodoViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[TodoViewModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.loading && !model.state.value.busy}
        runBlocking {repeat(55) {i -> app.workspace()!!.save(Todo("$account-active-$i","Task $i",notes="x".repeat(4000)),"create",null)}}
        compose.waitUntil(10000) {model.state.value.todos.size==25 && model.state.value.todosMore}
        val first=model.state.value.todos.map {it.id}.toSet()
        compose.runOnUiThread {model.loadMoreTodos()}
        compose.waitUntil(10000) {model.state.value.todoPageNumber==2 && !model.state.value.todosLoading}
        assertEquals(25,model.state.value.todos.size)
        assertTrue(first.intersect(model.state.value.todos.map {it.id}.toSet()).isEmpty())
        compose.runOnUiThread {model.firstTodoPage()}
        compose.waitUntil(10000) {model.state.value.todoPageNumber==1 && !model.state.value.todosLoading}
        assertEquals(first,model.state.value.todos.map {it.id}.toSet())
    }

}
