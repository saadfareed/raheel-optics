package pk.raheeloptics.orders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.identity.Session
import pk.raheeloptics.orders.ui.TodoViewModel
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
                val todo=Order("$account-$i","History fixture $i",status="completed",revision=2)
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

    @Test fun loadMoreAppendsOrdersAndKeepsEarlierRowsInPlace() {
        lateinit var model:TodoViewModel
        compose.runOnUiThread {model=ViewModelProvider(compose.activity)[TodoViewModel::class.java]}
        compose.waitUntil(10000) {!model.state.value.loading && !model.state.value.busy}
        // Only this test's own orders count, whatever else the shared test data holds (for example demo orders).
        compose.runOnUiThread {model.updateQuery(model.state.value.query.copy(search="Task "))}
        val page=ListRules.PageSize
        runBlocking {repeat(2*page+5) {i -> app.workspace()!!.save(Order("$account-active-$i","Task $i",notes="x".repeat(4000)),"create",null)}}
        compose.waitUntil(10000) {model.state.value.todos.size==page && model.state.value.todosMore}
        val first=model.state.value.todos.map {it.id}
        compose.runOnUiThread {model.loadMoreTodos()}
        compose.waitUntil(10000) {model.state.value.todos.size==2*page && !model.state.value.todosLoading}
        assertEquals("earlier rows keep their order",first,model.state.value.todos.take(page).map {it.id})
        assertEquals(2*page,model.state.value.todos.map {it.id}.toSet().size)
        compose.runOnUiThread {model.loadMoreTodos()}
        compose.waitUntil(10000) {model.state.value.todos.size>=2*page+5 && !model.state.value.todosLoading}
        assertFalse("everything loaded",model.state.value.todosMore)
        compose.runOnUiThread {model.updateQuery(model.state.value.query.copy(search="Task 1"))}
        compose.waitUntil(10000) {model.state.value.todos.size<50 && !model.state.value.todosLoading}
        assertEquals("a new query starts again from one page",true,model.state.value.todos.size<=ListRules.PageSize)
    }

}
