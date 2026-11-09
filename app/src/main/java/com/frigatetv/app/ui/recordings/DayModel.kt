package com.frigatetv.app.ui.recordings

import androidx.compose.runtime.Stable
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.RecordingSegment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal const val DAY_SECONDS = 86_400.0
internal const val PLAY_WINDOW_SECONDS = 3_600.0
private const val MERGE_GAP_SECONDS = 2.0
internal const val DAYS_BACK = 6

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_CHIP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")

/** One calendar day in epoch seconds, [start] inclusive and [end] exclusive. */
internal data class DayWindow(val day: LocalDate, val start: Double, val end: Double)

internal fun dayWindow(today: LocalDate, dayOffset: Int, zone: ZoneId): DayWindow {
    val day = today.minusDays(dayOffset.toLong())
    val start = day.atStartOfDay(zone).toEpochSecond().toDouble()
    return DayWindow(day, start, start + DAY_SECONDS)
}

internal class DayData(
    val segments: List<RecordingSegment>,
    val events: List<FrigateEvent>,
)

/**
 * A day's recordings reduced to sorted, merged ranges and sorted event starts. Built once per load,
 * so drawing, cursor lookups and "next event" never sort or allocate per frame or per key press.
 */
@Stable
class DayModel(
    val starts: DoubleArray,
    val ends: DoubleArray,
    val eventStarts: DoubleArray,
    val recordedSeconds: Double,
) {
    val isEmpty get() = starts.isEmpty()

    /** Index of the merged range containing [t], or -1. */
    fun rangeAt(t: Double): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                t < starts[mid] -> hi = mid - 1
                t >= ends[mid] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun nextEventAfter(t: Double): Double? {
        var lo = 0
        var hi = eventStarts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (eventStarts[mid] <= t) lo = mid + 1 else hi = mid
        }
        return if (lo < eventStarts.size) eventStarts[lo] else null
    }
}

internal fun buildDayModel(data: DayData, dayStart: Double, dayEnd: Double): DayModel {
    val sorted = data.segments
        .filter { it.endTime > dayStart && it.startTime < dayEnd }
        .sortedBy { it.startTime }
    val starts = ArrayList<Double>(sorted.size)
    val ends = ArrayList<Double>(sorted.size)
    for (s in sorted) {
        val a = maxOf(s.startTime, dayStart)
        val b = minOf(s.endTime, dayEnd)
        if (ends.isNotEmpty() && a <= ends.last() + MERGE_GAP_SECONDS) {
            if (b > ends.last()) ends[ends.size - 1] = b
        } else {
            starts += a
            ends += b
        }
    }
    var total = 0.0
    for (i in starts.indices) total += ends[i] - starts[i]
    val events = data.events
        .asSequence()
        .map { it.startTime }
        .filter { it >= dayStart && it < dayEnd }
        .sorted()
        .toList()
        .toDoubleArray()
    return DayModel(starts.toDoubleArray(), ends.toDoubleArray(), events, total)
}

internal fun defaultCursor(dayStart: Double, dayEnd: Double, nowEpoch: Double): Double {
    // Today: start near "now" minus an hour. Past days: noon.
    val candidate = if (nowEpoch in dayStart..dayEnd) nowEpoch - 3_600 else dayStart + DAY_SECONDS / 2
    return candidate.coerceIn(dayStart, dayEnd)
}

internal fun formatClock(epochSeconds: Double): String =
    CLOCK.format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

internal fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).toLong()
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

internal fun dayLabel(today: LocalDate, offset: Int): String = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> today.minusDays(offset.toLong()).format(DAY_CHIP)
}
