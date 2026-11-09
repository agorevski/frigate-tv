@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import org.junit.Test

class LiveScreenTest : FrigateUiTest() {

    @Test
    fun gridRendersATileForEachCamera() {
        launchApp()
        waitForText("Front")
        // The featured camera also has a rail tile, so a name can match more than one node.
        listOf("Front", "Back", "Mailbox", "Driveway", "Reolink Camera").forEach { camera ->
            compose.onAllNodesWithText(camera).onFirst().assertIsDisplayed()
        }
        compose.onAllNodes(hasText("No enabled cameras on this server.")).assertCountEquals(0)
    }

    @Test
    fun tilesShowOfflineWhenTheirStreamFails() {
        // The RTSP port is closed, so each camera's stream errors out and its tile reports OFFLINE.
        launchApp()
        waitForText("OFFLINE", timeoutMs = 30_000)
        waitForTextContaining("Offline · retrying")
        compose.onAllNodesWithText("Front").onFirst().assertIsDisplayed()
    }

    @Test
    fun unreachableServerShowsRetryPanel() {
        launchApp(reachable = false)
        waitForText("Can't reach $HOME_PROFILE_NAME")
        compose.onNodeWithText("http://127.0.0.1:${frigate.port}").assertIsDisplayed()
        compose.onNodeWithText("Retry now").assertIsDisplayed()
        compose.onNodeWithText("Server profiles").assertIsDisplayed()
        compose.onNodeWithText("Retrying automatically.").assertIsDisplayed()
    }
}
