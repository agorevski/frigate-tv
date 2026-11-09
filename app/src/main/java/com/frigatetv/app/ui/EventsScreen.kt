package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class LabelFilter(val title: String) {
    ALL("All"),
    PERSON("People"),
    VEHICLE("Vehicles"),
    OTHER("Other"),
}

private enum class TimeRange(val title: String, val chip: String, val seconds: Long) {
    DAY("Last 24 hours", "24h", 86_400L),
    WEEK("Last 7 days", "7d", 604_800L),
    MONTH("Last 30 days", "30d", 2_592_000L),
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

@OptIn(ExperimentalLayoutApi::class)
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

    val loaded = result
    val events = loaded?.getOrNull()
    val rows = remember(events, label) { events?.let { buildRows(it, label) }.orEmpty() }
    // The filtered events in grid order (newest first); the detail view pages through these.
    val cards = remember(rows) { rows.mapNotNull { (it as? CardRow)?.ui } }
    val count = cards.size

    // Back on the list: bring the card of the event last shown into view and focus it, even if
    // the detail view paged to an event that was never on screen.
    LaunchedEffect(selected) {
        if (selected != null) return@LaunchedEffect
        val id = openedId
        val rowIndex = if (id == null) -1 else rows.indexOfFirst { it is CardRow && it.ui.id == id }
        if (rowIndex >= 0) {
            withFrameNanos {}
            val layout = gridState.layoutInfo
            val shown = layout.visibleItemsInfo.firstOrNull { it.index == rowIndex }
            val fullyVisible = shown != null && shown.offset.y >= layout.viewportStartOffset &&
                shown.offset.y + shown.size.height <= layout.viewportEndOffset
            if (!fullyVisible) {
                // Keep the day header above the card in view when there is one.
                val target = if (rowIndex > 0 && rows[rowIndex - 1] is DayRow) rowIndex - 1 else rowIndex
                gridState.scrollToItem(target)
            }
            withTimeoutOrNull(1_000) {
                snapshotFlow { gridState.layoutInfo.visibleItemsInfo.any { it.key == id } }.first { it }
            }
            withFrameNanos {}
        }
        runCatching { returnFocus.requestFocus() }
    }

