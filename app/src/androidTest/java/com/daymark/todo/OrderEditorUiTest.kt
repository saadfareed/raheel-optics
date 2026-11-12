package com.daymark.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.daymark.todo.core.*
import com.daymark.todo.ui.TodoEditor
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class OrderEditorUiTest {
    @get:Rule val compose=createComposeRule()
    private fun capture(name:String) {
        val instrument=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val screenshot=instrument.uiAutomation.takeScreenshot()
        java.io.File(instrument.targetContext.cacheDir,name).outputStream().use {screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun sundayHoursCanBeToggledWithoutChangingSavedSettings() {
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            com.daymark.todo.ui.ShopSettingsScreen({})
        }}
        compose.onNodeWithTag("shop-hours-form").performScrollToNode(hasTestTag("Sunday-hours"))
        val initial=compose.onNodeWithTag("Sunday-hours").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState]
        compose.onNodeWithTag("Sunday-hours").performClick()
        if(initial==androidx.compose.ui.state.ToggleableState.On) compose.onNodeWithTag("Sunday-hours").assertIsOff()
        else compose.onNodeWithTag("Sunday-hours").assertIsOn()
        compose.onNodeWithText("Save",useUnmergedTree=true).assertIsDisplayed()
        capture("shop-hours-repair-qa.png")
        // Deliberately do not save: the owner's shop preferences remain unchanged.
    }
    @Test fun fixedSaveButtonCreatesAnOrderWithoutOptionalPrescriptionFields() {
        var saved:Todo?=null
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            TodoEditor(null,false,null,{}) {saved=it}
        }}
        compose.onNodeWithText("Save order").assertIsDisplayed()
        compose.onNodeWithText("Customer name").performTextInput("Repair Demo")
        compose.onNodeWithText("Mobile number").performTextInput("03001234567")
        capture("order-editor-repair-qa.png")
        compose.onNodeWithText("Save order").performClick()
        compose.runOnIdle {
            assertNotNull(saved)
            assertEquals("Repair Demo",saved!!.title)
            assertEquals("+923001234567",saved!!.normalizedMobile)
            assertEquals(OrderStatus.ACTIVE,saved!!.orderStatus)
            assertTrue(saved!!.prescription.distanceRight.sphere.isBlank())
            assertNull(saved!!.prescription.distanceRight.cylinder)
            assertTrue(saved!!.payments.isEmpty())
        }
    }
}
