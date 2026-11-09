@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Test

class EventsScreenTest : FrigateUiTest() {

    @Test
    fun eventListShowsCardsWithCameraNames() {
        launchApp()
        openTab("Events")
        waitForText("4 events")
        compose.onNodeWithText("Today").assertIsDisplayed()
        // Each camera name appears twice: once in the camera filter and once on its event card.
        compose.onAllNodesWithText("Front").assertCountEquals(2)
        compose.onAllNodesWithText("Mailbox").assertCountEquals(2)
        compose.onAllNodesWithText("Driveway").assertCountEquals(2)
        compose.onAllNodesWithText("Back").assertCountEquals(2)
        compose.onAllNodesWithText("Person").assertCountEquals(2)
        compose.onAllNodesWithText("Car").assertCountEquals(1)
    }

    @Test
    fun peopleFilterNarrowsListToPersonEvents() {
        launchApp()
        openTab("Events")
        waitForText("4 events")
        compose.onNodeWithText("People").performClick()
        waitForText("2 person events")
        compose.onAllNodesWithText("Person").assertCountEquals(2)
        compose.onAllNodes(hasText("Car")).assertCountEquals(0)
        compose.onAllNodes(hasText("Cat")).assertCountEquals(0)
    }

    @Test
    fun vehiclesFilterShowsOnlyTheCarEvent() {
        launchApp()
        openTab("Events")
        waitForText("4 events")
        compose.onNodeWithText("Vehicles").performClick()
        waitForText("1 vehicle event")
        compose.onAllNodesWithText("Car").assertCountEquals(1)
        compose.onAllNodesWithText("Person").assertCountEquals(0)
    }

    @Test
    fun vehiclesFilterWithoutVehicleEventsShowsEmptyState() {
        launchApp(eventsFixture = PEOPLE_ONLY_EVENTS_FIXTURE)
        openTab("Events")
        waitForText("2 events")
        compose.onNodeWithText("Vehicles").performClick()
        waitForText("No events for this filter.")
        waitForText("No vehicle events in the last 24 hours. Try a wider range or another filter.")
    }

    @Test
    fun eventsShowErrorWhenServerIsUnreachable() {
        launchApp()
        waitForText("Front")
        frigate.shutdown()
        openTab("Events")
        waitForText("Could not load events.")
    }
}
