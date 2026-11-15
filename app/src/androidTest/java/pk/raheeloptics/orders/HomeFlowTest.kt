package pk.raheeloptics.orders

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import pk.raheeloptics.orders.identity.Session
import pk.raheeloptics.orders.identity.SessionStore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.util.UUID

/** Core order flows in guest mode. Runs against the QA build, which has its own data. */
class HomeFlowTest {
    val compose=createAndroidComposeRule<MainActivity>()
    private val guest=object:ExternalResource() {
        override fun before() {
            val context=InstrumentationRegistry.getInstrumentation().targetContext
            SessionStore(context).save(Session("local-device","","","",Long.MAX_VALUE,true))
            // A fresh install shows the introduction first; these tests start from the app itself.
            kotlinx.coroutines.runBlocking {(context.applicationContext as RaheelApp).onboarding.complete()}
        }
    }
    @get:Rule val rules:RuleChain=RuleChain.outerRule(guest).around(compose)

    /** The app shows a spinner and disabled controls while its database opens; wait until it is usable, then for the control. */
    private fun waitFor(description:String) {
        fun ready(d:String)=compose.onAllNodes(hasContentDescription(d) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        compose.waitUntil(40000) {ready("Add order")}
        compose.waitUntil(10000) {ready(description)}
    }

    @Test fun orderStatusTabsAreOneScrollableRowWithAll() {
        waitFor("Add order")
        listOf("All","Active","Ready","Completed","Cancelled").forEach {name ->
            compose.onNode(hasText("$name ",substring=true) and hasClickAction()).assertExists()
        }
        compose.onNode(hasText("Active ",substring=true) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("Completed ",substring=true) and hasClickAction()).performScrollTo().performClick()
        compose.onNode(hasText("Completed ",substring=true) and hasClickAction()).assertIsSelected()
        compose.onNode(hasText("All ",substring=true) and hasClickAction()).performClick()
        compose.onNode(hasText("All ",substring=true) and hasClickAction()).assertIsSelected()
    }

    @Test fun newOrderIsValidatedThenAppearsInActive() {
        val name="UI Test ${UUID.randomUUID().toString().take(6)}"
        waitFor("Add order")
        compose.onNodeWithContentDescription("Add order").performClick()
        compose.onNodeWithText("Customer name").performTextInput("a")
        compose.onNodeWithText("Name must be at least 2 characters.").assertIsDisplayed()
        compose.onNodeWithText("Customer name").performTextReplacement(name)
        compose.onNodeWithText("Mobile number").performTextInput("12ab")
        compose.onNodeWithText("Enter a valid Pakistani mobile number, e.g. 0300 1234567.").assertIsDisplayed()
        compose.onNodeWithText("Mobile number").performTextReplacement("0300 1234567")
        compose.onNodeWithText("Save order").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(name).assertIsDisplayed()
    }

    @Test fun amountFieldsRejectDecimalsAndLeadingZeros() {
        waitFor("Add order")
        compose.onNodeWithContentDescription("Add order").performClick()
        compose.onNodeWithTag("order-form").performScrollToNode(hasText("Frame · PKR"))
        compose.onNodeWithText("Frame · PKR").performTextInput("0150.75")
        compose.onNodeWithText("Frame · PKR").assertTextContains("15075")
    }

    @Test fun appearanceSelectionPersistsAfterRecreation() {
        waitFor("Settings")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Appearance").performScrollTo().performClick()
        // The Appearance row also shows the current choice, so pick the menu option rather than the row.
        compose.onNode(hasText("Dark") and !hasText("Appearance")).performClick()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Appearance").performScrollTo().assertIsDisplayed()
        org.junit.Assert.assertEquals("Dark",InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("appearance",0).getString("theme",null))
        compose.onNodeWithText("Appearance").performClick()
        compose.onNode(hasText("Light") and !hasText("Appearance")).performClick()
    }

    @Test fun guestSeesSignInOnTheAccountCardAndNoSeparateSignInRow() {
        waitFor("Settings")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Not signed in · tap to sign in").assertIsDisplayed()
        compose.onAllNodesWithText("Log out").assertCountEquals(0)
    }
}
