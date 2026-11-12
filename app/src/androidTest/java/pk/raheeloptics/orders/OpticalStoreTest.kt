package pk.raheeloptics.orders

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.core.*
import pk.raheeloptics.orders.data.*
import com.powersync.DatabaseDriverFactory
import com.powersync.PowerSyncDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OpticalStoreTest {
    private val openedDatabases = mutableListOf<PowerSyncDatabase>()
    private val testFiles = mutableSetOf<String>()

    private fun store(account: String = UUID.randomUUID().toString()): OpticalStore {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val filename = "optical-test-$account.db"
        testFiles += filename
        val database = PowerSyncDatabase(DatabaseDriverFactory(context), appSchema, dbFilename = filename)
        openedDatabases += database
        return OpticalStore(database, account)
    }

    @After fun removeTestDatabases() = runBlocking {
        openedDatabases.asReversed().forEach { database -> runCatching { database.close() } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        testFiles.forEach { filename ->
            if (context.getDatabasePath(filename).exists()) assertTrue("Could not remove $filename", context.deleteDatabase(filename))
        }
    }

    private fun customer(mobile: String = "0300 1234567", name: String = "Ayesha Khan") =
        Customer(UUID.randomUUID().toString(), name, mobile, PakistanPhone.normalize(mobile)!!)
    // Queued customer edits live in the optical_drafts overlay, so counts read from there.
    private suspend fun customerCount(store: OpticalStore) = store.db.get("SELECT count(*) FROM optical_drafts WHERE json_extract(body,'$.kind')='customer'") { it.getLong(0)!! }

    @Test fun phoneCollisionSurfacesDistinctOutcome() = runBlocking {
        val store = store()
        val existing = store.upsertCustomer(customer(mobile = "0321 7654321", name = "Original"))
        val clashing = customer(mobile = "0321 7654321", name = "Impostor")
        val error = try { store.upsertCustomer(clashing); null } catch (e: PhoneCollisionException) { e }
        assertNotNull("Expected a PhoneCollisionException", error)
        assertEquals(existing.id, error!!.existingCustomerId)
        assertEquals(clashing.id, error.attemptedCustomerId)
        assertEquals("No silent duplicate", 1L, customerCount(store))
    }
}
