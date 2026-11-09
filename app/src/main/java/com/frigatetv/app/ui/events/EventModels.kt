package com.frigatetv.app.ui.events

import androidx.compose.runtime.Immutable
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.displayName
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class LabelFilter(val title: String) {
    ALL("All"),
    PERSON("People"),
    VEHICLE("Vehicles"),
    OTHER("Other"),
}

enum class TimeRange(val title: String, val chip: String, val seconds: Long) {
    DAY("Last 24 hours", "24h", 86_400L),
    WEEK("Last 7 days", "7d", 604_800L),
    MONTH("Last 30 days", "30d", 2_592_000L),
}

private val VEHICLE_LABELS = setOf("car", "truck", "motorcycle", "bus", "bicycle")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")

internal fun LabelFilter.matches(label: String): Boolean = when (this) {
    LabelFilter.ALL -> true
    LabelFilter.PERSON -> label == "person"
    LabelFilter.VEHICLE -> label in VEHICLE_LABELS
    LabelFilter.OTHER -> label != "person" && label !in VEHICLE_LABELS
}

/** Filter kind prefix for copy, e.g. "person " in "3 person events". Empty for All. */
internal fun LabelFilter.kind(): String = when (this) {
    LabelFilter.ALL -> ""
    LabelFilter.PERSON -> "person "
    LabelFilter.VEHICLE -> "vehicle "
    LabelFilter.OTHER -> "other "
}

fun formatEventTime(epochSeconds: Double): String =
    TIME_FORMAT.format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

/** An event with every display string precomputed off the main thread, so cards only lay out text. */
@Immutable
class EventUi(
    val event: FrigateEvent,
    val title: String,
    val camera: String,
    val time: String,
    val relative: String?,
    val duration: String,
    val ongoing: Boolean,
    val score: String?,
    val day: LocalDate,
) {
    val id: String get() = event.id
}

/** Day headers and cards, flattened for the grid. */
sealed interface GridRow
class DayRow(val key: String, val title: String) : GridRow
class CardRow(val ui: EventUi) : GridRow

internal fun FrigateEvent.title(): String =
    label.replaceFirstChar { it.titlecase() } + (subLabel?.let { " · $it" } ?: "")

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun relativeTime(ageSeconds: Long): String? = when {
    ageSeconds < 60 -> "just now"
    ageSeconds < 3_600 -> "${ageSeconds / 60} min ago"
    ageSeconds < 86_400 -> "${ageSeconds / 3_600} h ago"
    else -> null
}

internal fun toUi(e: FrigateEvent, nowSeconds: Double, zone: ZoneId): EventUi {
    val start = Instant.ofEpochMilli((e.startTime * 1000).toLong()).atZone(zone)
    val end = e.endTime
    return EventUi(
        event = e,
        title = e.title(),
        camera = displayName(e.camera),
        time = TIME_FORMAT.format(start),
        relative = relativeTime((nowSeconds - e.startTime).toLong().coerceAtLeast(0)),
        duration = if (end == null) "Ongoing" else formatDuration((end - e.startTime).toLong().coerceAtLeast(0)),
        ongoing = end == null,
        score = e.score?.takeIf { it > 0 }?.let { "${(it * 100).roundToInt()}%" },
        day = start.toLocalDate(),
    )
}

private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DAY_FORMAT.format(day)
}

/** Events of the chosen [label] in grid order, with a header row at the start of each day. */
internal fun buildRows(events: List<EventUi>, label: LabelFilter): List<GridRow> {
    val today = LocalDate.now()
    val rows = ArrayList<GridRow>(events.size + 8)
    var lastDay: LocalDate? = null
    for (ui in events) {
        if (!label.matches(ui.event.label)) continue
        if (ui.day != lastDay) {
            lastDay = ui.day
            rows += DayRow("day-${ui.day}", dayTitle(ui.day, today))
        }
        rows += CardRow(ui)
    }
    return rows
}
