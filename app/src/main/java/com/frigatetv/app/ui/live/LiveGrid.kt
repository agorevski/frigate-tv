package com.frigatetv.app.ui.live

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.frigatetv.app.AppState
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.orderedCameras
import com.frigatetv.app.player.StreamBudget
import com.frigatetv.app.ui.CenteredMessage
import com.frigatetv.app.ui.Space

/** Gap between the featured camera and the rail, and around the whole grid. */
private val Gutter = Space.md

/** Gap between rail tiles. Wider than the 4% focus scale-up so a focused tile never overlaps its neighbours. */
private val TileGap = Space.sm

/** Space reserved under the featured video for its caption. */
private val CaptionHeight = 64.dp

/** The grid of the camera list: the featured camera beside (or above, on phones) a rail of tiles. */
@Composable
internal fun LiveGrid(
    profile: ServerProfile,
    state: AppState,
    onSelectCamera: (String) -> Unit,
    onToggleMute: () -> Unit,
    tabFocus: FocusRequester,
    viewModel: LiveViewModel,
) {
    val ui by viewModel.state.collectAsState()
    val repo = remember(profile) { FrigateRepository(profile) }
    val cams = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }
    if (cams.isEmpty()) {
        CenteredMessage("No enabled cameras on this server.")
        return
    }
    val initialId = state.prefs.lastCamera?.takeIf { id -> cams.any { it.id == id } } ?: cams.first().id
    val focusedId = ui.focusedId?.takeIf { id -> cams.any { it.id == id } } ?: initialId
    val selectedId = ui.selectedId?.takeIf { id -> cams.any { it.id == id } } ?: focusedId
    var fullscreenId by rememberSaveable { mutableStateOf<String?>(null) }

    // Quality and audio follow the selected camera, but only after focus has settled.
    LaunchedEffect(focusedId) {
        viewModel.scheduleSelection(focusedId, onSelectCamera)
    }

    // Stable callbacks, so tiles skip recomposition when only another tile's state changes.
    val onFocusCamera = remember(viewModel) { { id: String -> viewModel.onFocused(id) } }
    val onOpen = remember(viewModel) {
        {
                id: String ->
            viewModel.onFocused(id)
            fullscreenId = id
        }
    }
    val onSwitch = remember(viewModel) {
        {
                id: String ->
            viewModel.switchTo(id)
            fullscreenId = id
        }
    }
    val onExit = remember { { fullscreenId = null } }

    val fullscreenCam = fullscreenId?.let { id -> cams.firstOrNull { it.id == id } }
    if (fullscreenCam != null) {
        FullscreenView(
            repo = repo,
            cams = cams,
            currentId = fullscreenCam.id,
            isDetecting = fullscreenCam.id in state.detecting,
            muted = state.prefs.muted,
            networkEpoch = state.networkEpoch,
            onSwitch = onSwitch,
            onToggleMute = onToggleMute,
            onExit = onExit,
        )
        return
    }

    // Only the fields the grid reads are passed down, so unrelated AppState changes
    // (notices, events, recordings) do not recompose it.
    CameraGrid(
        repo = repo,
        cams = cams,
        selectedId = selectedId,
        initialFocusId = focusedId,
        favorites = state.prefs.favorites,
        detecting = state.detecting,
        columnsPref = state.prefs.columns,
        muted = state.prefs.muted,
        networkEpoch = state.networkEpoch,
        tabFocus = tabFocus,
        onFocusCamera = onFocusCamera,
        onOpen = onOpen,
    )
}

/** Sizes for the featured camera and the rail. Every video box is 16:9 so no space is lost to letterboxing. */
@Immutable
private data class GridLayout(
    val featuredWidth: Dp,
    val featuredHeight: Dp,
    val columns: Int,
    val tileWidth: Dp,
    val tileHeight: Dp,
    /** Tall screens (phone portrait) stack the featured camera above the rail instead of beside it. */
    val stacked: Boolean = false,
)

private fun gridLayout(width: Dp, height: Dp, count: Int, forcedColumns: Int): GridLayout {
    if (height > width) {
        val columns = (if (forcedColumns > 0) minOf(forcedColumns, count) else minOf(2, count)).coerceAtLeast(1)
        val tileWidth = ((width - TileGap * (columns - 1)) / columns).coerceAtLeast(0.dp)
        return GridLayout(
            featuredWidth = width,
            featuredHeight = width * (9f / 16f),
            columns = columns,
            tileWidth = tileWidth,
            tileHeight = tileWidth * (9f / 16f),
            stacked = true,
        )
    }
    // With few cameras the rail needs less room, so the featured camera can grow.
    val fraction = if (count <= 2) 0.66f else 0.6f
    val featuredWidth = minOf(width * fraction, (height - CaptionHeight) * (16f / 9f)).coerceAtLeast(0.dp)
    val railWidth = (width - featuredWidth - Gutter).coerceAtLeast(1.dp)
    val columns = chooseColumns(count, railWidth, height, forcedColumns).coerceAtLeast(1)
    val rows = (count + columns - 1) / columns
    val cellWidth = (railWidth - TileGap * (columns - 1)) / columns
    val cellHeight = (height - TileGap * (rows - 1)) / rows
    val tileWidth = minOf(cellWidth, cellHeight * (16f / 9f)).coerceAtLeast(0.dp)
    return GridLayout(
        featuredWidth = featuredWidth,
        featuredHeight = featuredWidth * (9f / 16f),
        columns = columns,
        tileWidth = tileWidth,
        tileHeight = tileWidth * (9f / 16f),
    )
}

