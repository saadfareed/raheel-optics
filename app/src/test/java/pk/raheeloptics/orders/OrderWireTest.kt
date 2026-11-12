package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OrderWireTest {
    private val order=Order("a0000000-0000-0000-0000-000000000001","Saad",notes="n",dueAt=9000,reminderAt=8000,customerMobile="0333 4909162",
        normalizedMobile="+923334909162",customerId="c0000000-0000-0000-0000-000000000001",orderNumber="RO-1",
        prescription=Prescription(distanceRight=EyePrescription("-2.00","-0.50",90)),frame=FrameDetails(model="Ray-Ban",localImageUri="file:///p.jpg"),
        lens=LensDetails(material="Blue cut"),amounts=OrderAmounts(framePaisa=450000,lensPaisa=300000),
        payments=listOf(Payment("p1",200000,PaymentMethod.JAZZCASH,1500),Payment("p2",-50000,PaymentMethod.CASH,1600)),orderedAt=1000,updatedAt=2000)

    @Test fun createAndEditMapToApplyWithTheFullLedgerAndNoPhotoPath() {
        val create=OrderWire.applyPayload(Operation("op1",order,"create",0,occurredAt=5))
        assertEquals("order_create",create["action"]!!.jsonPrimitive.content)
        val o=create["order"]!!.jsonObject
        assertEquals("c0000000-0000-0000-0000-000000000001",o["customerId"]!!.jsonPrimitive.content)
        assertEquals(9000L,o["promisedAt"]!!.jsonPrimitive.long)
        assertEquals(-50000L,o["payments"]!!.jsonArray[1].jsonObject["amountPaisa"]!!.jsonPrimitive.long)
        assertNull(o["frame"]!!.jsonObject["localImageUri"])
        val edit=OrderWire.applyPayload(Operation("op2",order,"edit",3))
        assertEquals("order_edit",edit["action"]!!.jsonPrimitive.content);assertEquals(3L,edit["baseRevision"]!!.jsonPrimitive.long)
    }
    @Test fun terminalLocalStatusesMapToServerStatusesAndDropReminders() {
        val done=OrderWire.applyPayload(Operation("op",Rules.transition(order,"complete",7000),"complete",2))["order"]!!.jsonObject
        assertEquals("COMPLETE",done["status"]!!.jsonPrimitive.content);assertEquals(7000L,done["deliveredAt"]!!.jsonPrimitive.long)
        assertTrue(done["reminderAt"] is JsonNull)
        val cancelled=OrderWire.applyPayload(Operation("op",Rules.transition(order,"delete",7000),"delete",2))["order"]!!.jsonObject
        assertEquals("CANCEL",cancelled["status"]!!.jsonPrimitive.content)
    }
    @Test fun feedRowsBecomeFullLocalRecordsKeepingThisDevicesPhoto() {
        val row=buildJsonObject {
            put("id",order.id);put("customerId","c0000000-0000-0000-0000-000000000001");put("orderNumber","RO-1");put("status","COMPLETE")
            put("prescription",buildJsonObject {put("distanceRight",buildJsonObject {put("sphere","-2.00");put("axis",90)})})
            put("frame",buildJsonObject {put("model","Ray-Ban")});put("lens",buildJsonObject {put("material","Blue cut")})
            put("amounts",buildJsonObject {put("framePaisa",450000);put("lensPaisa",300000);put("extrasPaisa",0);put("discountPaisa",0);put("taxPaisa",0)})
            put("orderedAt",1000);put("promisedAt",9000);put("reminderAt",JsonNull);put("deliveredAt",7000);put("notes","n");put("revision",4);put("updatedAt",9999)
            put("payments",buildJsonArray {add(buildJsonObject {put("id","p1");put("amountPaisa",200000);put("method","JAZZCASH");put("receivedAt",1500);put("note","")})
                add(buildJsonObject {put("id","p2");put("amountPaisa",-50000);put("method","CASH");put("receivedAt",1600);put("note","")})})
            put("customer",buildJsonObject {put("id","c0000000-0000-0000-0000-000000000001");put("name","Saad");put("mobile","0333 4909162");put("normalizedMobile","+923334909162")})
        }
        val local=OrderWire.fromServer(row,"file:///p.jpg")
        assertEquals("Saad",local.title);assertEquals("0333 4909162",local.customerMobile);assertEquals("+923334909162",local.normalizedMobile)
        assertEquals(OrderStatus.COMPLETE,local.orderStatus);assertEquals("completed",local.status);assertEquals(7000L,local.completedAt)
        assertEquals(4L,local.revision);assertEquals(9999L,local.updatedAt)
        assertEquals(150000L,local.payments.sumOf {it.amountPaisa});assertEquals(PaymentMethod.JAZZCASH,local.payments[0].method)
        assertEquals("file:///p.jpg",local.frame.localImageUri);assertEquals("Blue cut",local.lens.material)
        assertEquals(-2.0,local.prescription.distanceRight.sphere.toDouble(),0.0)
        // Round trip: what the app sends back is the same order.
        val again=OrderWire.applyPayload(Operation("op",local,"edit",4))["order"]!!.jsonObject
        assertEquals(row["amounts"],again["amounts"]);assertEquals("COMPLETE",again["status"]!!.jsonPrimitive.content)
    }
}
