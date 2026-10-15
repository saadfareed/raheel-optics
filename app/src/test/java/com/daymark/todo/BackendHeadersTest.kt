package com.daymark.todo

import com.daymark.todo.identity.backendHeaders
import org.junit.Assert.*
import org.junit.Test

class BackendHeadersTest {
    @Test fun publicPolicyRequestWithPublishableKeyDoesNotSendInvalidBearer() {
        val headers = backendHeaders("sb_publishable_example")
        assertEquals("sb_publishable_example", headers["apikey"])
        assertFalse(headers.containsKey("Authorization"))
    }
    @Test fun legacyAnonJwtRemainsSupportedBeforeLogin() {
        val headers = backendHeaders("header.anon.signature")
        assertEquals("Bearer header.anon.signature", headers["Authorization"])
    }
    @Test fun authenticatedRequestUsesUserJwtAndKeepsAppKey() {
        val headers = backendHeaders("sb_publishable_example", "header.user.signature")
        assertEquals("sb_publishable_example", headers["apikey"])
        assertEquals("Bearer header.user.signature", headers["Authorization"])
    }
}
