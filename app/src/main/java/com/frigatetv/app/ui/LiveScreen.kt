package com.frigatetv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.frigatetv.app.AppState
import com.frigatetv.app.Connection
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.displayName
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable

private val api = FrigateApi()

/** Upper bound on simultaneously decoding streams. Tiles past this show a paused placeholder. */
private const val MAX_LIVE_STREAMS = 6
/** Selecting a camera switches it to the main stream only after focus rests this long. */
private const val SELECT_DEBOUNCE_MS = 800L
/** Fullscreen overlay hides after this much inactivity. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

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
        Connection.Idle, Connection.Connecting -> CenteredMessage("Connecting to ${profile.name}…")
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

    val fullscreenCam = fullscreenId?.let { id -> cams.firstOrNull { it.id == id } }
    if (fullscreenCam != null) {
        FullscreenView(
            profile = profile,
            cams = cams,
            currentId = fullscreenCam.id,
            muted = state.prefs.muted,
            networkEpoch = state.networkEpoch,
            onSwitch = { id ->
                focusedId = id
                selectedId = id
                fullscreenId = id
            },
            onToggleMute = onToggleMute,
            onExit = { fullscreenId = null },
        )
        return
    }

    CameraGrid(
        profile = profile,
        cams = cams,
        state = state,
        selectedId = selectedId,
        initialFocusId = focusedId,
        tabFocus = tabFocus,
        onFocusCamera = { focusedId = it },
        onOpen = { id ->
            focusedId = id
            fullscreenId = id
        },
    )
}

@Composable
private fun CameraGrid(
    profile: ServerProfile,
    cams: List<CameraInfo>,
    state: AppState,
    selectedId: String,
    initialFocusId: String,
    tabFocus: FocusRequester,
    onFocusCamera: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val requesters = remember(cams.map { it.id }) { cams.associate { it.id to FocusRequester() } }
    // The selected tile always decodes, so when it sits past the cap the other tiles yield one slot to it.
    val selectedIndex = cams.indexOfFirst { it.id == selectedId }
    val streamBudget = if (selectedIndex >= MAX_LIVE_STREAMS) MAX_LIVE_STREAMS - 1 else MAX_LIVE_STREAMS
    // Returning from fullscreen re-enters the grid; put focus back on the camera we left.
    LaunchedEffect(Unit) {
        runCatching { requesters[initialFocusId]?.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().padding(8.dp)) {
        val columns = chooseColumns(cams.size, maxWidth, maxHeight, state.prefs.columns)
        val rows = cams.chunked(columns)
        val spacing = 8.dp
        val tileWidth = (maxWidth - spacing * (columns - 1)) / columns
        val tileHeight = (maxHeight - spacing * (rows.size - 1)) / rows.size

        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(spacing)) {
            rows.forEachIndexed { rowIndex, rowCams ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing, Alignment.CenterHorizontally),
                ) {
                    rowCams.forEachIndexed { colIndex, cam ->
                        val index = rowIndex * columns + colIndex
                        val isSelected = cam.id == selectedId
                        // Top-row tiles: UP must reach the tab bar even from columns far to the right
                        // of the tabs, which directional search alone can miss.
                        val tileModifier = if (rowIndex == 0) {
                            Modifier.size(tileWidth, tileHeight).focusProperties { up = tabFocus }
                        } else {
                            Modifier.size(tileWidth, tileHeight)
                        }
                        CameraTile(
                            profile = profile,
                            cam = cam,
                            isSelected = isSelected,
                            isFavorite = cam.id in state.prefs.favorites,
                            isDetecting = cam.id in state.detecting,
                            audible = isSelected && !state.prefs.muted,
                            networkEpoch = state.networkEpoch,
                            withinBudget = index < streamBudget || isSelected,
                            modifier = tileModifier,
                            focusRequester = requesters.getValue(cam.id),
                            onFocus = { onFocusCamera(cam.id) },
                            onOpen = { onOpen(cam.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraTile(
    profile: ServerProfile,
    cam: CameraInfo,
    isSelected: Boolean,
    isFavorite: Boolean,
    isDetecting: Boolean,
    audible: Boolean,
    networkEpoch: Int,
    withinBudget: Boolean,
    modifier: Modifier,
    focusRequester: FocusRequester,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
) {
    // The selected camera uses the main stream (audio + full detail); others use the sub stream.
    val url = liveUrl(profile, cam, main = isSelected)
    var status by remember(cam.id, url) { mutableStateOf(StreamState.Connecting) }

    FocusSurface(
        onClick = onOpen,
        modifier = modifier.background(Color.Black),
        focusRequester = focusRequester,
        onFocus = { if (it) onFocus() },
    ) { _ ->
        when {
            url == null -> Text("No stream configured", color = TextMuted, fontSize = 18.sp)
            !withinBudget -> Text("Paused to protect the decoder", color = TextMuted, fontSize = 18.sp)
            else -> StreamPlayer(
                url = url,
                rtsp = true,
                audible = audible,
                networkEpoch = networkEpoch,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (status == StreamState.Offline) {
            RemoteImage(
                url = api.cameraSnapshotUrl(profile, cam.id),
                refreshMs = 10_000,
                modifier = Modifier.fillMaxSize().scrim(0.6f),
            )
        }

        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = displayName(cam.id) + if (isFavorite) "  ★" else "",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isDetecting) {
                StatusPill("MOTION", Warn)
                Spacer(Modifier.width(8.dp))
            }
            StatusPill(status.label, status.color())
        }

        if (isSelected) {
            Text(
                text = if (audible) "SOUND ON" else "MUTED",
                color = Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .background(Accent)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun FullscreenView(
    profile: ServerProfile,
    cams: List<CameraInfo>,
    currentId: String,
    muted: Boolean,
    networkEpoch: Int,
    onSwitch: (String) -> Unit,
    onToggleMute: () -> Unit,
    onExit: () -> Unit,
) {
    val index = cams.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val cam = cams[index]
    val url = liveUrl(profile, cam, main = true)
    var status by remember(currentId) { mutableStateOf(StreamState.Connecting) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val rootRequester = remember { FocusRequester() }

    // Any interaction shows the controls for a few seconds.
    LaunchedEffect(interaction) {
        controlsVisible = true
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
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

        if (status == StreamState.Offline) {
            CenteredMessage("${displayName(cam.id)} is offline. Retrying…")
        }

        if (controlsVisible) {
            Row(
                Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayName(cam.id),
                    color = Color.White,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                StatusPill(status.label, status.color())
                Spacer(Modifier.width(16.dp))
                FocusButton(if (muted) "Unmute" else "Mute", onToggleMute)
            }
            Text(
                text = "◀ ▶  switch camera     OK  controls     BACK  grid",
                color = TextPrimary,
                fontSize = 18.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(vertical = 12.dp),
            )
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
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Can't reach ${profile.name}", color = TextPrimary, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(profile.baseUrl, color = TextMuted, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(reason, color = Danger, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text("Retrying automatically.", color = TextMuted, fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FocusButton("Retry now", onReconnect, selected = true)
            FocusButton("Server profiles", onOpenSettings)
        }
    }
}

@Composable
fun CenteredMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = TextMuted, fontSize = 22.sp)
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
