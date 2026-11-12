package pk.raheeloptics.orders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import pk.raheeloptics.orders.core.Order
import pk.raheeloptics.orders.identity.*
import pk.raheeloptics.orders.ui.DaymarkScreen
import pk.raheeloptics.orders.ui.DaymarkTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Opt-in live QA, exclusively in the isolated package with a disposable account. */
class SignedInReplayTest {
    @get:Rule val compose=createComposeRule()

    @Test fun signedInOfflineQueueReplaysOneHundredTasksInOneRequest()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val fixture=File(context.filesDir,"qa-credentials.json")
        assumeTrue("Requires isolated QA credentials",context.packageName.endsWith(".qa") && fixture.exists())
        val credentials=Json.parseToJsonElement(fixture.readText()).jsonObject
        fixture.delete()
        val app=context.applicationContext as DaymarkApp
        val originalAuth=app.auth
        val offline=AtomicBoolean(false)
        val sent=AtomicInteger(0)
        val blocked=AtomicInteger(0)
        val logins=AtomicInteger(0)
        val network=HttpTokenTransport()
        val transport=TokenTransport {config,path,method,body,token ->
            if(offline.get()) {blocked.incrementAndGet();throw IOException("QA simulated offline")}
            if(path=="/rest/v1/rpc/sync_exchange") sent.incrementAndGet()
            if(path.startsWith("/auth/v1/token?grant_type=password")) logins.incrementAndGet()
            network.request(config,path,method,body,token)
        }
        fun capture(name:String) {
            compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap ->
                File(context.cacheDir,"signed-in-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            }
        }
        try {
            app.logout();app.sessions.finishGuestTransfer();app.onboarding.complete();app.auth=AuthClient(app.sessions,transport)
            compose.setContent {DaymarkTheme {DaymarkScreen(null,{})}}
            compose.waitUntil(15000) {compose.onAllNodesWithText("Email").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Email").performTextInput(credentials["email"]!!.jsonPrimitive.content)
            compose.onNodeWithText("Password").performTextInput(credentials["password"]!!.jsonPrimitive.content)
            compose.onAllNodesWithText("Sign in").onLast().performScrollTo().performClick()
            compose.waitUntil(20000) {compose.onAllNodesWithContentDescription("Add todo").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(credentials["id"]!!.jsonPrimitive.content,app.sessions.session()!!.userId)
            app.connect()
            assertFalse(app.hasSyncWork())
            offline.set(true)
            repeat(3) {index ->
                compose.onNodeWithContentDescription("Add todo").performClick()
                compose.onNodeWithText("Todo title").performTextInput("Offline UI task $index")
                compose.onNodeWithText("Save todo").performScrollTo().performClick()
                compose.waitUntil(10000) {compose.onAllNodesWithText("New task").fetchSemanticsNodes().isEmpty()}
            }
            val store=app.workspace()!!
            repeat(97) {store.save(Order(UUID.randomUUID().toString(),"Offline queued task $it"),"create",null)}
            WorkManager.getInstance(context).cancelUniqueWork("todo-sync").result.get()
            try {app.connect();fail("Offline sync should fail")} catch(_:IOException) {}
            assertEquals(100,store.storageStats().queuedOperations)
            capture("offline")
            val before=sent.get()
            offline.set(false)
            app.connect()
            assertEquals(1,sent.get()-before)
            assertEquals(0,store.storageStats().queuedOperations)
            assertEquals(100,store.storageStats().activeTasks)
            app.connect();assertEquals(1,sent.get()-before)
            compose.waitUntil(10000) {compose.onAllNodesWithText("All changes synced").fetchSemanticsNodes().isNotEmpty()}
            capture("online")
            // Exercise a real asynchronous save rejection while the editor is open.
            val previousPolicy=store.db.get("SELECT body FROM resource_limits WHERE id='policy'") {it.getString(0)!!}
            store.db.execute("UPDATE resource_limits SET body=? WHERE id='policy'",listOf("{\"version\":1,\"taskScope\":\"active\",\"maxActiveTasks\":100}"))
            compose.onNodeWithContentDescription("Add todo").performClick()
            compose.onNodeWithText("Todo title").performTextInput("Retained after failed save")
            compose.onNodeWithText("Save todo").performScrollTo().performClick()
            compose.waitUntil(10000) {compose.onAllNodes(hasText("limit",substring=true)).fetchSemanticsNodes().isNotEmpty()}
            compose.onAllNodes(hasText("limit",substring=true)).onLast().assertIsDisplayed()
            compose.onNodeWithText("Retained after failed save").assertExists()
            capture("save-error")
            compose.onNodeWithContentDescription("Close task editor").performClick()
            compose.waitUntil(10000) {compose.onAllNodesWithText("New task").fetchSemanticsNodes().isEmpty()}
            store.db.execute("UPDATE resource_limits SET body=? WHERE id='policy'",listOf(previousPolicy))
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manage storage"))
            compose.onNodeWithText("Manage storage").performClick()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Clear history cache"))
            compose.onNodeWithText("Remove downloaded history from this device.").assertIsDisplayed()
            capture("storage")
            File(context.cacheDir,"signed-in-replay.json").writeText(buildJsonObject {
                put("signedInThroughUi",true);put("failedSaveShownInsideEditor",true);put("offlineTasks",100);put("createdThroughUi",3)
                put("syncRequestsOnReconnect",sent.get()-before);put("idleSyncRequests",0)
                put("passwordSignInRequests",logins.get());put("blockedOfflineTransportAttempts",blocked.get())
                put("networkMode","Transport disconnected for isolated QA app; phone network unchanged")
                put("activeTasksAfterSync",store.storageStats().activeTasks)
            }.toString())
        } finally {
            offline.set(false)
            WorkManager.getInstance(context).cancelUniqueWork("todo-sync").result.get()
            app.logout();app.auth=originalAuth
        }
    }
}
