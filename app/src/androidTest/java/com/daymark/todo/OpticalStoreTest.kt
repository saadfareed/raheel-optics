package com.daymark.todo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.core.*
import com.daymark.todo.data.*
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

    private fun order(customer: Customer, number: String = "A-1", amounts: OrderAmounts = OrderAmounts(framePaisa = 500000, lensPaisa = 300000), prescription: Prescription = Prescription()) =
        OpticalOrder(UUID.randomUUID().toString(), number, customer, prescription = prescription, amounts = amounts)

    // Synced optical_customers/optical_orders stay empty in Task 2 (the sync task fills them later); the client's
    // queued work lives in the optical_drafts overlay, so counts read from there. Events are synced bookkeeping rows.
    private suspend fun customerCount(store: OpticalStore) = store.db.get("SELECT count(*) FROM optical_drafts WHERE json_extract(body,'$.kind')='customer'") { it.getLong(0)!! }
    private suspend fun orderCount(store: OpticalStore) = store.db.get("SELECT count(*) FROM optical_drafts WHERE json_extract(body,'$.kind')='order'") { it.getLong(0)!! }
    private suspend fun eventCount(store: OpticalStore) = store.db.get("SELECT count(*) FROM optical_order_events") { it.getLong(0)!! }
    private suspend fun journalCount(store: OpticalStore) = store.db.get("SELECT count(*) FROM optical_journal") { it.getLong(0)!! }

    @Test fun orderCreateWritesOneCustomerAndOneOrderInOneTransaction() = runBlocking {
        val store = store()
        val created = store.createOrder(order(customer()))
        assertEquals(1L, customerCount(store))
        assertEquals(1L, orderCount(store))
        assertEquals(1L, eventCount(store))
        val snapshot = store.snapshot()
        assertEquals(1, snapshot.customers.size)
        assertEquals(1, snapshot.orders.size)
        assertEquals(created.id, snapshot.orders.single().id)
    }

    @Test fun knownNumberReusesExistingCustomerId() = runBlocking {
        val store = store()
        val first = store.createOrder(order(customer(mobile = "0300 1111111"), number = "A-1"))
        val reusedCustomer = customer(mobile = "0300 1111111", name = "Different Name Entered")
        val second = store.createOrder(order(reusedCustomer, number = "A-2"))
        assertEquals("Second order must reuse the first customer id", first.customer.id, second.customer.id)
        assertNotEquals(reusedCustomer.id, second.customer.id)
        assertEquals("No duplicate customer row", 1L, customerCount(store))
        assertEquals(2L, orderCount(store))
    }

    @Test fun replayingSameOperationIdIsANoOp() = runBlocking {
        val store = store()
        val c = customer().copy(revision = 1)
        val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.CustomerUpsert(c), OpticalActions.CUSTOMER_UPSERT, baseRevision = 0)
        store.apply(op)
        store.apply(op)
        assertEquals(1L, customerCount(store))
        assertEquals("Replaying the same op id must not re-queue it", 1L, journalCount(store))
    }

    @Test fun offlineCreateThenReplayProducesNoDuplicates() = runBlocking {
        val store = store()
        // Simulate an offline create captured as an op, then replayed (e.g. after a crash/resync).
        val stored = order(customer().copy(revision = 1), number = "A-7").copy(revision = 1)
        val op = OpticalOperation(UUID.randomUUID().toString(), OpticalPayload.OrderCreate(stored), OpticalActions.ORDER_CREATE, baseRevision = 0)
        store.apply(op)
        store.apply(op)
        assertEquals(1L, orderCount(store))
        assertEquals(1L, eventCount(store))
        assertEquals(1L, journalCount(store))
    }

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

    @Test fun paymentLedgerAccumulatesAndBalanceIsCorrect() = runBlocking {
        val store = store()
        val created = store.createOrder(order(customer(), amounts = OrderAmounts(framePaisa = 1000000)))
        val afterFirst = store.addPayment(created.id, Payment(UUID.randomUUID().toString(), 400000))
        val afterSecond = store.addPayment(created.id, Payment(UUID.randomUUID().toString(), 300000))
        assertEquals(2, afterSecond.payments.size)
        assertEquals(700000L, afterSecond.paidPaisa)
        assertEquals(300000L, afterSecond.balancePaisa)
        assertEquals(2L, store.db.get("SELECT count(*) FROM optical_payments") { it.getLong(0)!! })

        val overpay = try { store.addPayment(created.id, Payment(UUID.randomUUID().toString(), 400000)); null } catch (e: IllegalArgumentException) { e }
        assertNotNull("Payment exceeding total must be rejected", overpay)

        val afterRefund = store.refundPayment(created.id, Payment(UUID.randomUUID().toString(), 200000))
        assertEquals(500000L, afterRefund.paidPaisa)
        assertEquals(500000L, afterRefund.balancePaisa)
        assertEquals(3, afterRefund.payments.size)
    }

    @Test fun snapshotOverlayShowsQueuedOrderWithBalanceAndCustomerLinkage() = runBlocking {
        val store = store()
        val created = store.createOrder(order(customer(name = "Linked Customer"), amounts = OrderAmounts(framePaisa = 800000)))
        store.addPayment(created.id, Payment(UUID.randomUUID().toString(), 300000))
        val snapshot = store.snapshot()
        val order = snapshot.orders.single()
        assertEquals(500000L, order.balancePaisa)
        assertEquals(created.customer.id, order.customer.id)
        assertTrue(snapshot.customers.any { it.id == order.customer.id && it.name == "Linked Customer" })
    }

    @Test fun editingCustomerLeavesPriorOrderPrescriptionSnapshotUnchanged() = runBlocking {
        val store = store()
        val priorPrescription = Prescription(distanceRight = EyePrescription(sphere = "-1.25"), prescribedBy = "Dr A")
        val created = store.createOrder(order(customer(mobile = "0345 1112233"), prescription = priorPrescription))
        // Edit the shared customer with a newer identity/contact detail.
        val reusedCustomer = created.customer.copy(name = "Renamed", notes = "updated contact")
        store.upsertCustomer(reusedCustomer)
        val snapshot = store.snapshot()
        val priorOrder = snapshot.orders.single { it.id == created.id }
        assertEquals("Prior order keeps its own prescription snapshot", "-1.25", priorOrder.prescription.distanceRight.sphere)
        assertEquals("Dr A", priorOrder.prescription.prescribedBy)
        assertEquals("Customer edit is visible", "Renamed", snapshot.customers.single().name)
    }
}
