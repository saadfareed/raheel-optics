package pk.raheeloptics.orders

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.core.Order
import pk.raheeloptics.orders.data.OrderStore
import pk.raheeloptics.orders.data.appSchema
import com.powersync.PowerSyncDatabase
import com.powersync.DatabaseDriverFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineStoreTest {
    private val openedDatabases=mutableListOf<PowerSyncDatabase>()
    private val testFiles=mutableSetOf<String>()
    private fun store(context:android.content.Context,account:String,localOnly:Boolean):OrderStore {
        val filename="test-$account.db"
        testFiles+=filename
        val database=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        openedDatabases+=database
        return OrderStore(database,account,localOnly)
    }

    @After fun removeTestDatabases()=runBlocking {
        openedDatabases.asReversed().forEach { database -> runCatching { database.close() } }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        testFiles.forEach { filename ->
            if(context.getDatabasePath(filename).exists()) {
                assertTrue("Could not remove $filename",context.deleteDatabase(filename))
            }
        }
    }
    @Test fun createCompleteRestartKeepsHistoryAndEvictsActiveBody()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val account=UUID.randomUUID().toString()
        var store=store(context,account,true)
        val todo=Order(UUID.randomUUID().toString(),"Offline persisted task",priority="High")
        store.save(todo,"create",null)
        val saved=store.snapshot().todos.single()
        store.transition(saved,"complete")
        assertTrue(store.snapshot().todos.isEmpty())
        assertEquals(0L,store.db.get("SELECT count(*) FROM drafts") {it.getLong(0)!!})
        store.db.close()
        store=store(context,account,true)
        assertTrue(store.snapshot().todos.isEmpty())
        assertEquals("Offline persisted task",store.snapshot().history.single().todo.title)
        assertEquals(1,store.snapshot().totals.completed)
        store.db.close()
    }
    @Test fun cloudQueueAndOverlaySurviveRestartUntilAcknowledgment()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val account=UUID.randomUUID().toString()
        var store=store(context,account,false)
        val todo=Order(UUID.randomUUID().toString(),"Pending cloud task")
        store.save(todo,"create",null);store.transition(store.snapshot().todos.single(),"delete")
        store.db.close();store=store(context,account,false)
        val snapshot=store.snapshot()
        assertTrue(snapshot.todos.isEmpty());assertEquals(2,snapshot.pending.size)
        assertTrue(snapshot.history.single().pending)
        assertNotNull(store.db.getNextCrudTransaction())
        store.reconcile()
        assertEquals(2,store.snapshot().pending.size)
        store.db.close()
    }
    @Test fun failedTransactionCannotLeaveHalfWrittenTodo()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val store=store(context,UUID.randomUUID().toString(),true)
        try {store.db.writeTransaction {tx -> tx.execute("INSERT INTO drafts(id,body,operation_id) VALUES('broken','{}','op')");error("injected crash before commit")}} catch(error:Exception) {
            assertTrue(generateSequence(error as Throwable) {it.cause}.any {it is IllegalStateException && it.message=="injected crash before commit"})
        }
        assertEquals(0L,store.db.get("SELECT count(*) FROM drafts") {it.getLong(0)!!})
        store.db.close()
    }
}
