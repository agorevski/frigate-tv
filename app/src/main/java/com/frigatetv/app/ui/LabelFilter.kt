package com.frigatetv.app.ui

/** Event type chips on the Events tab. Pure logic, kept out of the composables so it can be unit tested. */
internal enum class LabelFilter(val title: String) {
    ALL("All"),
    PERSON("People"),
    VEHICLE("Vehicles"),
    OTHER("Other"),
}

private val VEHICLE_LABELS = setOf("car", "truck", "motorcycle", "bus", "bicycle")

internal fun LabelFilter.matches(label: String): Boolean = when (this) {
    LabelFilter.ALL -> true
    LabelFilter.PERSON -> label == "person"
    LabelFilter.VEHICLE -> label in VEHICLE_LABELS
    LabelFilter.OTHER -> label != "person" && label !in VEHICLE_LABELS
}
