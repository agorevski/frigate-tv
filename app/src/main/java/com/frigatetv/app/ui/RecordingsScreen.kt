package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.RecordingSegment
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.flow.collectLatest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val DAY_SECONDS = 86_400.0
private const val CURSOR_STEP_SECONDS = 300.0
private const val CURSOR_FAST_STEP_SECONDS = 900.0
private const val FAST_AFTER_REPEATS = 6
private const val PLAY_WINDOW_SECONDS = 3_600.0
private const val MERGE_GAP_SECONDS = 2.0
private const val DAYS_BACK = 6
private const val LABEL_EVERY_HOURS = 3

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY_HEADLINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val DAY_CHIP: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d")
private val CLOCK_STYLE = NumericStyle.merge(TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 58.sp))
private val HOUR_LABEL_STYLE = NumericStyle.merge(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextSecondary))
private val NUMERIC_SPAN = SpanStyle(fontFeatureSettings = NumericStyle.fontFeatureSettings)
private val HOUR_LABELS = (0..24 step LABEL_EVERY_HOURS).map { "%02d:00".format(it % 24) }

private val SegmentColor = Accent
private val TrackColor = Color.White.copy(alpha = 0.06f)
private val TickColor = Color.White.copy(alpha = 0.14f)
private val MajorTickColor = Color.White.copy(alpha = 0.28f)
private val HourHighlight = Accent.copy(alpha = 0.10f)
private val FutureShade = Color.Black.copy(alpha = 0.35f)

private val api = FrigateApi()

private data class DayData(
    val segments: List<RecordingSegment>,
    val events: List<FrigateEvent>,
)

/**
 * A day's recordings reduced to sorted, merged ranges and sorted event starts. Built once per load,
 * so drawing, cursor lookups and "next event" never sort or allocate per frame or per key press.
 */
@Stable
private class DayModel(
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

private fun buildDayModel(data: DayData, dayStart: Double, dayEnd: Double): DayModel {
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

@Composable
fun RecordingsScreen(state: AppState) {
    val profile = state.active ?: return
    val cams = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }
    if (cams.isEmpty()) {
        EmptyState(
            title = "No enabled cameras",
            message = "Enable a camera on this server to browse its recordings.",
        )
        return
    }

    var cameraId by rememberSaveable { mutableStateOf(state.prefs.lastCamera ?: cams.first().id) }
    if (cams.none { it.id == cameraId }) cameraId = cams.first().id
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val day = today.minusDays(dayOffset.toLong())
    val dayStart = remember(dayOffset) { day.atStartOfDay(zone).toEpochSecond().toDouble() }
    val dayEnd = dayStart + DAY_SECONDS
    val nowEpoch = remember(dayOffset, cameraId) { System.currentTimeMillis() / 1000.0 }
    val maxCursor = minOf(dayEnd, nowEpoch)

    // Held as a state object and read only by the small composables that show it, so moving the
    // cursor redraws the clock and timeline instead of recomposing the whole screen.
    val cursor: MutableDoubleState = remember(dayStart) {
        mutableDoubleStateOf(defaultCursor(dayStart, dayEnd, nowEpoch))
    }
    var playStart by remember { mutableStateOf<Double?>(null) }

    // Stop playback when the camera or the day changes.
    LaunchedEffect(cameraId, dayOffset) { playStart = null }

    // Returning from playback re-creates the timeline; keep D-pad focus on it.
    val timelineRequester = remember { FocusRequester() }
    LaunchedEffect(playStart) {
        if (playStart == null) runCatching { timelineRequester.requestFocus() }
    }

    val data by produceState<Result<DayModel>?>(null, profile, cameraId, dayStart) {
        value = null
        value = runCatching {
            val raw = DayData(
                segments = api.recordings(profile, cameraId, dayStart, dayEnd),
                events = api.events(profile, limit = 300, after = dayStart, camera = cameraId),
            )
            buildDayModel(raw, dayStart, dayEnd)
        }
    }

    val start = playStart
    if (start != null) {
        BackHandler { playStart = null }
        PlaybackView(
            url = remember(start) { api.vodUrl(profile, cameraId, start, minOf(start + PLAY_WINDOW_SECONDS, dayEnd, nowEpoch)) },
            cameraId = cameraId,
            start = start,
            dayLabel = day.format(DAY_HEADLINE),
            audible = !state.prefs.muted,
        )
        return
    }

    val cameraName = displayName(cameraId)
    val dateLabel = day.format(DAY_HEADLINE)
    val moveCursor: (Double) -> Unit = remember(dayStart, maxCursor) {
        { t -> cursor.doubleValue = t.coerceIn(dayStart, maxCursor) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        val camIds = remember(cams) { cams.map { it.id } }
        CameraPicker(camIds, cameraId) { cameraId = it }
        Spacer(Modifier.height(Space.xs))
        DayPicker(today, dayOffset) { dayOffset = it }
        Spacer(Modifier.height(Space.md))

        val loaded = data
        val model = loaded?.getOrNull()
        // fill = false: on tall screens the panel wraps the capped timeline instead of leaving a void.
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Panel)
                .padding(Space.md),
        ) {
            when {
                loaded == null -> LoadingState("Loading $cameraName recordings…", Modifier.weight(1f))
                model == null -> EmptyState(
                    title = "Could not load recordings",
                    message = loaded.exceptionOrNull()?.message ?: "Check the server connection and try again.",
                    modifier = Modifier.weight(1f),
                )
                else -> {
                    if (model.isEmpty) {
                        EmptyState(
                            title = "No recordings on $dateLabel",
                            message = "$cameraName has no saved footage for this day. Pick another day or camera.",
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TimelineHeader(cameraName, dateLabel, model, cursor)
                        Spacer(Modifier.height(Space.sm))
                        Timeline(
                            focusRequester = timelineRequester,
                            dayStart = dayStart,
                            nowEpoch = nowEpoch,
                            model = model,
                            cursor = cursor,
                            onCursor = moveCursor,
                            onPlay = { playStart = cursor.doubleValue },
                            // Capped so a tall (portrait) screen does not stretch the day into a huge block.
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(min = 96.dp, max = 320.dp)
                                .fillMaxHeight(),
                        )
                    }
                    Spacer(Modifier.height(Space.sm))
                    Controls(
                        model = model,
                        cursor = cursor,
                        onNextEvent = moveCursor,
                        onPlay = { playStart = cursor.doubleValue },
                    )
                }
            }
        }
    }
}

/** Camera choice as a compact pill row. Lazy so long camera lists stay cheap and scroll into view. */
@Composable
private fun CameraPicker(ids: List<String>, selected: String, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = ids.indexOf(selected).coerceAtLeast(0))
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        contentPadding = PaddingValues(vertical = Space.xxs, horizontal = Space.xxs),
    ) {
        items(ids, key = { it }, contentType = { "camera" }) { id ->
            Pill(displayName(id), selected = id == selected) { onSelect(id) }
        }
    }
}

