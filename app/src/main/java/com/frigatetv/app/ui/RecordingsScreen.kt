package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.RecordingSegment
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.saveable.rememberSaveable

private const val DAY_SECONDS = 86_400.0
private const val CURSOR_STEP_SECONDS = 300.0
private const val PLAY_WINDOW_SECONDS = 3_600.0
private const val DAYS_BACK = 6
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val api = FrigateApi()

private data class DayData(
    val segments: List<RecordingSegment>,
    val events: List<FrigateEvent>,
)

@Composable
fun RecordingsScreen(state: AppState) {
    val profile = state.active ?: return
    val cams = orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    if (cams.isEmpty()) {
        CenteredMessage("No enabled cameras on this server.")
        return
    }

    var cameraId by rememberSaveable { mutableStateOf(state.prefs.lastCamera ?: cams.first().id) }
    if (cams.none { it.id == cameraId }) cameraId = cams.first().id
    var dayOffset by rememberSaveable { mutableIntStateOf(0) }
    val zone = remember { ZoneId.systemDefault() }
    val dayStart = remember(dayOffset) {
        LocalDate.now(zone).minusDays(dayOffset.toLong()).atStartOfDay(zone).toEpochSecond().toDouble()
    }
    val dayEnd = dayStart + DAY_SECONDS
    val nowEpoch = remember(dayOffset, cameraId) { System.currentTimeMillis() / 1000.0 }

    var cursor by remember(dayStart) { mutableDoubleStateOf(defaultCursor(dayStart, dayEnd, nowEpoch)) }
    var playStart by remember { mutableStateOf<Double?>(null) }

    // Stop playback when the camera or the day changes.
    LaunchedEffect(cameraId, dayOffset) { playStart = null }

    // Returning from playback re-creates the timeline; keep D-pad focus on it.
    val timelineRequester = remember { FocusRequester() }
    LaunchedEffect(playStart) {
        if (playStart == null) runCatching { timelineRequester.requestFocus() }
    }

    val data by produceState<Result<DayData>?>(null, profile, cameraId, dayStart) {
        value = runCatching {
            DayData(
                segments = api.recordings(profile, cameraId, dayStart, dayEnd),
                events = api.events(profile, limit = 300, after = dayStart, camera = cameraId)
                    .filter { it.startTime < dayEnd },
            )
        }
    }

    val start = playStart
    if (start != null) {
        BackHandler { playStart = null }
        val end = minOf(start + PLAY_WINDOW_SECONDS, dayEnd, nowEpoch)
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
            StreamPlayer(
                url = api.vodUrl(profile, cameraId, start, end),
                rtsp = false,
                audible = !state.prefs.muted,
                networkEpoch = 0,
                onState = {},
                controls = true,
                retry = false,
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            )
            Text(
                "Playing ${displayName(cameraId)} from ${formatClock(start)}. Press BACK to return to the timeline.",
                color = TextMuted,
                fontSize = 16.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Camera", color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            cams.forEach { cam ->
                FocusButton(displayName(cam.id), { cameraId = cam.id }, selected = cameraId == cam.id)
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Day", color = TextMuted, fontSize = 16.sp, modifier = Modifier.padding(end = 8.dp))
            (0..DAYS_BACK).forEach { offset ->
                FocusButton(dayLabel(zone, offset), { dayOffset = offset }, selected = dayOffset == offset)
            }
        }

        val loaded = data
        val dayData = loaded?.getOrNull()
        when {
            loaded == null -> CenteredMessage("Loading recordings…")
            dayData == null -> CenteredMessage("Could not load recordings: ${loaded.exceptionOrNull()?.message}")
            else -> {
                Spacer(Modifier.weight(1f))
                Text(
                    text = formatClock(cursor),
                    color = TextPrimary,
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                )
                val inRecording = dayData.segments.any { cursor >= it.startTime && cursor < it.endTime }
                Text(
                    text = if (inRecording) "Recording available" else "No recording at this time",
                    color = if (inRecording) LiveGreen else Warn,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(12.dp))
                Timeline(
                    focusRequester = timelineRequester,
                    dayStart = dayStart,
                    dayEnd = dayEnd,
                    segments = dayData.segments,
                    events = dayData.events,
                    cursor = cursor,
                    onCursor = { cursor = it.coerceIn(dayStart, minOf(dayEnd, nowEpoch)) },
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "◀ ▶ move 5 min    OK play from here",
                        color = TextMuted,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    FocusButton("Next event", {
                        dayData.events.firstOrNull { it.startTime > cursor + 1 }?.let { cursor = it.startTime }
                    })
                    FocusButton("Play from here", { playStart = cursor }, selected = true)
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun Timeline(
    focusRequester: FocusRequester,
    dayStart: Double,
    dayEnd: Double,
    segments: List<RecordingSegment>,
    events: List<FrigateEvent>,
    cursor: Double,
    onCursor: (Double) -> Unit,
) {
    val span = dayEnd - dayStart
    FocusSurface(
        onClick = {},
        background = PanelHi,
        focusRequester = focusRequester,
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onCursor(cursor - CURSOR_STEP_SECONDS); true }
                    Key.DirectionRight -> { onCursor(cursor + CURSOR_STEP_SECONDS); true }
                    else -> false
                }
            }
            .pointerInput(dayStart) {
                detectTapGestures { offset ->
                    onCursor(dayStart + offset.x / size.width * span)
                }
            },
    ) { _ ->
        Canvas(Modifier.fillMaxSize()) {
            fun x(t: Double): Float = ((t - dayStart) / span * size.width).toFloat()

            segments.forEach { s ->
                val x1 = x(maxOf(s.startTime, dayStart))
                val x2 = x(minOf(s.endTime, dayEnd))
                if (x2 > x1) {
                    drawRect(
                        color = Accent.copy(alpha = 0.7f),
                        topLeft = Offset(x1, size.height * 0.35f),
                        size = Size(x2 - x1, size.height * 0.4f),
                    )
                }
            }
            events.forEach { e ->
                drawRect(
                    color = Warn,
                    topLeft = Offset(x(e.startTime) - 2f, 0f),
                    size = Size(4f, size.height * 0.3f),
                )
            }
            drawRect(
                color = Color.White,
                topLeft = Offset(x(cursor) - 2f, 0f),
                size = Size(4f, size.height),
            )
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

private fun dayLabel(zone: ZoneId, offset: Int): String = when (offset) {
    0 -> "Today"
    1 -> "Yesterday"
    else -> LocalDate.now(zone).minusDays(offset.toLong()).format(DateTimeFormatter.ofPattern("EEE d"))
}
