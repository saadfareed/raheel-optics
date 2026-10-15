package com.daymark.todo

import com.daymark.todo.identity.CloudConfig
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class CloudConfigTest {
    @Test fun publicHttpsConfigurationAccepted() {CloudConfig("https://project.supabase.co","sb_publishable_public","https://sync.example.com").validate()}
    @Test fun invalidOrCredentialBearingEndpointsRejected() {
        for(url in listOf("http://project.example.com","https://","https://user:password@example.com","https://example.com?token=secret")) {
            assertThrows(IllegalArgumentException::class.java) {CloudConfig(url,"public","https://sync.example.com").validate()}
        }
    }
    @Test fun secretAndServiceRoleKeysRejected() {
        val role=Base64.getUrlEncoder().withoutPadding().encodeToString("{\"role\":\"service_role\"}".toByteArray())
        for(key in listOf("sb_secret_secret","header.$role.signature")) assertThrows(IllegalArgumentException::class.java) {CloudConfig("https://project.example.com",key,"https://sync.example.com").validate()}
    }
}
