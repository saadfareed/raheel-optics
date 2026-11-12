package pk.raheeloptics.orders

import pk.raheeloptics.orders.identity.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AuthRevocationTest {
    private class Sessions(expiredToken: Boolean = false, private val idleExpired: Boolean = false) : AuthSessionStorage {
        var retained: Session? = Session("owner", "owner@example.com", "old", "refresh",
            if (expiredToken) 0 else System.currentTimeMillis()+3_600_000)
        var markedOpened = false
        var failSave = false
        override fun session() = if (idleExpired) null else retained
        override fun sessionForRevocation() = retained
        override fun save(session: Session) { check(!failSave) { "Preference write failed" }; retained=session }
        override fun clear() { retained=null }
        override fun markOpened() { markedOpened=true }
        override fun config() = CloudConfig("https://example.com", "public")
    }
    private class Transport : TokenTransport {
        val calls = mutableListOf<Pair<String, String?>>()
        var rejectFirstLogout = false
        var offline = false
        var refreshedOwner = "owner"
        override suspend fun request(config: CloudConfig, path: String, method: String, body: JsonElement?, token: String?): JsonElement {
            calls += path to token
            if (offline) error("offline")
            if (path.startsWith("/auth/v1/token")) {
                assertEquals("refresh", body!!.jsonObject["refresh_token"]!!.jsonPrimitive.content)
                return Json.parseToJsonElement("""{"user":{"id":"$refreshedOwner","email":"owner@example.com"},"access_token":"new","refresh_token":"rotated","expires_in":3600}""")
            }
            assertEquals("/auth/v1/logout?scope=local", path)
            if (rejectFirstLogout) { rejectFirstLogout=false; throw ApiException(401, "expired") }
            return JsonNull
        }
    }

    @Test fun expiredAccessTokenRefreshesBeforeLocalScopeRevocation() = runTest {
        val sessions=Sessions(expiredToken=true); val transport=Transport()
        AuthClient(sessions, transport).revokeCurrentSession()
        assertEquals(listOf("/auth/v1/token?grant_type=refresh_token" to null, "/auth/v1/logout?scope=local" to "new"), transport.calls)
        assertEquals("rotated", sessions.retained!!.refreshToken)
        assertFalse(sessions.markedOpened)
    }

    @Test fun inactivityExpiredSessionCanBeRevokedWithoutRestoringAppAccess() = runTest {
        val sessions=Sessions(expiredToken=true, idleExpired=true); val transport=Transport()
        AuthClient(sessions, transport).revokeCurrentSession()
        assertNull(sessions.session())
        assertEquals("new", transport.calls.last().second)
        assertFalse(sessions.markedOpened)
    }

    @Test fun rejectedAccessTokenRefreshesAndRetriesOnce() = runTest {
        val sessions=Sessions(); val transport=Transport().apply { rejectFirstLogout=true }
        AuthClient(sessions, transport).revokeCurrentSession()
        assertEquals(listOf("old", null, "new"), transport.calls.map { it.second })
    }

    @Test fun offlineFailureIsReportedToTheCallerForGuaranteedLocalCleanup() = runTest {
        val transport=Transport().apply { offline=true }
        assertTrue(runCatching { AuthClient(Sessions(), transport).revokeCurrentSession() }.isFailure)
    }

    @Test fun localOnlySessionDoesNotContactServer() = runTest {
        val sessions=Sessions().apply { retained=retained!!.copy(localOnly=true) }; val transport=Transport()
        AuthClient(sessions, transport).revokeCurrentSession()
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun aRejectedFreshTokenDoesNotStartAnotherRefreshCycle() = runTest {
        val transport=Transport().apply { rejectFirstLogout=true }
        assertTrue(runCatching { AuthClient(Sessions(expiredToken=true), transport).revokeCurrentSession() }.isFailure)
        assertEquals(2, transport.calls.size)
    }

    @Test fun mismatchedRefreshOwnerCannotChangeOrRevokeAnotherAccount() = runTest {
        val sessions=Sessions(expiredToken=true)
        val transport=Transport().apply { refreshedOwner="other-owner" }
        assertTrue(runCatching { AuthClient(sessions, transport).revokeCurrentSession() }.isFailure)
        assertEquals("owner", sessions.retained!!.userId)
        assertEquals(1, transport.calls.size)
    }

    @Test fun persistenceFailureAfterRefreshStillRevokesReceivedToken() = runTest {
        val sessions=Sessions(expiredToken=true).apply { failSave=true }
        val transport=Transport()
        AuthClient(sessions,transport).revokeCurrentSession()
        assertEquals("/auth/v1/logout?scope=local" to "new", transport.calls.last())
        assertEquals("old", sessions.retained!!.accessToken)
        assertFalse(sessions.markedOpened)
    }
}
