package com.daymark.todo

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.daymark.todo.identity.BackendTransport
import com.daymark.todo.updates.UpdateController
import com.daymark.todo.updates.UpdateGate
import com.daymark.todo.updates.UpdateStorage
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test

class UpdateGateTest {
    @get:Rule val compose = createComposeRule()
    private fun controller(required: Boolean): UpdateController {
        val code = BuildConfig.VERSION_CODE
        val minimum = if (required) code + 1 else code
        val storage = object : UpdateStorage {
            var value: String? = null
            var dismissed = 0
            override fun policy() = value
            override fun savePolicy(value: String?) { this.value = value }
            override fun dismissedVersion() = dismissed
            override fun dismiss(version: Int) { dismissed = version }
        }
        val backend = object : BackendTransport {
            override suspend fun accountId() = error("No login needed")
            override suspend fun request(path: String, method: String, body: JsonElement?, authenticated: Boolean) =
                Json.parseToJsonElement("""[{"latest_version_code":${code+1},"minimum_version_code":$minimum,"version_name":"Next","update_url":"https://example.com/app.apk"}]""")
        }
        return UpdateController(storage, backend, code)
    }
    @Test fun requiredUpdateDoesNotComposeAppContent() {
        val controller = controller(true)
        compose.setContent { UpdateGate(controller) { Text("App content") } }
        compose.waitUntil(5_000) { controller.checked.value }
        compose.onNodeWithText("Update required").assertIsDisplayed()
        compose.onNodeWithText("Update now").assertIsDisplayed()
        compose.onNodeWithText("App content").assertDoesNotExist()
        compose.onNodeWithText("Later").assertDoesNotExist()
    }
    @Test fun optionalUpdateCanBeDismissedToUseApp() {
        val controller = controller(false)
        compose.setContent { UpdateGate(controller) { Text("App content") } }
        compose.waitUntil(5_000) { controller.checked.value }
        compose.onNodeWithText("Update available").assertIsDisplayed()
        compose.onNodeWithText("Later").performClick()
        compose.onNodeWithText("Update available").assertDoesNotExist()
        compose.onNodeWithText("App content").assertIsDisplayed()
    }
}
