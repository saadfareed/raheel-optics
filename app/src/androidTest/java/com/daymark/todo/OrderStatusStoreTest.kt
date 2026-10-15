package com.daymark.todo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.core.*
import com.daymark.todo.data.*
import com.powersync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OrderStatusStoreTest {
    @Test fun allFourStatusesRemainSearchableAndCountedAfterRestart() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="status-test-${UUID.randomUUID()}.db"
        var db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            var store=TodoStore(db,"status-test",true)
            suspend fun create(name:String):Todo {
                val order=Todo(UUID.randomUUID().toString(),name)
                store.save(order,"create",null)
                return store.todoPage().items.single {it.id==order.id}
            }
            create("Active demo")
            val ready=create("Ready demo")
            store.save(ready.copy(orderStatus=OrderStatus.READY_FOR_PICKUP),"edit",ready)
            store.transition(create("Complete demo"),"complete")
            store.transition(create("Cancel demo"),"delete")
            db.close()
            db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
            store=TodoStore(db,"status-test",true)
            assertEquals(4,store.todoPage().items.size)
            OrderStatus.entries.forEach {status ->
                assertEquals(status,store.todoPage(TodoQuery(orderStatus=status)).items.single().orderStatus)
            }
            val snapshot=store.uiSnapshot(store.todoPage().items,emptyList())
            OrderStatus.entries.forEach {assertEquals(1,snapshot.orderStatusCounts[it])}
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
    }
}