@Composable
private fun CameraGrid(
    repo: FrigateRepository,
    cams: List<CameraInfo>,
    selectedId: String,
    initialFocusId: String,
    favorites: Set<String>,
    detecting: Set<String>,
    columnsPref: Int,
    muted: Boolean,
    networkEpoch: Int,
    tabFocus: FocusRequester,
    onFocusCamera: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val requesters = remember(cams) { cams.associate { it.id to FocusRequester() } }
    // Returning from fullscreen re-enters the grid; put focus back on the camera we left.
    LaunchedEffect(Unit) {
        runCatching { requesters[initialFocusId]?.requestFocus() }
    }

    val selectedCam = cams.firstOrNull { it.id == selectedId } ?: cams.first()
    val withinBudget = remember(cams) { StreamBudget.railWithinBudget(cams.map { it.id }) }

    BoxWithConstraints(Modifier.fillMaxSize().padding(Gutter)) {
        val layout = remember(maxWidth, maxHeight, cams.size, columnsPref) {
            gridLayout(maxWidth, maxHeight, cams.size, columnsPref)
        }
        // Top-row tiles: UP must reach the tab bar even from columns far to the right
        // of the tabs, which directional search alone can miss.
        val firstRowModifier = remember(layout, tabFocus) {
            Modifier.size(layout.tileWidth, layout.tileHeight).focusProperties { up = tabFocus }
        }
        val tileModifier = remember(layout) { Modifier.size(layout.tileWidth, layout.tileHeight) }

        val featured = @Composable {
            FeaturedCamera(
                repo = repo,
                cam = selectedCam,
                isDetecting = selectedCam.id in detecting,
                muted = muted,
                networkEpoch = networkEpoch,
                // Touch screens: tapping the featured picture opens it. Not focusable, so the D-pad is unaffected.
                modifier = Modifier
                    .size(layout.featuredWidth, layout.featuredHeight)
                    .pointerInput(selectedCam.id) { detectTapGestures { onOpen(selectedCam.id) } },
            )
            FeaturedCaption(
                camId = selectedCam.id,
                isFavorite = selectedCam.id in favorites,
                muted = muted,
            )
        }
        // The rail is secondary: smaller tiles on sub streams, so the featured camera dominates.
        val rail = @Composable {
            cams.chunked(layout.columns).forEachIndexed { rowIndex, rowCams ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(TileGap, Alignment.CenterHorizontally),
                ) {
                    rowCams.forEach { cam ->
                        key(cam.id) {
                            CameraTile(
                                repo = repo,
                                cam = cam,
                                isFavorite = cam.id in favorites,
                                isDetecting = cam.id in detecting,
                                isSelected = cam.id == selectedCam.id,
                                networkEpoch = networkEpoch,
                                withinBudget = cam.id in withinBudget,
                                modifier = if (rowIndex == 0) firstRowModifier else tileModifier,
                                focusRequester = requesters.getValue(cam.id),
                                onFocus = onFocusCamera,
                                onOpen = onOpen,
                            )
                        }
                    }
                }
            }
        }

        if (layout.stacked) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TileGap),
            ) {
                Column { featured() }
                Spacer(Modifier.height(Space.xs))
                rail()
            }
        } else {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Gutter)) {
                Column(Modifier.width(layout.featuredWidth)) { featured() }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(TileGap)) { rail() }
            }
        }
    }
}

/**
 * Picks the column count that gives the largest 16:9-fitting tiles for [count] tiles.
 * Ties go to more columns (wider tiles read better from a couch).
 */
fun chooseColumns(count: Int, width: Dp, height: Dp, forced: Int): Int {
    if (forced > 0) return minOf(forced, count)
    var best = 1
    var bestScore = -1f
    for (cols in 1..count) {
        val rows = (count + cols - 1) / cols
        val tw = width.value / cols
        val th = height.value / rows
        val fitW = minOf(tw, th * 16f / 9f)
        val fitH = minOf(th, tw * 9f / 16f)
        val score = fitW * fitH
        if (score >= bestScore) {
            bestScore = score
            best = cols
        }
    }
    return best
}
