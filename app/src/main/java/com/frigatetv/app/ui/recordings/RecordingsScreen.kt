package com.frigatetv.app.ui.recordings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableDoubleState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.EmptyState
import com.frigatetv.app.ui.FocusButton
import com.frigatetv.app.ui.FocusSurface
import com.frigatetv.app.ui.LiveGreen
import com.frigatetv.app.ui.LoadingState
import com.frigatetv.app.ui.Panel
import com.frigatetv.app.ui.PanelHi
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.StatusPill
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.Warn
import com.frigatetv.app.ui.isTelevision
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_HEADLINE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")

/**
 * Recordings tab: pick a camera and a day, scrub the timeline, and play from the cursor.
 * Pressing OK on the timeline or "Play from here" starts [PlaybackView].
 */
@Composable
fun RecordingsScreen(state: AppState) {
    val profile = state.active ?: return
    val viewModel: RecordingsViewModel = viewModel(factory = RecordingsViewModel.Factory)
    val ui by viewModel.state.collectAsState()
    val repo = remember(profile) { FrigateRepository(profile) }
    val cams = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }
    val camIds = remember(cams) { cams.map { it.id } }
    val preferred = state.prefs.lastCamera
    LaunchedEffect(profile, camIds) { viewModel.onEnter(profile, camIds, preferred) }

    if (cams.isEmpty()) {
        EmptyState(
            title = "No enabled cameras",
            message = "Enable a camera on this server to browse its recordings.",
        )
        return
    }

    val cameraId = resolveCameraId(ui.cameraId, preferred, camIds) ?: camIds.first()
    LaunchedEffect(cameraId) { viewModel.selectCamera(cameraId) }

    val zone = remember { ZoneId.systemDefault() }
    val window = dayWindow(ui.today, ui.dayOffset, zone)
    val day = window.day
    val dayStart = window.start
    val dayEnd = window.end
    // Captured when the camera or day changes, so "now" is fresh each time the view is shown.
    val nowEpoch = remember(ui.generation, ui.dayOffset, cameraId) { System.currentTimeMillis() / 1000.0 }
    val maxCursor = minOf(dayEnd, nowEpoch)

    // Held as a state object and read only by the small composables that show it, so moving the
    // cursor redraws the clock and timeline instead of recomposing the whole screen.
    val cursor: MutableDoubleState = remember(dayStart) {
        mutableDoubleStateOf(defaultCursor(dayStart, dayEnd, nowEpoch))
    }
    var playStart by remember { mutableStateOf<Double?>(null) }

    // Stop playback when the camera or the day changes.
    LaunchedEffect(cameraId, ui.dayOffset) { playStart = null }

    // Returning from playback re-creates the timeline; keep D-pad focus on it.
    val timelineRequester = remember { FocusRequester() }
    LaunchedEffect(playStart) {
        if (playStart == null) runCatching { timelineRequester.requestFocus() }
    }

    val start = playStart
    if (start != null) {
        BackHandler { playStart = null }
        PlaybackView(
            url = remember(start) {
                repo.vodUrl(cameraId, start, minOf(start + PLAY_WINDOW_SECONDS, dayEnd, nowEpoch))
            },
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
        {
                t ->
            cursor.doubleValue = t.coerceIn(dayStart, maxCursor)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        CameraPicker(camIds, cameraId) { viewModel.selectCamera(it) }
        Spacer(Modifier.height(Space.xs))
        DayPicker(ui.today, ui.dayOffset) { viewModel.selectDay(it) }
        Spacer(Modifier.height(Space.md))

        val loaded = ui.load
        // fill = false: on tall screens the panel wraps the capped timeline instead of leaving a void.
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.lg))
                .background(Panel)
                .padding(Space.md),
        ) {
            when (loaded) {
                is RecordingsLoad.Loading -> LoadingState("Loading $cameraName recordings…", Modifier.weight(1f))
                is RecordingsLoad.Failed -> EmptyState(
                    title = "Could not load recordings",
                    message = loaded.message ?: "Check the server connection and try again.",
                    modifier = Modifier.weight(1f),
                )
                is RecordingsLoad.Loaded -> {
                    val model = loaded.model
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