    val current = selected
    if (current != null) {
        // Track the open event by id so a refreshed list keeps it; if it disappeared, stay on it.
        val index = cards.indexOfFirst { it.id == current.id }
        BackHandler { selected = null }
        EventDetail(
            profile,
            if (index >= 0) cards[index] else current,
            muted = state.prefs.muted,
            position = index,
            total = cards.size,
            onStep = { step ->
                val next = if (index >= 0) cards.getOrNull(index + step) else null
                if (next != null) {
                    selected = next
                    openedId = next.id
                }
                next != null
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md)) {
        val typeChips: @Composable RowScope.() -> Unit = {
            LabelFilter.entries.forEach { f ->
                FilterChip(f.title, selected = label == f) { label = f }
            }
        }
        val rangeChips: @Composable RowScope.() -> Unit = {
            TimeRange.entries.forEach { r ->
                FilterChip(r.chip, selected = range == r) { range = r }
            }
        }
        // Groups sit side by side when they fit (Range pushed to the end) and wrap onto their own line on
        // narrow phones. Each group also scrolls horizontally, so a chip is never squeezed or clipped.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            FilterGroup("Type", Modifier.horizontalScroll(rememberScrollState()), typeChips)
            FilterGroup("Range", Modifier.horizontalScroll(rememberScrollState()), rangeChips)
        }
        Spacer(Modifier.height(Space.xs))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterGroup("Camera", Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
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
                    softWrap = false,
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
            maxLines = 1,
            softWrap = false,
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
        // Hug the label: never stretched or squeezed by the parent row.
        modifier = Modifier.width(IntrinsicSize.Max).heightIn(min = 40.dp),
    ) { _ ->
        Text(
            text,
            color = if (selected) Ink else TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            softWrap = false,
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

/** Horizontal travel (as a fraction of the width) that the next event slides in from. */
private const val SLIDE_FRACTION = 0.3f

/** Drag resistance past the first or last event: the content gives a little, then springs back. */
private const val EDGE_RESISTANCE = 0.25f

/** Delay before a paged-to clip starts decoding, so flicking through events never stacks decoders. */
private const val PLAYER_SETTLE_MS = 300L

/**
 * One event full screen. A horizontal swipe (phone) or D-pad LEFT/RIGHT (TV, while the player
 * controls are not focused) calls [onStep] with +1 for the next event in grid order and -1 for the
 * previous one; it returns false at either end. [position] is the event's index in the list of
 * [total] events, or -1 when it is no longer in the list.
 */
@Composable
private fun EventDetail(
    profile: ServerProfile,
    ui: EventUi,
    muted: Boolean,
    position: Int,
    total: Int,
    onStep: (Int) -> Boolean,
) {
    val event = ui.event
    val playing = event.hasClip
    val hasPrev = position > 0
    val hasNext = position in 0 until total - 1
    val currentOnStep by rememberUpdatedState(onStep)
    val canPrev by rememberUpdatedState(hasPrev)
    val canNext by rememberUpdatedState(hasNext)
    // Any key press or touch re-shows the overlay; it fades after a pause while a clip plays.
    var pokes by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    LaunchedEffect(pokes, playing, ui.id) {
        overlayVisible = true
        if (playing) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        }
    }
    val overlayAlpha by animateFloatAsState(if (overlayVisible) 1f else 0f, tween(Motion.EnterMs), label = "overlay")

    // Only one clip decodes at a time: after paging, the still is shown until the user settles.
    var armedId by remember { mutableStateOf(ui.id) }
    LaunchedEffect(ui.id) {
        if (armedId != ui.id) {
            delay(PLAYER_SETTLE_MS)
            armedId = ui.id
        }
    }

    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    // Direction of the last successful step; the new event slides in from that side.
    var enterFrom by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.id) {
        if (enterFrom != 0) {
            offset.snapTo(enterFrom * widthPx * SLIDE_FRACTION)
            enterFrom = 0
            offset.animateTo(0f, tween(Motion.EnterMs, easing = FastOutSlowInEasing))
        }
    }
    val bumpPx = with(LocalDensity.current) { Space.lg.toPx() }
    fun step(dir: Int) {
        pokes++
        if (currentOnStep(dir)) {
            enterFrom = dir
        } else {
            // At either end: a small nudge toward the missing neighbour, then back.
            scope.launch {
                offset.animateTo(-dir * bumpPx, tween(Motion.FocusMs))
                offset.animateTo(0f, tween(Motion.ExitMs, easing = FastOutSlowInEasing))
            }
        }
    }

    val hostView = LocalView.current
    val isTv = isTelevision()
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = enter, enter = fadeIn(tween(Motion.EnterMs))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Ink)
                .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                .onPreviewKeyEvent { e ->
                    pokes++
                    val dir = when (e.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    // LEFT/RIGHT page between events unless the player's own controls have focus.
                    if (dir == 0 || playerControlsFocused(hostView)) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown) step(dir)
                    true
                }
                .pointerInput(Unit) {
                    val threshold = 72.dp.toPx()
                    val flingVelocity = 600.dp.toPx()
                    // Leave the bottom strip to the player's seek bar.
                    val seekBarStrip = 88.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pokes++
                        if (down.position.y > size.height - seekBarStrip) return@awaitEachGesture
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var dragging = false
                        var dx = 0f
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (dragging) change.consume()
                                break
                            }
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val moved = change.position - down.position
                            if (!dragging) {
                                if (abs(moved.x) > viewConfiguration.touchSlop && abs(moved.x) > abs(moved.y) * 1.5f) {
                                    dragging = true
                                } else if (abs(moved.y) > viewConfiguration.touchSlop) {
                                    return@awaitEachGesture
                                }
                            }
                            if (dragging) {
                                // Consuming cancels the touch for the player, so a swipe never toggles its controls.
                                change.consume()
                                dx = moved.x
                                val open = if (dx < 0) canNext else canPrev
                                val follow = if (open) dx else dx * EDGE_RESISTANCE
                                scope.launch { offset.snapTo(follow) }
                            }
                        }
                        if (!dragging) return@awaitEachGesture
                        val v = tracker.calculateVelocity().x
                        // Swipe left (finger moves left) shows the next event, like turning a page.
                        val dir = when {
                            dx < 0 && (dx < -threshold || v < -flingVelocity) -> 1
                            dx > 0 && (dx > threshold || v > flingVelocity) -> -1
                            else -> 0
                        }
                        pokes++
                        if (dir != 0 && currentOnStep(dir)) {
                            enterFrom = dir
                        } else {
                            scope.launch { offset.animateTo(0f, tween(Motion.ExitMs, easing = FastOutSlowInEasing)) }
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationX = offset.value
                        alpha = 1f - (abs(offset.value) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.6f)
                    },
            ) {
                when {
                    playing && armedId == ui.id -> StreamPlayer(
                        url = remember(profile, ui.id) { api.eventClipUrl(profile, ui.id) },
                        rtsp = false,
                        audible = !muted,
                        networkEpoch = 0,
                        onState = {},
                        controls = true,
                        retry = false,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = if (event.hasSnapshot) { { EventStill(profile, ui) } } else null,
                    )
                    playing -> if (event.hasSnapshot) EventStill(profile, ui) else LoadingState("Loading clip…")
                    event.hasSnapshot -> {
                        EventStill(profile, ui)
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
            }

            // Previous / next affordances at the side edges, shown with the overlay. Tappable on phones.
            if (position >= 0 && total > 1) {
                PageArrow("‹", enabled = hasPrev, Modifier.align(Alignment.CenterStart), { overlayAlpha }) { step(-1) }
                PageArrow("›", enabled = hasNext, Modifier.align(Alignment.CenterEnd), { overlayAlpha }) { step(1) }
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
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(Space.md))
                Column(horizontalAlignment = Alignment.End) {
                    if (position >= 0 && total > 1) {
                        Text(
                            "${position + 1} / $total",
                            color = TextPrimary,
                            style = MaterialTheme.typography.labelLarge.merge(NumericStyle),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier
                                .clip(BadgeShape)
                                .background(BadgeBg)
                                .padding(horizontal = Space.xs, vertical = Space.xxs),
                        )
                        Spacer(Modifier.height(Space.xxs))
                    }
                    Text(
                        text = when {
                            total <= 1 || position < 0 -> if (isTv) "Press BACK to return to the list." else "Back returns to the list."
                            isTv -> "◀ ▶ browse events · BACK returns to the list."
                            else -> "Swipe to browse events."
                        },
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The event's full snapshot, letterboxed. Shown for snapshot-only events and while a clip connects. */
@Composable
private fun EventStill(profile: ServerProfile, ui: EventUi) {
    RemoteImage(
        remember(profile, ui.id) { api.eventSnapshotUrl(profile, ui.id) },
        refreshMs = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Round ‹ › hint at a side edge of the detail view. Not focusable: the D-pad pages with LEFT/RIGHT. */
@Composable
private fun PageArrow(glyph: String, enabled: Boolean, modifier: Modifier, alpha: () -> Float, onTap: () -> Unit) {
    Box(
        modifier
            .padding(horizontal = Space.sm)
            .size(44.dp)
            .graphicsLayer { this.alpha = alpha() * if (enabled) 0.9f else 0.3f }
            .clip(CircleShape)
            .background(BadgeBg)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures { if (alpha() > 0.5f) onTap() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = TextPrimary, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
    }
}

/** True when focus is inside the media3 player's control bar (play/pause, seek bar, ...). */
private fun playerControlsFocused(host: View): Boolean {
    val focused = host.rootView.findFocus() ?: return false
    return generateSequence(focused) { it.parent as? View }.any {
        it.id == androidx.media3.ui.R.id.exo_controller || it.javaClass.simpleName == "PlayerControlView"
    }
}


private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))
