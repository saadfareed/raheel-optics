package com.daymark.todo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.core.*
import com.daymark.todo.data.*
import com.daymark.todo.identity.*
import com.daymark.todo.sync.SupabaseSynchronizer
import com.powersync.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SharedCustomerTest {
    private fun order(name:String="Ayesha",phone:String="03001234567")=Todo(UUID.randomUUID().toString(),name,
        customerMobile=phone,normalizedMobile=PakistanPhone.normalize(phone)!!,
        prescription=Prescription(distanceRight=EyePrescription(sphere="-2.00")))

    @Test fun profileEditsAndRepeatOrderShareIdentityWithoutChangingPriorPrescription()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="shared-profile-${UUID.randomUUID()}.db"
        var db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            var store=TodoStore(db,"guest-profile",true)
            store.save(order(),"create",null)
            var row=store.customerDirectory(CustomerDirectoryQuery()).single()
            val first=store.todoPage().items.single()
            val edited=row.customer!!.copy(name="Ayesha Khan",mobile="03011234567",normalizedMobile="+923011234567",
                latestPrescription=Prescription(distanceRight=EyePrescription(sphere="-3.00")))
            OpticalStore(db,store.accountId,localOnly=true).upsertCustomer(edited)
            row=store.customerDirectory(CustomerDirectoryQuery()).single()
            assertEquals("Ayesha Khan",row.latestOrder.title)
            assertEquals("+923011234567",row.latestOrder.normalizedMobile)
            assertEquals("-3.00",row.latestOrder.prescription.distanceRight.sphere)
            assertEquals("-2.00",store.todoPage().items.single().prescription.distanceRight.sphere)
            val repeated=RepeatOrder.draft(row.latestOrder).copy(title="Ayesha Updated",prescription=Prescription(distanceRight=EyePrescription(sphere="-4.00")))
            store.save(repeated,"create",null)
            row=store.customerDirectory(CustomerDirectoryQuery()).single()
            assertEquals(2,row.orderCount);assertEquals(first.customerId,row.customer!!.id)
            assertEquals("Ayesha Updated",row.customer!!.name)
            assertEquals("-4.00",row.customer!!.latestPrescription!!.distanceRight.sphere)
            assertEquals("-2.00",store.todoPage().items.single {it.id==first.id}.prescription.distanceRight.sphere)
            store.save(order("Zain","03021234567"),"create",null)
            val zain=store.customerDirectory(CustomerDirectoryQuery(search="Zain")).single().customer!!
            try {
                OpticalStore(db,store.accountId,localOnly=true).upsertCustomer(zain.copy(mobile="03011234567",normalizedMobile="+923011234567"))
                fail("Expected phone collision")
            } catch(_:PhoneCollisionException) {}
            assertEquals(2,store.customerDirectory(CustomerDirectoryQuery()).size)
            // Profiles are independent of local history cleanup and survive restart.
            store.transition(store.todoPage().items.single {it.id==first.id},"complete")
            store.transition(store.todoPage().items.single {it.id==repeated.id},"complete")
            store.clearLocalHistory()
            db.close();db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
            store=TodoStore(db,"guest-profile",true)
            assertEquals(2,store.customerDirectory(CustomerDirectoryQuery()).size)
            assertEquals("Ayesha Updated",store.customerDirectory(CustomerDirectoryQuery(search="Ayesha")).single().customer!!.name)
            assertEquals(0,store.customerDirectory(CustomerDirectoryQuery(search="Ayesha")).single().orderCount)
        } finally {db.close();context.deleteDatabase(filename)}
    }

    @Test fun typedCustomerOperationUsesCustomerEndpointAndMissingMigrationDoesNotQuarantineIt()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="profile-sync-${UUID.randomUUID()}.db"
        val db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            val account=UUID.randomUUID().toString()
            val store=TodoStore(db,account,false)
            store.save(order(),"create",null)
            val customer=store.customerDirectory(CustomerDirectoryQuery()).single().customer!!
            val calls=mutableListOf<String>()
            var missing=true
            val transport=object:BackendTransport {
                override suspend fun accountId()=account
                override suspend fun request(path:String,method:String,body:JsonElement?,authenticated:Boolean):JsonElement {
                    calls+=path
                    check(path=="/rest/v1/rpc/upsert_optical_customer")
                    if(missing) throw ApiException(404,"Migration not installed")
                    return buildJsonObject {put("accepted",true);put("customer",Json.encodeToJsonElement(customer))}
                }
            }
            val sync=SupabaseSynchronizer(store,transport)
            try {sync.exchange();fail("Expected missing migration")} catch(_:ApiException) {}
            assertEquals(0L,db.get("SELECT count(*) FROM quarantined_changes") {it.getLong(0)!!})
            assertNotNull(db.getCrudBatch(1))
            missing=false
            assertTrue(sync.exchange())
            assertEquals(2,calls.size)
            assertEquals(1,store.customerDirectory(CustomerDirectoryQuery()).size)
            assertEquals(1L,db.get("SELECT count(*) FROM optical_customer_cache") {it.getLong(0)!!})
        } finally {db.close();context.deleteDatabase(filename)}
    }

    @Test fun rejectedProfileCanBeRebasedOrReplacedWithoutUploadingSupersededChanges()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val filename="profile-conflict-${UUID.randomUUID()}.db"
        val db=PowerSyncDatabase(DatabaseDriverFactory(context),appSchema,dbFilename=filename)
        try {
            val account=UUID.randomUUID().toString()
            val store=TodoStore(db,account,false)
            store.save(order(),"create",null)
            val profiles=OpticalStore(db,account)
            val original=store.customerDirectory(CustomerDirectoryQuery()).single().customer!!
            val remote=original.copy(name="Cloud name",revision=1)
            profiles.cacheCustomers(listOf(remote))
            val firstOp=db.get("SELECT payload FROM optical_journal LIMIT 1") {Json.decodeFromString<OpticalOperation>(it.getString(0)!!)}
            profiles.recordResult(firstOp,false,0,"Stale revision")
            profiles.retryCustomerChanges(original.copy(name="Reviewed local name"))
            assertEquals("Reviewed local name",store.customerDirectory(CustomerDirectoryQuery()).single().customer!!.name)
            assertEquals(0L,db.get("SELECT count(*) FROM optical_journal WHERE state='conflict'") {it.getLong(0)!!})
            val transport=object:BackendTransport {
                override suspend fun accountId()=account
                override suspend fun request(path:String,method:String,body:JsonElement?,authenticated:Boolean):JsonElement=error("Superseded profile must not upload")
            }
            assertTrue(SupabaseSynchronizer(store,transport).exchange())
            val latest=db.get("SELECT payload FROM optical_journal WHERE state='queued' LIMIT 1") {Json.decodeFromString<OpticalOperation>(it.getString(0)!!)}
            profiles.recordResult(latest,false,1,"Changed again")
            profiles.resolveCustomerConflict(original.id,false)
            assertEquals("Cloud name",store.customerDirectory(CustomerDirectoryQuery()).single().customer!!.name)
            assertEquals("-2.00",store.todoPage().items.single().prescription.distanceRight.sphere)
        } finally {db.close();context.deleteDatabase(filename)}
    }
}
