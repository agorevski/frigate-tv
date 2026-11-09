package com.frigatetv.app.ui.events

import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.player.StreamPlayer
import com.frigatetv.app.ui.Accent
import com.frigatetv.app.ui.EmptyState
import com.frigatetv.app.ui.Ink
import com.frigatetv.app.ui.LoadingState
import com.frigatetv.app.ui.Motion
import com.frigatetv.app.ui.NumericStyle
import com.frigatetv.app.ui.Radius
import com.frigatetv.app.ui.RemoteImage
import com.frigatetv.app.ui.Space
import com.frigatetv.app.ui.StatusPill
import com.frigatetv.app.ui.TextPrimary
import com.frigatetv.app.ui.TextSecondary
import com.frigatetv.app.ui.isTelevision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Hides the clip overlay after this long without a key press. */
private const val OVERLAY_HIDE_MS = 8_000L

/** Horizontal travel (as a fraction of the width) that the next event slides in from. */
private const val SLIDE_FRACTION = 0.3f

/** Drag resistance past the first or last event: the content gives a little, then springs back. */
private const val EDGE_RESISTANCE = 0.25f

/** Delay before a paged-to clip starts decoding, so flicking through events never stacks decoders. */
private const val PLAYER_SETTLE_MS = 300L

/** Decoder slot of the event clip player. */
private const val CLIP_SLOT = "event-clip"

private val TopScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))

/**
 * One event full screen. A horizontal swipe (phone) or D-pad LEFT/RIGHT (TV, while the player
 * controls are not focused) calls [onStep] with +1 for the next event in grid order and -1 for the
 * previous one; it returns false at either end. [position] is the event's index in the list of
 * [total] events, or -1 when it is no longer in the list.
 */
@Composable
internal fun EventDetail(
    repo: FrigateRepository,
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
                        url = remember(repo, ui.id) { repo.eventClipUrl(ui.id) },
                        rtsp = false,
                        audible = !muted,
                        networkEpoch = 0,
                        slot = CLIP_SLOT,
                        onState = {},
                        controls = true,
                        retry = false,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = if (event.hasSnapshot) { { EventStill(repo, ui) } } else null,
                    )
                    playing -> if (event.hasSnapshot) EventStill(repo, ui) else LoadingState("Loading clip…")
                    event.hasSnapshot -> {
                        EventStill(repo, ui)
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
private fun EventStill(repo: FrigateRepository, ui: EventUi) {
    RemoteImage(
        remember(repo, ui.id) { repo.eventSnapshotUrl(ui.id) },
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
