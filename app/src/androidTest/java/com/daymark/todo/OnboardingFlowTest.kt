package com.daymark.todo

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.daymark.todo.ui.OnboardingScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnboardingFlowTest {
    @get:Rule val compose=createComposeRule()
    @Test fun finishesOnlyAfterTheLastPage() {
        var finished=false
        compose.setContent {MaterialTheme {OnboardingScreen(false,null) {finished=true}}}
        compose.onNodeWithText("Next").performClick()
        assertFalse(finished)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Let's begin").assertIsDisplayed()
        assertFalse(finished)
        compose.onNodeWithText("Let's begin").performClick()
        assertTrue(finished)
    }
    @Test fun introductionCanBeSkipped() {
        var finished=false
        compose.setContent {MaterialTheme {OnboardingScreen(false,null) {finished=true}}}
        compose.onNodeWithText("Skip").performClick()
        assertTrue(finished)
    }
}
