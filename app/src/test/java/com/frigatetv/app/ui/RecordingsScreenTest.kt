@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class RecordingsScreenTest : FrigateUiTest() {

    @Test
    fun dayAndCameraChipsRenderWithTimelineLoaded() {
        launchApp()
        openTab("Recordings")
        waitForText("Play from here")
        compose.onNodeWithText("Today").assertIsDisplayed()
        compose.onNodeWithText("Yesterday").assertIsDisplayed()
        listOf("Front", "Back", "Mailbox", "Driveway", "Reolink Camera").forEach { camera ->
            compose.onNodeWithText(camera).assertIsDisplayed()
        }
        compose.onNodeWithText("Front · ${headline(0)}").assertIsDisplayed()
    }

    @Test
    fun selectingYesterdayUpdatesTheHeader() {
        launchApp()
        openTab("Recordings")
        waitForText("Play from here")
        compose.onNodeWithText("Yesterday").performClick()
        waitForText("Front · ${headline(1)}")
        compose.onNodeWithText("Play from here").assertIsDisplayed()
    }

    @Test
    fun selectingAnotherCameraUpdatesTheHeader() {
        launchApp()
        openTab("Recordings")
        waitForText("Play from here")
        compose.onNodeWithText("Driveway").performClick()
        waitForText("Driveway · ${headline(0)}")
    }

    /** The header's date, formatted the way the screen formats it (EEEE, MMMM d). */
    private fun headline(daysAgo: Long): String =
        LocalDate.now().minusDays(daysAgo).format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
}
