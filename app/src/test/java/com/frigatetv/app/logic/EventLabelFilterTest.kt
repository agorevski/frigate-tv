package com.frigatetv.app.logic

import com.frigatetv.app.ui.events.LabelFilter
import com.frigatetv.app.ui.events.matches
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Event type chips on the Events tab (the People and Vehicles checks in 07_events_recordings.sh). */
class EventLabelFilterTest {

    @Test
    fun people_filter_keeps_only_person_events() {
        assertTrue(LabelFilter.PERSON.matches("person"))
        listOf("car", "dog", "cat", "Person").forEach { label ->
            assertFalse("People must not show $label", LabelFilter.PERSON.matches(label))
        }
    }

    @Test
    fun vehicles_filter_matches_the_vehicle_labels_only() {
        listOf("car", "truck", "motorcycle", "bus", "bicycle").forEach { label ->
            assertTrue("Vehicles must show $label", LabelFilter.VEHICLE.matches(label))
        }
        assertFalse(LabelFilter.VEHICLE.matches("person"))
        assertFalse(LabelFilter.VEHICLE.matches("dog"))
    }

    @Test
    fun other_filter_is_everything_that_is_neither_person_nor_vehicle() {
        assertTrue(LabelFilter.OTHER.matches("dog"))
        assertTrue(LabelFilter.OTHER.matches("cat"))
        assertFalse(LabelFilter.OTHER.matches("person"))
        assertFalse(LabelFilter.OTHER.matches("car"))
    }

    @Test
    fun all_filter_keeps_every_label() {
        listOf("person", "car", "dog", "unknown").forEach { assertTrue(LabelFilter.ALL.matches(it)) }
    }
}
