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
    @Test fun examplesAreInteractiveAndFinishOnlyAfterExplicitChoice() {
        var finished=false
        compose.setContent {MaterialTheme {OnboardingScreen(false,null) {finished=true}}}
        compose.onNodeWithContentDescription("Complete sample task").performClick()
        compose.onNodeWithText("Nice. One thing done.").assertIsDisplayed()
        assertFalse(finished)
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("1 hour").performClick()
        compose.onNodeWithText("In 1 hour").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Finish the sample task").performClick()
        compose.onNodeWithText("3 of 3 done").assertIsDisplayed()
        assertFalse(finished)
        compose.onNodeWithText("Let's begin").performClick()
        assertTrue(finished)
    }
    @Test fun introductionCanBeSkippedWithoutWalkingThroughEveryPage() {
        var finished=false
        compose.setContent {MaterialTheme {OnboardingScreen(false,null) {finished=true}}}
        compose.onNodeWithText("Skip").performClick()
        assertTrue(finished)
    }
}
