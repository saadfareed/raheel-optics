package com.daymark.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.daymark.todo.core.*
import com.daymark.todo.ui.CustomerDetailsSheet
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

class CustomerProfileUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun editContactAndPrescriptionThenSaveProfile() {
        var saved:Customer?=null
        val customer=Customer("ui-demo","Demo Customer","03001234567","+923001234567",
            latestPrescription=Prescription(distanceRight=EyePrescription(sphere="-2.00")))
        compose.setContent {MaterialTheme(colorScheme=darkColorScheme()) {
            CustomerDetailsSheet(customer,false,null,{}) {saved=it}
        }}
        compose.onNodeWithText("Demo Customer").assertIsDisplayed()
        val instrument=InstrumentationRegistry.getInstrumentation()
        val shot=instrument.uiAutomation.takeScreenshot()
        java.io.File(instrument.targetContext.cacheDir,"profile-editor-qa.png").outputStream().use {shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        compose.onNodeWithText("Demo Customer").performTextReplacement("Demo Updated")
        compose.onNodeWithText("03001234567").performTextReplacement("03011234567")
        compose.onNodeWithText("-2.00").performScrollTo().performTextReplacement("-3.00")
        compose.onNodeWithTag("customer-profile-form").performScrollToNode(hasText("Save customer"))
        compose.onNodeWithText("Save customer").performClick()
        compose.runOnIdle {
            assertEquals("Demo Updated",saved!!.name)
            assertEquals("+923011234567",saved!!.normalizedMobile)
            assertEquals("-3.00",saved!!.latestPrescription!!.distanceRight.sphere)
            assertTrue(saved!!.previousMobiles.contains("+923001234567"))
        }
    }
}
