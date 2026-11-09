package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.frigatetv.app.AppState
import com.frigatetv.app.Connection
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import java.util.Date
import kotlinx.coroutines.delay

private val api = FrigateApi()

/** Upper bound on simultaneously decoding streams. Tiles past this show a paused placeholder. */
private const val MAX_LIVE_STREAMS = 6
/** Selecting a camera switches it to the main stream only after focus rests this long. */
private const val SELECT_DEBOUNCE_MS = 800L
/** Fullscreen overlay hides after this much inactivity. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

/** Gap between the featured camera and the rail, and around the whole grid. */
private val Gutter = Space.md
/** Gap between rail tiles. Wider than the 4% focus scale-up so a focused tile never overlaps its neighbours. */
private val TileGap = Space.sm
/** Space reserved under the featured video for its caption. */
private val CaptionHeight = 64.dp

// Shapes and colors are allocated once, not per composition.
private val TileShape = RoundedCornerShape(Radius.md)
private val FeaturedShape = RoundedCornerShape(Radius.lg)
private val BadgeShape = RoundedCornerShape(Radius.sm)
private val KeyCapShape = RoundedCornerShape(Space.xxs)
private val BadgeBg = Ink.copy(alpha = 0.6f)
private val DimBg = Ink.copy(alpha = 0.55f)

// Gradients without explicit bounds stretch to whatever they are drawn into, so one instance serves every tile.
private val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.85f)))
private val TopFade = Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))

@Composable
fun LiveScreen(
    state: AppState,
    onSelectCamera: (String) -> Unit,
    onToggleMute: () -> Unit,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    tabFocus: FocusRequester,
) {
    val profile = state.active ?: return
    when (val conn = state.connection) {
        Connection.Idle, Connection.Connecting -> LoadingState("Connecting to ${profile.name}…")
        is Connection.Failed -> ConnectionFailedPanel(profile, conn.reason, onReconnect, onOpenSettings)
        is Connection.Connected -> LiveGrid(profile, state, onSelectCamera, onToggleMute, tabFocus)
    }
}

