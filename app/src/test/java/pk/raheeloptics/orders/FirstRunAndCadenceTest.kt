package pk.raheeloptics.orders

import pk.raheeloptics.orders.core.OnboardingPolicy
import pk.raheeloptics.orders.core.SyncCadence
import org.junit.Assert.*
import org.junit.Test

class FirstRunAndCadenceTest {
    @Test fun introductionIsOnlyForNewInstallations() {
        assertTrue(OnboardingPolicy.shouldShow(false,false))
        assertFalse(OnboardingPolicy.shouldShow(true,false))
        assertFalse(OnboardingPolicy.shouldShow(false,true))
        assertFalse(OnboardingPolicy.shouldShow(true,true))
    }
    @Test fun startupJitterIsBounded() {
        assertEquals(0L,SyncCadence.startupDelayMillis(0.0))
        assertEquals(1500L,SyncCadence.startupDelayMillis(0.5))
        assertEquals(3000L,SyncCadence.startupDelayMillis(1.0))
        assertThrows(IllegalArgumentException::class.java) {SyncCadence.startupDelayMillis(Double.NaN)}
    }
    @Test fun pollingIsSpreadAndRetryDelayIsBounded() {
        assertEquals(120000L,SyncCadence.delayMillis(120,sample=0.0))
        assertEquals(144000L,SyncCadence.delayMillis(120,sample=1.0))
        assertEquals(240000L,SyncCadence.delayMillis(120,1,0.0))
        assertEquals(1080000L,SyncCadence.delayMillis(120,100,1.0))
        assertEquals(3600000L,SyncCadence.delayMillis(3600,sample=0.0))
        assertEquals(120000L,SyncCadence.delayMillis(120,0,0.0))
        assertThrows(IllegalArgumentException::class.java) {SyncCadence.delayMillis(120,sample=Double.NaN)}
    }
}
