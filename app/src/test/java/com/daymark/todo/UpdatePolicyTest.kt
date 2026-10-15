package com.daymark.todo

import com.daymark.todo.updates.UpdateKind
import com.daymark.todo.updates.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatePolicyTest {
    private val policy = UpdatePolicy(10, 5, "2.0", "https://example.com/daymark.apk")
    @Test fun playInstallationsUpdateThroughTheirOwnPlayListing() {
        assertEquals("https://play.google.com/store/apps/details?id=com.daymark.todo",policy.destination("com.android.vending","com.daymark.todo"))
    }
    @Test fun sideloadedInstallationsRetainTheirConfiguredUpdateLink() {
        assertEquals(policy.update_url,policy.destination(null,"com.daymark.todo"))
        assertEquals(policy.update_url,policy.destination("com.google.android.packageinstaller","com.daymark.todo"))
    }
    @Test fun requiredAndOptionalBoundaries() {
        assertEquals(UpdateKind.HARD, policy.kind(4))
        assertEquals(UpdateKind.SOFT, policy.kind(5))
        assertEquals(UpdateKind.SOFT, policy.kind(9))
        assertEquals(UpdateKind.NONE, policy.kind(10))
        assertEquals(UpdateKind.NONE, policy.kind(11))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsMinimumAboveLatest() {
        policy.copy(minimum_version_code = 11).validate()
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInsecureDownload() {
        policy.copy(update_url = "http://example.com/app.apk").validate()
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUrlCredentials() {
        policy.copy(update_url = "https://user@example.com/app.apk").validate()
    }
}
