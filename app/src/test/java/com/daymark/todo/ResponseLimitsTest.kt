package com.daymark.todo

import com.daymark.todo.identity.ResponseLimits
import org.junit.Assert.*
import org.junit.Test

class ResponseLimitsTest {
    @Test fun respectsByteLimitAndUtf8() {
        val text="Task ✓"
        assertEquals(text,ResponseLimits.read(text.byteInputStream(),text.toByteArray().size))
        assertThrows(IllegalStateException::class.java) {ResponseLimits.read(text.byteInputStream(),text.toByteArray().size-1)}
    }
    @Test fun rejectsUnknownLengthOversizedResponses() {
        assertThrows(IllegalStateException::class.java) {ResponseLimits.read(ByteArray(9000).inputStream(),8192)}
    }
    @Test fun metadataAndReceiptResponsesHaveSmallerBudgets() {
        assertEquals(64*1024,ResponseLimits.forPath("/rest/v1/app_update_policy?platform=eq.android"))
        assertEquals(128*1024,ResponseLimits.forPath("/rest/v1/rpc/apply_operations"))
        assertEquals(ResponseLimits.MaxJsonBytes,ResponseLimits.forPath("/rest/v1/rpc/sync_changes"))
    }
}
