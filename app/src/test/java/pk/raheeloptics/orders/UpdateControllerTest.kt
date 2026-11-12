package pk.raheeloptics.orders

import pk.raheeloptics.orders.identity.BackendTransport
import pk.raheeloptics.orders.updates.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UpdateControllerTest {
    private class MemoryStorage : UpdateStorage {
        var cached: String? = null
        var dismissed = 0
        var failWrites = false
        override fun policy() = cached
        override fun savePolicy(value: String?) {
            check(!failWrites) { "Disk full" }
            cached = value
        }
        override fun dismissedVersion() = dismissed
        override fun dismiss(version: Int) { dismissed = version }
    }
    private class Backend : BackendTransport {
        var response: JsonElement = JsonArray(emptyList())
        var offline = false
        var cancelled = false
        override suspend fun accountId() = error("Update checks must not require login")
        override suspend fun request(path: String, method: String, body: JsonElement?, authenticated: Boolean): JsonElement {
            assertFalse(authenticated)
            assertEquals("GET", method)
            if (cancelled) throw CancellationException("Backgrounded")
            if (offline) error("Offline")
            return response
        }
        fun release(latest: Int, minimum: Int) {
            response = Json.parseToJsonElement("""[{"latest_version_code":$latest,"minimum_version_code":$minimum,"version_name":"2.0","update_url":"https://example.com/app.apk"}]""")
        }
    }
    @Test fun hardUpdateSurvivesOfflineRestartAndCannotBeDismissed() = runTest {
        val storage = MemoryStorage()
        val backend = Backend().apply { release(10, 5) }
        val controller = UpdateController(storage, backend, 3)
        controller.check()
        controller.dismiss()
        assertEquals(0, storage.dismissed)
        backend.offline = true
        val restarted = UpdateController(storage, backend, 3)
        restarted.check()
        assertEquals(UpdateKind.HARD, restarted.policy.value!!.kind(3))
        assertNotNull(restarted.error.value)
        assertTrue(restarted.checked.value)
        assertFalse(restarted.checking.value)
    }
    @Test fun optionalDismissalSurvivesRestartAndNewReleasePromptsAgain() = runTest {
        val storage = MemoryStorage()
        val backend = Backend().apply { release(10, 3) }
        val controller = UpdateController(storage, backend, 3)
        controller.check()
        controller.dismiss()
        val restarted = UpdateController(storage, backend, 3)
        assertEquals(10, restarted.dismissedVersion.value)
        backend.release(11, 3)
        restarted.check()
        assertNotEquals(restarted.dismissedVersion.value, restarted.policy.value!!.latest_version_code)
    }
    @Test fun successfulRevocationClearsCachedHardUpdate() = runTest {
        val storage = MemoryStorage()
        val backend = Backend().apply { release(10, 5) }
        val controller = UpdateController(storage, backend, 3)
        controller.check()
        backend.response = JsonArray(emptyList())
        controller.check()
        assertNull(controller.policy.value)
        assertNull(storage.cached)
    }
    @Test fun invalidPolicyRetainsPreviousRequiredUpdate() = runTest {
        val storage = MemoryStorage()
        val backend = Backend().apply { release(10, 5) }
        val controller = UpdateController(storage, backend, 3)
        controller.check()
        backend.release(2, 5)
        controller.check()
        assertEquals(10, controller.policy.value!!.latest_version_code)
        assertNotNull(controller.error.value)
    }
    @Test fun firstLaunchOfflineAllowsExistingOfflineFeatures() = runTest {
        val controller = UpdateController(MemoryStorage(), Backend().apply { offline = true }, 3)
        controller.check()
        assertTrue(controller.checked.value)
        assertNull(controller.policy.value)
    }
    @Test fun installingRequiredVersionRemovesHardBlockFromCachedPolicy() = runTest {
        val storage = MemoryStorage()
        val backend = Backend().apply { release(10, 5) }
        UpdateController(storage, backend, 3).check()
        assertEquals(UpdateKind.NONE, UpdateController(storage, backend, 10).policy.value!!.kind(10))
    }
    @Test fun newlyReceivedRequiredUpdateIsEnforcedWhenDiskWriteFails() = runTest {
        val storage = MemoryStorage().apply { failWrites = true }
        val controller = UpdateController(storage, Backend().apply { release(10, 5) }, 3)
        controller.check()
        assertEquals(UpdateKind.HARD, controller.policy.value!!.kind(3))
        assertTrue(controller.checked.value)
        assertNotNull(controller.error.value)
        assertNull(storage.cached)
    }
    @Test fun cancellingInitialCheckDoesNotUnlockAppAndNextCheckRecovers() = runTest {
        val backend = Backend().apply { cancelled = true }
        val controller = UpdateController(MemoryStorage(), backend, 3)
        try {
            controller.check()
            fail("Cancellation should propagate")
        } catch (_: CancellationException) { }
        assertFalse(controller.checked.value)
        assertFalse(controller.checking.value)
        backend.cancelled = false
        backend.release(10, 5)
        controller.check()
        assertTrue(controller.checked.value)
        assertEquals(UpdateKind.HARD, controller.policy.value!!.kind(3))
    }
    @Test fun malformedResponseCannotRevokeKnownHardRequirement() = runTest {
        val backend = Backend().apply { release(10, 5) }
        val controller = UpdateController(MemoryStorage(), backend, 3)
        controller.check()
        backend.response = JsonObject(emptyMap())
        controller.check()
        assertEquals(UpdateKind.HARD, controller.policy.value!!.kind(3))
        assertNotNull(controller.error.value)
    }

}
