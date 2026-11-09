package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateEvent
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private enum class LabelFilter(val title: String) {
    ALL("All"),
    PERSON("People"),
    VEHICLE("Vehicles"),
    OTHER("Other"),
}

private enum class TimeRange(val title: String, val chip: String, val seconds: Long) {
    DAY("Last 24 hours", "24 hours", 86_400L),
    WEEK("Last 7 days", "7 days", 604_800L),
    MONTH("Last 30 days", "30 days", 2_592_000L),
}

private val VEHICLE_LABELS = setOf("car", "truck", "motorcycle", "bus", "bicycle")
private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm:ss")
private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, MMM d")
private val api = FrigateApi()

/** Hides the clip overlay after this long without a key press. */
private const val OVERLAY_HIDE_MS = 8_000L

private fun LabelFilter.matches(label: String): Boolean = when (this) {
    LabelFilter.ALL -> true
    LabelFilter.PERSON -> label == "person"
    LabelFilter.VEHICLE -> label in VEHICLE_LABELS
    LabelFilter.OTHER -> label != "person" && label !in VEHICLE_LABELS
}

fun formatEventTime(epochSeconds: Double): String =
    TIME_FORMAT.format(Instant.ofEpochMilli((epochSeconds * 1000).toLong()).atZone(ZoneId.systemDefault()))

