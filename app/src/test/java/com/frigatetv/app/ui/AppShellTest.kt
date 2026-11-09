@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Test

class AppShellTest : FrigateUiTest() {

    @Test
    fun tabRowShowsAllFourTabs() {
        launchApp()
        waitForText("Front")
        listOf("Live", "Events", "Recordings", "Settings").forEach { tab ->
            compose.onNodeWithText(tab).assertIsDisplayed()
        }
        compose.onNodeWithText("Live").assertIsSelected()
    }

    @Test
    fun dpadRightMovesFocusAcrossTabs() {
        launchApp()
        waitForText("Front")
        compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Live").assertIsFocused()
        compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Events").assertIsFocused()
    }

    @Test
    fun dpadCenterOnFocusedTabShowsItsScreen() {
        launchApp()
        waitForText("Front")
        compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithText("Events").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForText("Type")
        compose.onNodeWithText("Events").assertIsSelected()
    }

    @Test
    fun dpadRightAcrossToSettingsShowsServerProfiles() {
        launchApp()
        waitForText("Front")
        compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionUp) }
        repeat(3) { compose.onNodeWithText("Live").performKeyInput { pressKey(Key.DirectionRight) } }
        compose.onNodeWithText("Settings").assertIsFocused()
        compose.onNodeWithText("Settings").performKeyInput { pressKey(Key.DirectionCenter) }
        waitForText("Server profiles")
        compose.onNodeWithText("Settings").assertIsSelected()
    }
}
