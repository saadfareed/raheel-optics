package pk.raheeloptics.orders

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.identity.CloudConfig
import pk.raheeloptics.orders.identity.Session
import pk.raheeloptics.orders.identity.SessionStore
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WorkspaceIdentityTest {
    @Test fun cachedSessionTracksStoredCiphertextAndClearing() {
        val suffix=UUID.randomUUID().toString()
        val context=object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name:String,mode:Int): SharedPreferences =
                super.getSharedPreferences("$name-$suffix",mode)
        }
        val sessions=SessionStore(context)
        val session=Session("account","test@example.com","test-access","test-refresh",Long.MAX_VALUE)
        try {
            sessions.save(session)
            assertEquals(session,sessions.session())
            assertEquals(session,SessionStore(context).session())
            val replacement=session.copy(accessToken="replacement-access")
            SessionStore(context).save(replacement)
            assertEquals(replacement,sessions.session())
            sessions.clear()
            assertNull(sessions.session())
            sessions.save(session)
            context.getSharedPreferences("account",Context.MODE_PRIVATE).edit().putString("session","invalid-ciphertext").commit()
            assertNull(sessions.session())
        } finally {
            context.getSharedPreferences("account",Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test fun tokenRefreshDoesNotExtendAppInactivityAndReauthenticationResetsIt() {
        val suffix=UUID.randomUUID().toString()
        val context=object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name:String,mode:Int): SharedPreferences =super.getSharedPreferences("$name-$suffix",mode)
        }
        val sessions=SessionStore(context)
        val session=Session("account","test@example.com","test-access","test-refresh",Long.MAX_VALUE)
        val preferences=context.getSharedPreferences("account",Context.MODE_PRIVATE)
        try {
            sessions.save(session);sessions.setIdleDays(7)
            preferences.edit().putLong("lastOpened",System.currentTimeMillis()-8*86400000L).commit()
            assertNull(sessions.session())
            sessions.save(session.copy(accessToken="refreshed"));sessions.markOpened()
            assertNull(sessions.session())
            sessions.clear();sessions.save(session);sessions.markOpened()
            assertEquals(session,sessions.session())
        } finally {preferences.edit().clear().commit()}
    }

    @Test fun changingProjectsPreservesOriginalWorkspaceAndSeparatesSameUserId() {
        val suffix = UUID.randomUUID().toString()
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("$name-$suffix", mode)
        }
        val sessions = SessionStore(context)
        val a = CloudConfig("https://a.supabase.co", "sb_publishable_test", "https://sync.example.com")
        val b = a.copy(supabaseUrl="https://b.supabase.co")
        val session = Session("same-user", "", "", "", Long.MAX_VALUE)
        try {
            sessions.configure(a)
            sessions.configure(b) // Also bind legacy storage before its first post-upgrade open.
            val second = sessions.workspaceKey(session)
            assertNotEquals("same-user", second)
            sessions.configure(a)
            assertEquals("same-user", sessions.workspaceKey(session))
            sessions.configure(a.copy(powerSyncUrl=""))
            assertEquals("same-user", sessions.workspaceKey(session))
            sessions.configure(b)
            assertEquals(second, sessions.workspaceKey(session))
            sessions.configure(b.copy(powerSyncUrl=""))
            assertEquals(second, sessions.workspaceKey(session))
            assertEquals("local-device", sessions.workspaceKey(session.copy(localOnly=true)))
        } finally {
            context.getSharedPreferences("account", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun workspaceRegistrySeparatesProjectsAccountsAndRetainsInterruptedErasure() {
        val suffix=UUID.randomUUID().toString()
        val context=object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences=super.getSharedPreferences("$name-$suffix",mode)
        }
        try {
            val sessions=SessionStore(context)
            sessions.configure(CloudConfig("https://first.example.test","public"))
            val first=Session("account-a","","","",Long.MAX_VALUE)
            val second=first.copy(userId="account-b")
            sessions.rememberWorkspace(first,"orders-known-a.db")
            sessions.rememberWorkspace(second,"orders-known-b.db")
            val owned=sessions.retainedWorkspaces(first)
            assertTrue("orders-known-a.db" in owned);assertFalse("orders-known-b.db" in owned)
            sessions.markForErasure(owned)
            assertEquals(owned,SessionStore(context).pendingErasures())
            sessions.configure(CloudConfig("https://second.example.test","public"))
            assertFalse("orders-known-a.db" in sessions.retainedWorkspaces(first))
            owned.forEach(sessions::finishErasure)
            assertTrue(SessionStore(context).pendingErasures().isEmpty())
        } finally {context.getSharedPreferences("account",Context.MODE_PRIVATE).edit().clear().commit()}
    }

}
