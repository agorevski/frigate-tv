package com.frigatetv.app.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.displayName
import com.frigatetv.app.player.StreamPlayer
import com.frigatetv.app.player.StreamState
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.FocusSurface
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.KeyHint
import com.frigatetv.app.ui.Panel
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.RemoteImage
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.StatusPill
import com.frigatetv.app.ui.StreamBadge
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.Warn
import com.frigatetv.app.ui.color
import com.frigatetv.app.ui.isTelevision

private val TileShape = RoundedCornerShape(Radius.md)
private val FeaturedShape = RoundedCornerShape(Radius.lg)
private val DimBg = Ink.copy(alpha = 0.55f)

// Gradients without explicit bounds stretch to whatever they are drawn into, so one instance serves every tile.
internal val BottomFade = Brush.verticalGradient(listOf(Color.Transparent, Ink.copy(alpha = 0.85f)))

/** A rail tile. Only [withinBudget] tiles decode video; the rest show a snapshot that refreshes slowly. */
@Composable
internal fun CameraTile(
    repo: FrigateRepository,
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
    val url = remember(repo, cam) { repo.liveUrl(cam, main = false) }
    val slot = remember(cam.id) { "tile:${cam.id}" }
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
                slot = slot,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        when {
            url == null -> TileCaption("No stream configured")
            !withinBudget -> {
                // Paused tiles still show the camera, from a slowly refreshed snapshot.
                RemoteImage(
                    url = repo.cameraSnapshotUrl(cam.id),
                    refreshMs = 30_000,
                    modifier = Modifier.fillMaxSize(),
                )
                TileCaption("Paused · OK to watch", dim = true)
            }
            status == StreamState.Offline -> {
                RemoteImage(
                    url = repo.cameraSnapshotUrl(cam.id),
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
internal fun TileCaption(text: String, dim: Boolean = false) {
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
internal fun FeaturedCamera(
    repo: FrigateRepository,
    cam: CameraInfo,
    isDetecting: Boolean,
    muted: Boolean,
    networkEpoch: Int,
    modifier: Modifier,
) {
    val url = remember(repo, cam) { repo.liveUrl(cam, main = true) }
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
                slot = FEATURED_SLOT,
                onState = { status = it },
                modifier = Modifier.fillMaxSize(),
            )
            when (status) {
                // Static text rather than an animated loader: the decoders are busy starting up.
                StreamState.Connecting -> TileCaption("Connecting to ${displayName(cam.id)}…")
                StreamState.Offline -> {
                    RemoteImage(
                        url = repo.cameraSnapshotUrl(cam.id),
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

/** Decoder slot of the featured player; each rail tile uses "tile:<camera id>". */
internal const val FEATURED_SLOT = "featured"

/** Name and hint under the featured video, so nothing covers the main picture's lower edge. */
@Composable
internal fun FeaturedCaption(camId: String, isFavorite: Boolean, muted: Boolean) {
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
