package com.daymark.todo

import android.content.SharedPreferences
import com.daymark.todo.core.editChecked
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class CheckedPreferencesTest {
    private class Preferences(private val writable: Boolean) {
        val committed = mutableMapOf<String, String?>()
        val pending = mutableMapOf<String, String?>()
        var commits = 0
        var applies = 0
        val editor: SharedPreferences.Editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String?; proxy }
                "remove" -> { pending[args!![0] as String] = null; proxy }
                "commit" -> { commits++; if (writable) committed.putAll(pending); writable }
                "apply" -> { applies++; null }
                else -> error("Unexpected editor call ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs: SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "edit" -> editor
                else -> error("Unexpected preferences call ${method.name}")
            }
        } as SharedPreferences
    }
    @Test fun durableWritesCommitSynchronouslyAndTogether() {
        val storage = Preferences(true)
        storage.prefs.editChecked { putString("policy", "required"); putString("project", "account") }
        assertEquals(mapOf("policy" to "required", "project" to "account"), storage.committed)
        assertEquals(1, storage.commits)
        assertEquals(0, storage.applies)
    }
    @Test fun failedCommitIsReportedRatherThanAcknowledged() {
        val storage = Preferences(false)
        try {
            storage.prefs.editChecked("Disk unavailable") { putString("policy", "required") }
            fail("Failed disk write must throw")
        } catch (error: IllegalStateException) {
            assertEquals("Disk unavailable", error.message)
        }
        assertTrue(storage.committed.isEmpty())
        assertEquals(1, storage.commits)
        assertEquals(0, storage.applies)
    }
}
