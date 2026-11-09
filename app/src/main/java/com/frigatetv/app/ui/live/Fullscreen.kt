package com.frigatetv.app.ui.live

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.displayName
import com.frigatetv.app.player.StreamPlayer
import com.frigatetv.app.player.StreamState
import com.frigatetv.app.ui.CenteredMessage
import com.frigatetv.app.ui.FocusButton
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.KeyHint
import com.frigatetv.app.ui.LoadingState
import com.frigatetv.app.ui.Motion
import com.frigatetv.app.ui.NumericStyle
import com.frigatetv.app.ui.RemoteImage
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.StatusPill
import com.frigatetv.app.ui.StreamBadge
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.Warn
import com.frigatetv.app.ui.color
import com.frigatetv.app.ui.isTelevision
import kotlinx.coroutines.delay
import java.util.Date

/** Fullscreen overlay hides after this much inactivity. */
private const val CONTROLS_TIMEOUT_MS = 4_000L

private val DimBg = Ink.copy(alpha = 0.55f)
private val TopFade = Brush.verticalGradient(listOf(Ink.copy(alpha = 0.85f), Color.Transparent))

/**
 * One camera filling the screen (main stream, with audio). LEFT/RIGHT switch cameras, OK toggles the
 * overlay, and BACK returns to the grid. The overlay hides after [CONTROLS_TIMEOUT_MS] of inactivity.
 */
@Composable
internal fun FullscreenView(
    repo: FrigateRepository,
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
    val url = remember(repo, cam) { repo.liveUrl(cam, main = true) }
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
                slot = FULLSCREEN_SLOT,
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
                url = repo.cameraSnapshotUrl(cam.id),
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

/** Decoder slot of the fullscreen player. */
internal const val FULLSCREEN_SLOT = "fullscreen"
