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
class CustomerDirectoryStoreTest {
    @Test fun directoryIncludesFirstActiveOrderAndAggregatesAcrossOrderPages()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="customer-directory-${UUID.randomUUID()}.db"
        val db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            val store=OrderStore(db,"directory-test",true)
            repeat(28) {index ->
                val order=Order(UUID.randomUUID().toString(),"Ayesha",customerMobile="03001234567",normalizedMobile="+923001234567",orderedAt=100L+index,
                    amounts=OrderAmounts(framePaisa=1000),prescription=Prescription(distanceRight=EyePrescription(sphere="-${index}.00")))
                store.save(order,"create",null)
            }
            store.save(Order(UUID.randomUUID().toString(),"Zain",customerMobile="03011234567",normalizedMobile="+923011234567",orderedAt=200),"create",null)
            val rows=store.customerDirectory(CustomerDirectoryQuery(sort=CustomerSort.NAME_ASC))
            assertEquals(2,rows.size);assertEquals("Ayesha",rows.first().latestOrder.title)
            assertEquals(28,rows.first().orderCount);assertEquals(28000L,rows.first().balancePaisa)
            assertEquals("-27.00",rows.first().latestOrder.prescription.distanceRight.sphere)
            assertEquals(1,store.customerDirectory(CustomerDirectoryQuery(search="0300 123-4567")).size)
            assertEquals(1,store.customerDirectory(CustomerDirectoryQuery(search="+92 (300) 1234567")).size)
            assertEquals("Zain",store.customerDirectory(CustomerDirectoryQuery(sort=CustomerSort.NAME_DESC)).first().latestOrder.title)
            assertTrue(store.customerDirectory(CustomerDirectoryQuery(search="%_")).isEmpty())
        } finally {db.close();context.deleteDatabase(filename)}
    }
}