@Composable
private fun LiveGrid(
    profile: ServerProfile,
    state: AppState,
    onSelectCamera: (String) -> Unit,
    onToggleMute: () -> Unit,
    tabFocus: FocusRequester,
) {
    val cams = remember(state.cameras, state.prefs.cameraOrder) {
        orderedCameras(state.cameras, state.prefs.cameraOrder).filter { it.enabled }
    }
    if (cams.isEmpty()) {
        CenteredMessage("No enabled cameras on this server.")
        return
    }
    val initialId = state.prefs.lastCamera?.takeIf { id -> cams.any { it.id == id } } ?: cams.first().id

    var focusedId by rememberSaveable { mutableStateOf(initialId) }
    var selectedId by remember { mutableStateOf(initialId) }
    var fullscreenId by rememberSaveable { mutableStateOf<String?>(null) }

    // Quality and audio follow the selected camera, but only after focus has settled.
    LaunchedEffect(focusedId) {
        delay(SELECT_DEBOUNCE_MS)
        selectedId = focusedId
        onSelectCamera(focusedId)
    }

    // Stable callbacks, so tiles skip recomposition when only another tile's state changes.
    val onFocusCamera = remember { { id: String -> focusedId = id } }
    val onOpen = remember {
        { id: String ->
            focusedId = id
            fullscreenId = id
        }
    }
    val onSwitch = remember {
        { id: String ->
            focusedId = id
            selectedId = id
            fullscreenId = id
        }
    }
    val onExit = remember { { fullscreenId = null } }

    val fullscreenCam = fullscreenId?.let { id -> cams.firstOrNull { it.id == id } }
    if (fullscreenCam != null) {
        FullscreenView(
            profile = profile,
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
        profile = profile,
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
    profile: ServerProfile,
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
    // The featured stream takes one decoder slot, so the rail gets one fewer than the cap.
    val railBudget = MAX_LIVE_STREAMS - 1

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
                profile = profile,
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
                    rowCams.forEachIndexed { colIndex, cam ->
                        key(cam.id) {
                            CameraTile(
                                profile = profile,
                                cam = cam,
                                isFavorite = cam.id in favorites,
                                isDetecting = cam.id in detecting,
                                isSelected = cam.id == selectedCam.id,
                                networkEpoch = networkEpoch,
                                withinBudget = rowIndex * layout.columns + colIndex < railBudget,
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

@Composable
private fun CameraTile(
    profile: ServerProfile,
    cam: CameraInfo,
    isFavorite: Boolean,
    isDetecting: Boolean,
    isSelected: Boolean,
    networkEpoch: Int,
    withinBudget: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocus: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    // Rail tiles use the sub stream; the featured camera carries the main stream and audio.
    val url = remember(profile, cam) { liveUrl(profile, cam, main = false) }
    var status by remember(cam.id, url) { mutableStateOf(StreamState.Connecting) }
    val name = remember(cam.id, isFavorite) { displayName(cam.id) + if (isFavorite) "  ★" else "" }
    val playing = url != null && withinBudget
    val hasPicture = playing && (status == StreamState.Live || status == StreamState.Buffering)

    FocusSurface(
        onClick = { onOpen(cam.id) },
        modifier = modifier,
        // Black behind video so any letterbox matches the picture; a panel while there is no picture.
        background = if (hasPicture) Color.Black else Panel,
        shape = TileShape,
        focusRequester = focusRequester,
        onFocus = { if (it) onFocus(cam.id) },
    ) { _ ->
        if (playing) {
            StreamPlayer(
                url = url!!,
                rtsp = true,
                audible = false,
                networkEpoch = networkEpoch,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when {
            url == null -> TileCaption("No stream configured")
            !withinBudget -> {
                // Paused tiles still show the camera, from a slowly refreshed snapshot.
                RemoteImage(
                    url = api.cameraSnapshotUrl(profile, cam.id),
                    refreshMs = 30_000,
                    modifier = Modifier.fillMaxSize(),
                )
                TileCaption("Paused · OK to watch", dim = true)
            }
            status == StreamState.Offline -> {
                RemoteImage(
                    url = api.cameraSnapshotUrl(profile, cam.id),
                    refreshMs = 10_000,
                    modifier = Modifier.fillMaxSize(),
                )
                TileCaption("Offline · retrying", dim = true)
            }
            status == StreamState.Connecting -> TileCaption("Connecting…")
        }

        if (isDetecting) {
            StatusPill("MOTION", Warn, Modifier.align(Alignment.TopStart).padding(Space.xs))
        }

        // Name and status share one bottom strip over a fade, so the picture above stays clean.
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(BottomFade)
                .padding(start = Space.xs, end = Space.xs, top = Space.md, bottom = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelected) {
                // Marks the camera currently shown in the featured view.
                Box(Modifier.size(Space.xs).background(Accent, CircleShape))
                Spacer(Modifier.width(Space.xxs + 2.dp))
            }
            Text(
                text = name,
                color = TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                url == null -> Unit
                !withinBudget -> StreamBadge("PAUSED", TextSecondary)
                else -> StreamBadge(status.label, status.color())
            }
        }
    }
}

/** Centered note over a tile with no live picture. [dim] darkens a snapshot underneath so the text reads. */
@Composable
private fun TileCaption(text: String, dim: Boolean = false) {
    Box(
        Modifier.fillMaxSize().then(if (dim) Modifier.background(DimBg) else Modifier).padding(Space.xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The camera being watched. Not focusable: the grid rail keeps focus; this shows the selected camera at full size. */
@Composable
private fun FeaturedCamera(
    profile: ServerProfile,
    cam: CameraInfo,
    isDetecting: Boolean,
    muted: Boolean,
    networkEpoch: Int,
    modifier: Modifier,
) {
    val url = remember(profile, cam) { liveUrl(profile, cam, main = true) }
    var status by remember(cam.id, url) { mutableStateOf(StreamState.Connecting) }
    val hasPicture = status == StreamState.Live || status == StreamState.Buffering

    Box(modifier.clip(FeaturedShape).background(if (url != null && hasPicture) Color.Black else Panel)) {
        if (url == null) {
            TileCaption("No stream configured for ${displayName(cam.id)}")
        } else {
            StreamPlayer(
                url = url,
                rtsp = true,
                audible = !muted,
                networkEpoch = networkEpoch,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
            when (status) {
                // Static text rather than an animated loader: the decoders are busy starting up.
                StreamState.Connecting -> TileCaption("Connecting to ${displayName(cam.id)}…")
                StreamState.Offline -> {
                    RemoteImage(
                        url = api.cameraSnapshotUrl(profile, cam.id),
                        refreshMs = 10_000,
                        modifier = Modifier.fillMaxSize(),
                    )
                    TileCaption("${displayName(cam.id)} is offline. Retrying…", dim = true)
                }
                else -> Unit
            }
        }

        if (isDetecting) {
            StatusPill("MOTION", Warn, Modifier.align(Alignment.TopStart).padding(Space.sm))
        }
        if (url != null) {
            StreamBadge(status.label, status.color(), Modifier.align(Alignment.TopEnd).padding(Space.sm))
        }
        // Only shown when muted: the audible state is the default and needs no label.
        if (muted) {
            StreamBadge("MUTED", TextSecondary, Modifier.align(Alignment.BottomEnd).padding(Space.sm))
        }
    }
}

/** Name and hint under the featured video, so nothing covers the main picture's lower edge. */
@Composable
private fun FeaturedCaption(camId: String, isFavorite: Boolean, muted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = displayName(camId) + if (isFavorite) "  ★" else "",
                color = TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (muted) "Main stream · Muted" else "Main stream · Audio on",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }
        if (isTelevision()) {
            Spacer(Modifier.width(Space.md))
            KeyHint("OK", "Fullscreen")
        }
    }
}

/** Compact status: a colored dot and a label on a translucent chip. Static, so it costs nothing per frame. */
@Composable
private fun StreamBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(BadgeBg, BadgeShape)
            .padding(horizontal = Space.xs, vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(Space.xs).background(color, CircleShape))
        Spacer(Modifier.width(Space.xxs + 2.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** A remote key drawn as a key cap, followed by what it does. */
@Composable
private fun KeyHint(keyLabel: String, action: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = keyLabel,
            color = TextPrimary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .background(PanelHi, KeyCapShape)
                .padding(horizontal = Space.xs, vertical = Space.xxs / 2),
        )
        Spacer(Modifier.width(Space.xs))
        Text(action, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun FullscreenView(
    profile: ServerProfile,
    cams: List<CameraInfo>,
    currentId: String,
    isDetecting: Boolean,
    muted: Boolean,
    networkEpoch: Int,
    onSwitch: (String) -> Unit,
    onToggleMute: () -> Unit,
    onExit: () -> Unit,
) {
    val index = cams.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val cam = cams[index]
    val url = remember(profile, cam) { liveUrl(profile, cam, main = true) }
    var status by remember(currentId) { mutableStateOf(StreamState.Connecting) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val rootRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    // Read once per interaction: the overlay is only up for a few seconds, so no ticking clock is needed.
    val clock = remember(interaction) { timeFormat.format(Date()) }

    // Any interaction shows the controls for a few seconds.
    LaunchedEffect(interaction) {
        controlsVisible = true
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }
    LaunchedEffect(controlsVisible) {
        // The Mute button leaves with the overlay; keep focus on the root so the D-pad keeps working.
        if (!controlsVisible) runCatching { rootRequester.requestFocus() }
    }
    LaunchedEffect(Unit) {
        runCatching { rootRequester.requestFocus() }
    }
    BackHandler(onBack = onExit)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootRequester)
            .focusable()
            // Touch screens: a tap toggles the overlay, like OK on the remote.
            .pointerInput(Unit) {
                detectTapGestures { if (controlsVisible) controlsVisible = false else interaction++ }
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onSwitch(cams[(index - 1 + cams.size) % cams.size].id)
                        interaction++
                        true
                    }
                    Key.DirectionRight -> {
                        onSwitch(cams[(index + 1) % cams.size].id)
                        interaction++
                        true
                    }
                    else -> {
                        interaction++
                        false
                    }
                }
            }
            .onKeyEvent { event ->
                // OK/Enter toggles the overlay when nothing inside it has focus.
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter)
                ) {
                    if (controlsVisible) controlsVisible = false else interaction++
                    true
                } else {
                    false
                }
            },
    ) {
        if (url == null) {
            CenteredMessage("No stream configured for ${displayName(cam.id)}")
        } else {
            StreamPlayer(
                url = url,
                rtsp = true,
                audible = !muted,
                networkEpoch = networkEpoch,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (url != null && status == StreamState.Connecting) {
            LoadingState(
                label = "Connecting to ${displayName(cam.id)}",
                modifier = Modifier.background(Ink.copy(alpha = 0.6f)),
            )
        }

        if (status == StreamState.Offline) {
            RemoteImage(
                url = api.cameraSnapshotUrl(profile, cam.id),
                refreshMs = 10_000,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
            Box(Modifier.fillMaxSize().background(DimBg), contentAlignment = Alignment.Center) {
                Text(
                    "${displayName(cam.id)} is offline. Retrying…",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = fadeIn(tween(Motion.EnterMs)),
            exit = fadeOut(tween(Motion.EnterMs)),
            label = "fullscreenTop",
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(TopFade)
                    .padding(start = Space.xl, end = Space.xl, top = Space.lg, bottom = Space.xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayName(cam.id),
                        color = TextPrimary,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(Space.xxs))
                    Text(
                        text = "Camera ${index + 1} of ${cams.size}  ·  $clock",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyLarge.merge(NumericStyle),
                    )
                }
                Spacer(Modifier.width(Space.md))
                if (isDetecting) {
                    StatusPill("MOTION", Warn)
                    Spacer(Modifier.width(Space.xs))
                }
                if (url != null) StreamBadge(status.label, status.color())
                Spacer(Modifier.width(Space.md))
                FocusButton(if (muted) "Unmute" else "Mute", onToggleMute)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = fadeIn(tween(Motion.EnterMs)),
            exit = fadeOut(tween(Motion.EnterMs)),
            label = "fullscreenBottom",
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(BottomFade)
                    .padding(top = Space.xxl, bottom = Space.lg),
                horizontalArrangement = Arrangement.spacedBy(Space.xl, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isTelevision()) {
                    KeyHint("◀ ▶", "Switch camera")
                    KeyHint("OK", "Hide controls")
                    KeyHint("BACK", "Grid")
                } else {
                    Text("Tap to hide controls · Back returns to the grid", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ConnectionFailedPanel(
    profile: ServerProfile,
    reason: String,
    onReconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(Space.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Can't reach ${profile.name}",
            color = TextPrimary,
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(Space.md))
        Text(profile.baseUrl, color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.xs))
        Text(reason, color = Danger, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Space.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(color = Warn, active = true)
            Spacer(Modifier.width(Space.xs))
            Text("Retrying automatically.", color = TextSecondary, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(Space.xl))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            FocusButton("Retry now", onReconnect, selected = true)
            FocusButton("Server profiles", onOpenSettings)
        }
    }
}

@Composable
fun CenteredMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = TextSecondary, style = MaterialTheme.typography.titleLarge)
    }
}

/** Stream URL for a camera: the main (record) stream or the low-res (detect) stream. */
fun liveUrl(profile: ServerProfile, cam: CameraInfo, main: Boolean): String? {
    val stream = if (main) cam.recordStream ?: cam.detectStream else cam.detectStream ?: cam.recordStream
    return stream?.let { profile.rtspUrl(it) }
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