@Composable
private fun DayPicker(today: LocalDate, selected: Int, onSelect: (Int) -> Unit) {
    val labels = remember(today) { (0..DAYS_BACK).map { dayLabel(today, it) } }
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.xxs, vertical = Space.xxs),
    ) {
        labels.forEachIndexed { offset, label ->
            Pill(label, selected = offset == selected) { onSelect(offset) }
        }
    }
}

/** Selection is a raised tint plus accent text, keeping solid accent fill for the primary action. */
@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        modifier = Modifier.defaultMinSize(minHeight = 44.dp),
        background = if (selected) PanelHi else Panel,
        shape = RoundedCornerShape(50),
        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
    ) {
        Text(
            label,
            color = if (selected) Accent else TextSecondary,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun TimelineHeader(cameraName: String, dateLabel: String, model: DayModel, cursor: MutableDoubleState) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(
                "$cameraName · $dateLabel",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            CursorClock(cursor)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                StatusPill(formatDuration(model.recordedSeconds) + " recorded", Accent)
                if (model.eventStarts.isNotEmpty()) {
                    val n = model.eventStarts.size
                    StatusPill(if (n == 1) "1 event" else "$n events", Warn)
                }
            }
            SegmentStatus(model, cursor)
        }
    }
}

@Composable
private fun CursorClock(cursor: MutableDoubleState) {
    Text(formatClock(cursor.doubleValue), color = TextPrimary, style = CLOCK_STYLE)
}

