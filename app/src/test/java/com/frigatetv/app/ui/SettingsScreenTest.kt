@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsScreenTest : FrigateUiTest() {

    @Test
    fun profileListShowsActiveProfileAndItsAddress() {
        launchApp()
        openTab("Settings")
        waitForText("Server profiles")
        // The active profile name is also the header's server name, so the list row is the first match.
        compose.onAllNodesWithText(HOME_PROFILE_NAME).onFirst().assertIsDisplayed()
        compose.onNodeWithText("ACTIVE").assertIsDisplayed()
        compose.onNodeWithText("http://127.0.0.1:${frigate.port}").assertIsDisplayed()
        waitForText("Frigate 0.16.2 · 5 cameras")
    }

    @Test
    fun addProfileButtonOpensTheProfileForm() {
        launchApp()
        openTab("Settings")
        waitForText("Server profiles")
        openAddProfileForm()
        listOf("Test connection", "Save & activate", "Save", "Cancel").forEach { button ->
            compose.onNodeWithText(button).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Name").assertIsDisplayed()
        compose.onNodeWithText("Port").assertIsDisplayed()
    }

    @Test
    fun testConnectionReportsReachableFrigate() {
        launchApp()
        openTab("Settings")
        waitForText("Server profiles")
        openAddProfileForm()
        fillProfileForm(name = "Second Server", host = "127.0.0.1", port = frigate.port)
        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        waitForText("Connected: Frigate 0.16.2 · 5 cameras")
    }

    @Test
    fun unreachableServerFailsTestAndSaveAndActivateIsRefused() {
        launchApp()
        openTab("Settings")
        waitForText("Server profiles")
        openAddProfileForm()
        frigate.shutdown()
        fillProfileForm(name = "Unreachable Test", host = "127.0.0.1", port = frigate.port)

        compose.onNodeWithText("Test connection").performScrollTo().performClick()
        waitForTextContaining("Failed: Cannot reach server")

        compose.onNodeWithText("Save & activate").performScrollTo().performClick()
        waitForTextContaining("Keeping the current profile")

        assertEquals(home.id, vm.state.value.active?.id)
        assertEquals(1, vm.state.value.profiles.size)
        compose.onNodeWithText("ACTIVE").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("http://127.0.0.1:${frigate.port}").performScrollTo().assertIsDisplayed()
    }
}
