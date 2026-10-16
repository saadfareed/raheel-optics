package com.daymark.todo

import com.daymark.todo.core.*
import org.junit.Assert.*
import org.junit.Test

class RepeatOrderTest {
    @Test fun repeatCopiesContactAndPrescriptionButClearsOrderSpecificData() {
        val source=Todo("old","Ayesha",customerMobile="03001234567",normalizedMobile="+923001234567",
            prescription=Prescription(distanceRight=EyePrescription(sphere="-2.00")),
            status="completed",orderStatus=OrderStatus.COMPLETE,revision=9,orderedAt=1,
            dueAt=100,reminderAt=90,completedAt=101,deliveredAt=101,
            frame=FrameDetails(localImageUri="old-image"),amounts=OrderAmounts(framePaisa=500),payments=listOf(Payment("p",500)))
        val draft=RepeatOrder.draft(source,200)
        assertNotEquals(source.id,draft.id)
        assertEquals(source.title,draft.title);assertEquals(source.normalizedMobile,draft.normalizedMobile)
        assertEquals(source.prescription,draft.prescription)
        assertEquals(OrderStatus.ACTIVE,draft.orderStatus);assertEquals("active",draft.status)
        assertEquals(0L,draft.revision);assertEquals(200L,draft.orderedAt)
        assertEquals(FrameDetails(),draft.frame);assertEquals(OrderAmounts(),draft.amounts);assertTrue(draft.payments.isEmpty())
        assertNull(draft.dueAt);assertNull(draft.reminderAt);assertNull(draft.completedAt);assertNull(draft.deliveredAt)
        assertNotEquals(draft.orderNumber,RepeatOrder.draft(source,200).orderNumber)
    }
}
