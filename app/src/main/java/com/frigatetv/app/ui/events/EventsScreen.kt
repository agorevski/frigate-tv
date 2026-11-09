package com.frigatetv.app.ui.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.frigatetv.app.AppState
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import com.frigatetv.app.ui.EmptyState
import com.frigatetv.app.ui.LoadingState
import com.frigatetv.app.ui.NumericStyle
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Events tab: filters, a day-grouped grid of events, and the detail view for one event. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventsScreen(state: AppState) {
    val profile = state.active ?: return
    val viewModel: EventsViewModel = viewModel(factory = EventsViewModel.Factory)
    val ui by viewModel.state.collectAsState()
    val repo = remember(profile) { FrigateRepository(profile) }
    LaunchedEffect(profile) { viewModel.bind(profile) }

    val cameras = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }

    var selected by remember { mutableStateOf<EventUi?>(null) }
    // Kept above the detail early return so the list keeps its scroll position and focus on the opened card.
    val gridState = rememberLazyGridState()
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    val returnFocus = remember { FocusRequester() }

    val rows = ui.rows
    val cards = ui.cards
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
            repo = repo,
            ui = if (index >= 0) cards[index] else current,
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
                FilterChip(f.title, selected = ui.label == f) { viewModel.setLabel(f) }
            }
        }
        val rangeChips: @Composable RowScope.() -> Unit = {
            TimeRange.entries.forEach { r ->
                FilterChip(r.chip, selected = ui.range == r) { viewModel.setRange(r) }
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
        val load = ui.load
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FilterGroup("Camera", Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                FilterChip("All", selected = ui.cameraId == null) { viewModel.setCamera(null) }
                cameras.forEach { cam ->
                    FilterChip(displayName(cam.id), selected = ui.cameraId == cam.id) { viewModel.setCamera(cam.id) }
                }
            }
            if (load is EventsLoad.Loaded) {
                Spacer(Modifier.width(Space.md))
                Text(
                    "$count ${ui.label.kind()}${if (count == 1) "event" else "events"}",
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
                load is EventsLoad.Loading -> LoadingState("Loading events…")
                load is EventsLoad.Failed -> EmptyState(
                    title = "Could not load events.",
                    message = load.message ?: "Check the server connection and try again.",
                )
                rows.isEmpty() -> EmptyState(
                    title = "No events for this filter.",
                    message = "No ${ui.label.kind()}events${ui.cameraId?.let { " on ${displayName(it)}" }.orEmpty()} " +
                        "in the ${ui.range.title.lowercase()}. Try a wider range or another filter.",
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
                                val card = row.ui
                                EventCard(
                                    repo = repo,
                                    ui = card,
                                    onOpen = {
                                        openedId = card.id
                                        selected = card
                                    },
                                    focusRequester = if (card.id == openedId) returnFocus else null,
                                )
                            }
                        }
                    }
                }
            }
        }
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