/** An event with every display string precomputed off the main thread, so cards only lay out text. */
@Immutable
private class EventUi(
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
private sealed interface GridRow
private class DayRow(val key: String, val title: String) : GridRow
private class CardRow(val ui: EventUi) : GridRow

private fun FrigateEvent.title(): String =
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

private fun toUi(e: FrigateEvent, nowSeconds: Double, zone: ZoneId): EventUi {
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

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DAY_FORMAT.format(day)
}

private fun buildRows(events: List<EventUi>, label: LabelFilter): List<GridRow> {
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

@Composable
fun EventsScreen(state: AppState) {
    val profile = state.active ?: return
    val cameras = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }

    var label by rememberSaveable { mutableStateOf(LabelFilter.ALL) }
    var range by rememberSaveable { mutableStateOf(TimeRange.DAY) }
    var cameraId by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<EventUi?>(null) }
    // Kept above the detail early return so the list keeps its scroll position and focus on the opened card.
    val gridState = rememberLazyGridState()
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }
    LaunchedEffect(selected) {
        if (selected == null) runCatching { returnFocus.requestFocus() }
    }

    val result by produceState<Result<List<EventUi>>?>(null, profile, range, cameraId) {
        val now = System.currentTimeMillis() / 1000.0
        value = runCatching {
            val events = api.events(profile, limit = 200, after = now - range.seconds, camera = cameraId)
            withContext(Dispatchers.Default) {
                val zone = ZoneId.systemDefault()
                events.map { toUi(it, now, zone) }
            }
        }
    }

    val current = selected
    if (current != null) {
        BackHandler { selected = null }
        EventDetail(profile, current, muted = state.prefs.muted)
        return
    }

    val loaded = result
    val events = loaded?.getOrNull()
    val rows = remember(events, label) { events?.let { buildRows(it, label) }.orEmpty() }
    val count = remember(rows) { rows.count { it is CardRow } }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterGroup("Type") {
                LabelFilter.entries.forEach { f ->
                    FilterChip(f.title, selected = label == f) { label = f }
                }
            }
            Spacer(Modifier.weight(1f))
            FilterGroup("Range") {
                TimeRange.entries.forEach { r ->
                    FilterChip(r.chip, selected = range == r) { range = r }
                }
            }
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterGroup("Camera", Modifier.weight(1f)) {
                FilterChip("All", selected = cameraId == null) { cameraId = null }
                cameras.forEach { cam ->
                    FilterChip(displayName(cam.id), selected = cameraId == cam.id) { cameraId = cam.id }
                }
            }
            if (loaded?.isSuccess == true) {
                Spacer(Modifier.width(Space.md))
                Text(
                    "$count ${label.kind()}${if (count == 1) "event" else "events"}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.titleMedium.merge(NumericStyle),
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(Space.sm))

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                loaded == null -> LoadingState("Loading events…")
                loaded.isFailure -> EmptyState(
                    title = "Could not load events.",
                    message = loaded.exceptionOrNull()?.message ?: "Check the server connection and try again.",
                )
                rows.isEmpty() -> EmptyState(
                    title = "No events for this filter.",
                    message = "No ${label.kind()}events${cameraId?.let { " on ${displayName(it)}" }.orEmpty()} " +
                        "in the ${range.title.lowercase()}. Try a wider range or another filter.",
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 200.dp),
                    state = gridState,
                    // Room for the focus ring and scale-up so edge cards are not clipped.
                    contentPadding = PaddingValues(horizontal = Space.xxs, vertical = Space.xs),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        rows,
                        key = { row -> if (row is CardRow) row.ui.id else (row as DayRow).key },
                        span = { row -> GridItemSpan(if (row is DayRow) maxLineSpan else 1) },
                        contentType = { row -> if (row is CardRow) 1 else 0 },
                    ) { row ->
                        when (row) {
                            is DayRow -> DayHeader(row.title)
                            is CardRow -> {
                                val ui = row.ui
                                EventCard(
                                    profile,
                                    ui,
                                    onOpen = {
                                        openedId = ui.id
                                        selected = ui
                                    },
                                    focusRequester = if (ui.id == openedId) returnFocus else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Filter kind prefix for copy, e.g. "person " in "3 person events". Empty for All. */
private fun LabelFilter.kind(): String = when (this) {
    LabelFilter.ALL -> ""
    LabelFilter.PERSON -> "person "
    LabelFilter.VEHICLE -> "vehicle "
    LabelFilter.OTHER -> "other "
}

/** A small caption followed by its chips on one line. */
@Composable
private fun FilterGroup(title: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(end = Space.xxs),
        )
        content()
    }
}

private val ChipShape = RoundedCornerShape(50)

/** Compact pill filter. Selected chips fill with the accent; focus adds the shared ring. */
@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FocusSurface(
        onClick = onClick,
        background = if (selected) Accent else PanelHi,
        shape = ChipShape,
        contentPadding = PaddingValues(horizontal = Space.sm, vertical = 6.dp),
        modifier = Modifier.heightIn(min = 40.dp),
    ) { _ ->
        Text(
            text,
            color = if (selected) Ink else TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
    }
}

@Composable
private fun DayHeader(title: String) {
    Text(
        title,
        color = TextPrimary,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = Space.xs),
    )
}

private val CardShape = RoundedCornerShape(Radius.lg)
private val BadgeShape = RoundedCornerShape(Radius.sm)
private val BadgeBg = Ink.copy(alpha = 0.75f)

/** Small dark chip drawn over a thumbnail. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Row(
        modifier.clip(BadgeShape).background(BadgeBg).padding(horizontal = Space.xs, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(8.dp).background(dot, CircleShape))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = TextPrimary, style = MaterialTheme.typography.labelMedium.merge(NumericStyle))
    }
}

@Composable
private fun EventCard(
    profile: ServerProfile,
    ui: EventUi,
    onOpen: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val thumbUrl = remember(profile, ui.id) {
        if (ui.event.hasSnapshot) api.eventThumbnailUrl(profile, ui.id) else null
    }
    FocusSurface(
        onClick = onOpen,
        background = Panel,
        shape = CardShape,
        focusRequester = focusRequester,
        modifier = Modifier.fillMaxWidth(),
    ) { _ ->
        // Text order matters to UI tests: title, camera, then time.
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink)) {
                if (thumbUrl != null) {
                    // Frigate's thumbnail.jpg is a small crop, cheap to fetch and decode for a grid.
                    RemoteImage(thumbUrl, refreshMs = null, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        "No preview",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Column(Modifier.padding(horizontal = Space.sm, vertical = Space.xs)) {
                Text(
                    text = ui.title,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    ui.camera,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ui.time,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                        maxLines = 1,
                    )
                    if (ui.relative != null) {
                        Text(
                            " · ${ui.relative}",
                            color = TextSecondary.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyMedium.merge(NumericStyle),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        // Overlays on the thumbnail, composed last so they draw on top.
        Box(Modifier.matchParentSize().padding(Space.xs)) {
            if (ui.score != null) Badge(ui.score, Modifier.align(Alignment.TopStart))
            Badge(
                ui.duration,
                Modifier.align(Alignment.TopEnd),
                dot = if (ui.ongoing) LiveGreen else null,
            )
        }
    }
}

@Composable
private fun EventDetail(profile: ServerProfile, ui: EventUi, muted: Boolean) {
    val event = ui.event
    val playing = event.hasClip
    // Any key press re-shows the overlay; it fades after a pause while a clip plays.
    var pokes by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pokes, playing) {
        overlayVisible = true
        if (playing) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }
    val overlayAlpha by animateFloatAsState(if (overlayVisible) 1f else 0f, tween(Motion.EnterMs), label = "overlay")

    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = enter, enter = fadeIn(tween(Motion.EnterMs))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .onPreviewKeyEvent {
                    pokes++
                    false
                },
        ) {
            when {
                playing -> StreamPlayer(
                    url = remember(profile, ui.id) { api.eventClipUrl(profile, ui.id) },
                    rtsp = false,
                    audible = !muted,
                    networkEpoch = 0,
                    onState = {},
                    controls = true,
                    retry = false,
                    modifier = Modifier.fillMaxSize(),
                )
                event.hasSnapshot -> {
                    RemoteImage(
                        remember(profile, ui.id) { api.eventSnapshotUrl(profile, ui.id) },
                        refreshMs = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "No recording available for this event.",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = Space.xl)
                            .clip(RoundedCornerShape(Radius.md))
                            .background(BadgeBg)
                            .padding(horizontal = Space.md, vertical = Space.xs),
                    )
                }
                else -> EmptyState(
                    title = "No clip or snapshot available.",
                    message = "Frigate did not keep media for this event.",
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = overlayAlpha }
                    .background(TopScrim)
                    .padding(start = Space.xl, end = Space.xl, top = Space.lg, bottom = Space.xxl),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = ui.title,
                            color = TextPrimary,
                            style = MaterialTheme.typography.headlineMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (ui.score != null) {
                            Spacer(Modifier.width(Space.sm))
                            StatusPill(ui.score, Accent)
                        }
                    }
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        text = "${ui.camera} · ${ui.time} · ${ui.duration}",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge.merge(NumericStyle),
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(Space.md))
                Text(
                    text = "Press BACK to return to the list.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Space.xs),
                )
            }
        }
    }
}

private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))
