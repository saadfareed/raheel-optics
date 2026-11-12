package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.SessionPolicy
import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    @Test fun closingTheAppDoesNotExpireSession() {assertFalse(SessionPolicy.expired(1000,61000,30))}
    @Test fun inactivityExpiresAtBoundary() {
        assertFalse(SessionPolicy.expired(1000,30*86400000L+999,30))
        assertTrue(SessionPolicy.expired(1000,30*86400000L+1000,30))
    }
    @Test fun neverExpireAndClockRollbackAreHandled() {
        assertFalse(SessionPolicy.expired(0,Long.MAX_VALUE,0))
        assertFalse(SessionPolicy.expired(10000,1000,7))
    }
}