@Composable
private fun SegmentStatus(model: DayModel, cursor: MutableDoubleState) {
    val index by remember(model) { derivedStateOf { model.rangeAt(cursor.doubleValue) } }
    if (index < 0) {
        Text("No recording at this time", color = Warn, style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(
            buildAnnotatedString {
                append("Recorded ")
                withStyle(NUMERIC_SPAN) { append(formatClock(model.starts[index])) }
                append(" – ")
                withStyle(NUMERIC_SPAN) { append(formatClock(model.ends[index])) }
            },
            color = LiveGreen,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun Controls(
    model: DayModel,
    cursor: MutableDoubleState,
    onNextEvent: (Double) -> Unit,
    onPlay: () -> Unit,
) {
    val isTv = isTelevision()
    val nextEvent by remember(model) { derivedStateOf { model.nextEventAfter(cursor.doubleValue + 1) } }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            when {
                model.isEmpty -> "Choose another day or camera above"
                isTv -> "◀ ▶  move 5 min  ·  hold to skip faster  ·  OK  play"
                else -> "Tap Play from here to start playback"
            },
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        FocusButton("Next event", { nextEvent?.let(onNextEvent) }, enabled = nextEvent != null)
        FocusButton("Play from here", onPlay, selected = true, enabled = !model.isEmpty)
    }
}

/**
 * The whole day drawn on one Canvas: hour grid, merged recording ranges, event markers, the focused
 * hour and the cursor. The cursor animates through an [Animatable] read only in the draw phase,
 * so a key press triggers a redraw, not a recomposition.
 */
@Composable
private fun Timeline(
    focusRequester: FocusRequester,
    dayStart: Double,
    nowEpoch: Double,
    model: DayModel,
    cursor: MutableDoubleState,
    onCursor: (Double) -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = remember(dayStart) { Animatable(((cursor.doubleValue - dayStart) / DAY_SECONDS).toFloat()) }
    LaunchedEffect(fraction) {
        snapshotFlow { ((cursor.doubleValue - dayStart) / DAY_SECONDS).toFloat() }
            .collectLatest { fraction.animateTo(it, tween(Motion.FocusMs)) }
    }
    val measurer = rememberTextMeasurer(cacheSize = HOUR_LABELS.size)
    val labels: List<TextLayoutResult> = remember(measurer) { HOUR_LABELS.map { measurer.measure(it, HOUR_LABEL_STYLE) } }
    val segFractions = remember(model, dayStart) {
        FloatArray(model.starts.size * 2) { i ->
            val t = if (i % 2 == 0) model.starts[i / 2] else model.ends[i / 2]
            ((t - dayStart) / DAY_SECONDS).toFloat()
        }
    }
    val eventFractions = remember(model, dayStart) {
        FloatArray(model.eventStarts.size) { ((model.eventStarts[it] - dayStart) / DAY_SECONDS).toFloat() }
    }
    val nowFraction = ((nowEpoch - dayStart) / DAY_SECONDS).toFloat()

    FocusSurface(
        onClick = onPlay,
        background = Ink,
        shape = RoundedCornerShape(Radius.md),
        focusRequester = focusRequester,
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = if (event.nativeKeyEvent.repeatCount >= FAST_AFTER_REPEATS) CURSOR_FAST_STEP_SECONDS else CURSOR_STEP_SECONDS
                when (event.key) {
                    Key.DirectionLeft -> { onCursor(cursor.doubleValue - step); true }
                    Key.DirectionRight -> { onCursor(cursor.doubleValue + step); true }
                    else -> false
                }
            },
    ) { focused ->
        Canvas(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.md, vertical = Space.sm)
                .pointerInput(dayStart) {
                    detectTapGestures { offset -> onCursor(dayStart + offset.x / size.width * DAY_SECONDS) }
                },
        ) {
            drawTimeline(
                segFractions = segFractions,
                eventFractions = eventFractions,
                nowFraction = nowFraction,
                cursorFraction = fraction.value,
                labels = labels,
                focused = focused,
            )
        }
    }
}

private fun DrawScope.drawTimeline(
    segFractions: FloatArray,
    eventFractions: FloatArray,
    nowFraction: Float,
    cursorFraction: Float,
    labels: List<TextLayoutResult>,
    focused: Boolean,
) {
    val w = size.width
    val labelH = labels.first().size.height.toFloat()
    val markerZone = 14.dp.toPx()
    val trackTop = markerZone + 4.dp.toPx()
    val trackBottom = size.height - labelH - 8.dp.toPx()
    val trackH = (trackBottom - trackTop).coerceAtLeast(8.dp.toPx())
    val hourW = w / 24f
    val radius = CornerRadius(6.dp.toPx())

    // Focused hour column.
    val hour = (cursorFraction * 24f).toInt().coerceIn(0, 23)
    drawRoundRect(
        color = if (focused) HourHighlight else HourHighlight.copy(alpha = 0.05f),
        topLeft = Offset(hour * hourW, trackTop - 2.dp.toPx()),
        size = Size(hourW, trackH + 4.dp.toPx()),
        cornerRadius = radius,
    )

    // Track and hour grid.
    drawRoundRect(TrackColor, Offset(0f, trackTop), Size(w, trackH), radius)
    val tickW = 1.dp.toPx()
    for (h in 0..24) {
        val x = (h * hourW).coerceIn(0f, w - tickW)
        val major = h % LABEL_EVERY_HOURS == 0
        drawRect(
            color = if (major) MajorTickColor else TickColor,
            topLeft = Offset(x, if (major) trackTop else trackBottom - 6.dp.toPx()),
            size = Size(tickW, if (major) trackH + 4.dp.toPx() else 6.dp.toPx()),
        )
    }
    labels.forEachIndexed { i, label ->
        val x = i * LABEL_EVERY_HOURS * hourW - label.size.width / 2f
        drawText(label, topLeft = Offset(x.coerceIn(0f, w - label.size.width), size.height - labelH))
    }

    // Recorded ranges.
    val minW = 2.dp.toPx()
    val segAlpha = if (focused) 1f else 0.8f
    val inset = trackH * 0.18f
    var i = 0
    while (i < segFractions.size) {
        val x1 = segFractions[i] * w
        val x2 = maxOf(segFractions[i + 1] * w, x1 + minW)
        drawRoundRect(
            color = SegmentColor,
            alpha = segAlpha,
            topLeft = Offset(x1, trackTop + inset),
            size = Size(x2 - x1, trackH - inset * 2),
            cornerRadius = CornerRadius(minOf(radius.x, (x2 - x1) / 2)),
        )
        i += 2
    }

    // Future (today only).
    if (nowFraction in 0f..1f) {
        val x = nowFraction * w
        drawRect(FutureShade, Offset(x, trackTop), Size(w - x, trackH))
    }

    // Event markers.
    val dotR = 3.5.dp.toPx()
    for (f in eventFractions) {
        drawCircle(Warn, dotR, Offset(f * w, markerZone / 2))
    }

    // Cursor with a head.
    val cx = cursorFraction * w
    val cursorColor = if (focused) TextPrimary else TextSecondary
    drawRect(cursorColor, Offset(cx - 1.5.dp.toPx(), trackTop - 4.dp.toPx()), Size(3.dp.toPx(), trackH + 8.dp.toPx()))
    drawCircle(cursorColor, 5.dp.toPx(), Offset(cx, trackTop - 4.dp.toPx()))
}

@Composable
private fun PlaybackView(url: String, cameraId: String, start: Double, dayLabel: String, audible: Boolean) {
    val isTv = isTelevision()
    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        // Video sits in a Panel frame; the inner corner radius is the outer radius minus the frame padding.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Panel)
                .padding(Space.xs),
        ) {
            StreamPlayer(
                url = url,
                rtsp = false,
                audible = audible,
                networkEpoch = 0,
                onState = {},
                controls = true,
                retry = false,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(Color.Black),
            )
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(Space.sm)
                    .clip(RoundedCornerShape(Radius.sm))
                    .scrim(0.6f)
                    .padding(horizontal = Space.sm, vertical = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                StatusPill("REC", Accent)
                Text(displayName(cameraId), color = TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(dayLabel, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                buildAnnotatedString {
                    append("Playing ${displayName(cameraId)} from ")
                    withStyle(NUMERIC_SPAN) { append(formatClock(start)) }
                    append(if (isTv) ". Press BACK to return to the timeline." else ". Back returns to the timeline.")
                },
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            if (isTv) {
                Spacer(Modifier.width(Space.md))
                Text(
                    "OK  pause  ·  ◀ ▶  seek",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun defaultCursor(dayStart: Double, dayEnd: Double, nowEpoch: Double): Double {
    // Today: start near "now" minus an hour. Past days: noon.
    val candidate = if (nowEpoch in dayStart..dayEnd) nowEpoch - 3_600 else dayStart + DAY_SECONDS / 2
    return candidate.coerceIn(dayStart, dayEnd)
}

private fun formatClock(epochSeconds: Double): String =
    CLOCK.format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

private fun formatDuration(seconds: Double): String {
    val mins = (seconds / 60).toLong()
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

private fun dayLabel(today: LocalDate, offset: Int): String = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> today.minusDays(offset.toLong()).format(DAY_CHIP)
}
