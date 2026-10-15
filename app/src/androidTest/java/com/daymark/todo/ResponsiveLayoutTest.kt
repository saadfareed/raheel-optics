package com.daymark.todo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.identity.Session
import com.daymark.todo.ui.DaymarkScreen
import com.daymark.todo.ui.DaymarkTheme
import com.daymark.todo.ui.SplashScreen
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Regression coverage for 320dp layouts at 200% text size. */
class ResponsiveLayoutTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as DaymarkApp

    @Before fun prepare()=runBlocking {
        app.logout()
        app.onboarding.complete()
        context.getSharedPreferences("appearance",0).edit().putString("theme","Light").commit()
        Unit
    }
    @After fun finish()=runBlocking {app.logout()}
    private fun show() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                DaymarkTheme {Box(Modifier.width(320.dp).fillMaxHeight()) {DaymarkScreen(null,{})}}
            }
        }
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap ->
            java.io.File(context.cacheDir,"reaudit-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
    }
    @Test fun accountAtLargeFontAndNarrowContent() {
        show()
        compose.waitUntil(10000) {compose.onAllNodesWithText("Create account").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Create account").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Forgot password?"))
        capture("signup-font200-width320")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Continue as guest"))
        compose.onNodeWithText("Continue as guest").assertIsDisplayed()
    }
    @Test fun guestScreensAtLargeFontAndNarrowContent() {
        app.sessions.save(Session("local-device","","","",Long.MAX_VALUE,true))
        show()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Add todo").fetchSemanticsNodes().isNotEmpty()}
        capture("home-font200-width320")
        compose.onNodeWithContentDescription("Add todo").performClick()
        compose.onNodeWithText("High").performScrollTo()
        capture("editor-font200")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Privacy"))
        capture("settings-font200-width320")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manage storage"))
        compose.onNodeWithText("Manage storage").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Export device data"))
        compose.onNodeWithText("Save a copy of the data stored on this device.").assertIsDisplayed()
        compose.onNodeWithContentDescription("About Export device data").performClick()
        compose.onNodeWithText("The file contains readable task data, not passwords. It is a device snapshot, not a full cloud backup; store it privately.").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Import active tasks"))
        compose.onNodeWithText("Add active-task copies from a Daymark export.").assertIsDisplayed()
        capture("storage-font200-width320")
    }
    @Test fun inboxWarningFitsAtLargeFontAndNarrowContent() {
        app.sessions.save(Session("local-device","","","",Long.MAX_VALUE,true))
        show()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("Notifications").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Notifications").performClick()
        if(!app.reminders.allowed()) {
            compose.onNodeWithText("Notifications off").assertIsDisplayed()
            compose.onNodeWithText("Open settings").assertIsDisplayed()
        }
        capture("inbox-font200-width320")
    }
    @Test fun splashRemainsReachableInAShortLargeFontViewport() {
        compose.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,2f)) {
                DaymarkTheme {Box(Modifier.width(320.dp).height(240.dp)) {SplashScreen()}}
            }
        }
        capture("splash-font200-short")
        compose.onNodeWithContentDescription("Loading").performScrollTo().assertIsDisplayed()
    }
}
