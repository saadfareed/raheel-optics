package com.daymark.todo

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Rule
import org.junit.rules.ExternalResource
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.identity.Session
import com.daymark.todo.identity.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.rules.RuleChain
import org.junit.Test
import java.util.UUID

class HomeFlowTest {
    val compose=createAndroidComposeRule<MainActivity>()
    private val createdTitles=mutableListOf<String>()
    private var originalTheme:String?=null
    private val session=object : ExternalResource() {
        override fun before() {
            originalTheme=InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("appearance",0).getString("theme",null)
            SessionStore(InstrumentationRegistry.getInstrumentation().targetContext).save(
                Session("local-device","","","",Long.MAX_VALUE,true))
        }
        override fun after() {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            try {
                createdTitles.forEach {title ->runBlocking {
                    val app=context.applicationContext as DaymarkApp
                    app.workspace()?.db?.writeTransaction {tx ->
                        val id=tx.getOptional("SELECT json_extract(body,'$.todo.id') FROM history_cache WHERE json_extract(body,'$.todo.title')=? LIMIT 1",listOf(title)) {it.getString(0)!!}
                            ?: tx.getOptional("SELECT id FROM drafts WHERE json_extract(body,'$.title')=? LIMIT 1",listOf(title)) {it.getString(0)!!}
                        if(id!=null) {
                            val status=tx.getOptional("SELECT status FROM local_state WHERE id=?",listOf(id)) {it.getString(0)!!}
                            tx.execute("DELETE FROM history_cache WHERE json_extract(body,'$.todo.id')=?",listOf(id))
                            tx.execute("DELETE FROM drafts WHERE id=?",listOf(id))
                            tx.execute("DELETE FROM local_state WHERE id=?",listOf(id))
                            if(status in setOf("active","completed","deleted")) tx.execute("UPDATE local_totals SET $status=MAX(0,$status-1),revision=revision+1 WHERE id='totals'")
                        }
                    }
                }}
            } finally {
                context.getSharedPreferences("appearance",0).edit().apply {if(originalTheme==null) remove("theme") else putString("theme",originalTheme)}.commit()
                SessionStore(context).clear()
            }
        }
    }
    @get:Rule val rules: RuleChain=RuleChain.outerRule(session).around(compose)
    private fun capture(name:String) {
        compose.waitForIdle()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        java.io.File(context.cacheDir,"$name.png").outputStream().use {output ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }
    @Test fun analyticsPeriodMenuKeepsPeriodUntilAnOptionIsChosen() {
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Analytics").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Analytics").performClick()
        compose.onNodeWithText("Overview").assertIsDisplayed()
        compose.onNodeWithText("All").performScrollTo().assertIsSelected()
        compose.onAllNodesWithText("High").onLast().performClick()
        compose.onAllNodesWithText("High").onLast().assertIsSelected()
        capture("analytics-priority")
        compose.onNodeWithText("Last 7 days").performScrollTo().performClick()
        compose.onAllNodesWithText("Last 7 days").assertCountEquals(2)
        compose.onNodeWithText("Last 30 days").performClick()
        compose.onNodeWithText("Last 30 days").assertIsDisplayed()
        compose.onNodeWithText("Last 7 days").assertDoesNotExist()
    }
    @Test fun optionalReminderSurvivesRecreation() {
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Add todo").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Add todo").performClick()
        compose.onNodeWithContentDescription("Task reminder").performScrollTo().assertIsOff()
        compose.onNodeWithText("Countdown").assertDoesNotExist()
        compose.onNodeWithText("Date & time").assertDoesNotExist()
        compose.onNodeWithContentDescription("Task reminder").performClick()
        compose.onNodeWithText("After deadline").performScrollTo().performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithContentDescription("Task reminder").performScrollTo().assertIsOn()
        compose.onNodeWithText("After deadline").performScrollTo().assertIsSelected()
        capture("optional-reminder")
    }

    @Test fun appearanceSelectionPersistsAfterRecreation() {
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Settings").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Dark").assertIsDisplayed()
        capture("settings-dark")
        org.junit.Assert.assertEquals("Dark",InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("appearance",0).getString("theme",null))
        compose.onNodeWithText("Appearance").performClick()
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("Light").assertIsDisplayed()
        capture("settings-light")
    }

    @Test fun localWorkspaceCentralAddAndCompletionFlow() {
        val title="UI test task ${UUID.randomUUID()}"
        createdTitles+=title
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Add todo").fetchSemanticsNodes().isNotEmpty()}
        capture("home-hero")
        compose.onNodeWithContentDescription("Add todo").performClick()
        compose.onNodeWithText("Todo title").performTextInput(title)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Todo title").assertTextContains(title)
        compose.onNodeWithText("Save todo").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Complete $title").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Complete $title").performClick()
        compose.onNodeWithContentDescription("History").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(title).assertIsDisplayed()
    }

    @Test fun repeatedSummaryFiltersKeepMatchingTasksVisible() {
        val late="Overdue QA ${UUID.randomUUID()}"
        val today="Today QA ${UUID.randomUUID()}"
        createdTitles+=late
        createdTitles+=today
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Add todo").fetchSemanticsNodes().isNotEmpty()}
        runBlocking {
            val store=(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as DaymarkApp).workspace()!!
            val now=System.currentTimeMillis()
            val end=com.daymark.todo.core.ListRules.timeBounds(now,java.time.ZoneId.systemDefault()).second
            store.save(com.daymark.todo.core.Todo(UUID.randomUUID().toString(),late,dueAt=now-60000),"create",null)
            store.save(com.daymark.todo.core.Todo(UUID.randomUUID().toString(),today,dueAt=now+(end-now)/2),"create",null)
            org.junit.Assert.assertTrue(store.todoPage(com.daymark.todo.core.TodoQuery(deadline=com.daymark.todo.core.DeadlineFilter.Today)).items.any {it.title==today})
        }
        compose.waitUntil(10000) {compose.onAllNodesWithText(late).fetchSemanticsNodes().isNotEmpty()}
        repeat(4) {compose.onAllNodesWithText("Overdue").onFirst().performClick()}
        compose.waitUntil(10000) {compose.onAllNodesWithText(late).fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText(late).onLast().assertIsDisplayed()
        repeat(4) {compose.onAllNodesWithText("Due today").onFirst().performClick()}
        compose.waitUntil(10000) {compose.onAllNodesWithText(late).fetchSemanticsNodes().size>=2}
        compose.onAllNodesWithText(late).onLast().assertIsDisplayed()
        compose.onNodeWithText("No Tasks").assertDoesNotExist()
    }
}
