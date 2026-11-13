package pk.raheeloptics.orders

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.data.*
import com.powersync.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OpticalRepairStoreTest {
    @Test fun olderCloudCannotAcknowledgeAnOrderAndDropItsOpticalFields()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="optical-guard-${UUID.randomUUID()}.db"
        val db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            val store=OrderStore(db,"guard",false)
            store.save(Order(UUID.randomUUID().toString(),"Guard demo",orderNumber="RO-GUARD"),"create",null)
            var mutations=0
            val backend=object:pk.raheeloptics.orders.identity.BackendTransport {
                override suspend fun accountId()="guard"
                override suspend fun request(path:String,method:String,body:kotlinx.serialization.json.JsonElement?,authenticated:Boolean):kotlinx.serialization.json.JsonElement {
                    if(path.endsWith("optical_orders_api_version")) throw pk.raheeloptics.orders.identity.ApiException(404,"Missing migration")
                    mutations++;error("An order reached a server without the order API")
                }
            }
            try {pk.raheeloptics.orders.sync.SupabaseSynchronizer(store,backend).exchange();fail("Expected sync guard")}
            catch(e:IllegalStateException) {assertTrue(e.message!!.contains("latest server update"))}
            assertEquals(0,mutations)
            assertNotNull(db.getCrudBatch(1))
        } finally {db.close();context.deleteDatabase(filename)}
    }
    @Test fun filtersAnalyticsExportsAndLatePaymentsUseAllOrders()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="optical-repairs-${UUID.randomUUID()}.db"
        val db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            val store=OrderStore(db,"repairs",true)
            repeat(30) {index ->
                val order=Order(UUID.randomUUID().toString(),"Customer ${index.toString().padStart(2,'0')}",customerMobile="03001234567",normalizedMobile="+923001234567",orderedAt=100L+index,
                    amounts=OrderAmounts(framePaisa=1000L+index))
                store.save(order,"create",null)
            }
            val first=store.todoPage(OrderQuery(sort=OrderSort.CustomerName))
            val second=store.todoPage(OrderQuery(sort=OrderSort.CustomerName),first.next)
            assertEquals(ListRules.PageSize,first.items.size);assertEquals(ListRules.PageSize,second.items.size)
            assertEquals("Customer 00",first.items.first().title)
            assertEquals("Customer ${ListRules.PageSize}",second.items.first().title)
            assertEquals("Customer 29",store.todoPage(OrderQuery(sort=OrderSort.Balance)).items.first().title)
            val original=first.items.first()
            store.transition(original,"complete")
            val completed=store.orderById(original.id)!!
            store.save(completed.copy(payments=listOf(Payment("payment",500,receivedAt=150))),"edit",completed)
            val paid=store.orderById(original.id)!!
            assertEquals(500L,paid.payments.sumOf {it.amountPaisa})
            assertEquals(OrderStatus.COMPLETE,paid.orderStatus)
            assertEquals(1,store.todoPage(OrderQuery(payment=PaymentFilter.PARTIAL)).items.size)
            assertEquals(5,store.todoPage(OrderQuery(orderedFrom=125,orderedUntil=130)).items.size)
            val stats=store.orderAnalytics(OrderAnalyticsQuery(OrderAnalyticsPeriod.CUSTOM,java.time.LocalDate.of(1970,1,1),java.time.LocalDate.of(1970,1,2)))
            assertEquals(30,stats.orders);assertEquals(500L,stats.collected)
            val export=StringBuilder();store.exportTo(export,OpticalExportSelection.ACTIVE_ORDERS)
            val preview=TaskImport.preview(export.toString().byteInputStream())
            assertEquals(29,preview.activeTasks.size)
            val directory=store.customerDirectory(CustomerDirectoryQuery())
            assertEquals(1,directory.size);assertEquals(30,directory.single().orderCount)
        } finally {db.close();context.deleteDatabase(filename)}
    }
}
