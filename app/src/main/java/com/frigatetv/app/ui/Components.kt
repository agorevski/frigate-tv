package com.frigatetv.app.ui

import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.frigatetv.app.data.frigateMediaDataSourceFactory
import kotlinx.coroutines.delay

private const val TAG = "FrigateTV"
private val CornerShape = RoundedCornerShape(Radius.md)
private val PillShape = RoundedCornerShape(percent = 50)

enum class StreamState(val label: String) {
    Connecting("CONNECTING"),
    Live("LIVE"),
    Buffering("BUFFERING"),
    Offline("OFFLINE"),
}

fun StreamState.color(): Color = when (this) {
    StreamState.Live -> LiveGreen
    StreamState.Connecting, StreamState.Buffering -> Warn
    StreamState.Offline -> Danger
}

/** Focus scale-up. Applied in the graphics layer, so it never changes layout. */
private const val FOCUS_SCALE = 1.05f
private val FocusRingWidth = 3.dp
private val FocusGlowWidth = 6.dp
private val SelectedRingWidth = 2.dp

/**
 * Draws the focus ring, an outer glow ring, and the selected ring around the content.
 *
 * [focus] and [selected] are read only inside the draw lambda, so animating them invalidates the
 * draw pass alone (no recomposition or relayout). Outlines are cached until the size changes.
 * No shadows or elevation: those are costly on TV GPUs.
 */
private fun Modifier.focusRing(shape: Shape, focus: () -> Float, selected: () -> Boolean): Modifier =
    drawWithCache {
        val ring = FocusRingWidth.toPx()
        val glow = FocusGlowWidth.toPx()
        val sel = SelectedRingWidth.toPx()
        // Each stroke is centred on its path, so outlines are inset/outset by half a stroke.
        val ringOutline = shape.createOutline(Size(size.width - ring, size.height - ring), layoutDirection, this)
        val selOutline = shape.createOutline(Size(size.width - sel, size.height - sel), layoutDirection, this)
        val glowOutline = shape.createOutline(Size(size.width + glow, size.height + glow), layoutDirection, this)
        val ringStroke = Stroke(ring)
        val selStroke = Stroke(sel)
        val glowStroke = Stroke(glow)
        onDrawWithContent {
            drawContent()
            val f = focus()
            if (f > 0.01f) {
                translate(-glow / 2, -glow / 2) {
                    drawOutline(glowOutline, Accent.copy(alpha = 0.22f * f), style = glowStroke)
                }
                translate(ring / 2, ring / 2) {
                    drawOutline(ringOutline, Accent.copy(alpha = f), style = ringStroke)
                }
            } else if (selected()) {
                translate(sel / 2, sel / 2) {
                    drawOutline(selOutline, Accent.copy(alpha = 0.55f), style = selStroke)
                }
            }
        }
    }

/**
 * A focusable, clickable surface. Focus shows as an accent ring with a soft outer glow and a
 * 5% scale-up, so the focused target is obvious at TV distance. [selected] (e.g. the current tab
 * or chosen camera) shows a thinner, dimmer ring that stays visible without focus, so selected
 * and focused read differently. All focus motion runs in the draw/graphics-layer phases.
 * Works with touch on phones.
 */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = Color.Transparent,
    shape: Shape = CornerShape,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusRequester: FocusRequester? = null,
    onFocus: ((Boolean) -> Unit)? = null,
    selected: Boolean = false,
    focusScale: Float = FOCUS_SCALE,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val progress = animateFloatAsState(if (focused) 1f else 0f, tween(Motion.FocusMs), label = "focus")
    val currentSelected by rememberUpdatedState(selected)
    Box(
        modifier
            // Draw above siblings while focused so the scaled-up surface and glow are not overlapped.
            .zIndex(if (focused) 1f else 0f)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus?.invoke(it.isFocused)
            }
            .graphicsLayer {
                val s = 1f + (focusScale - 1f) * progress.value
                scaleX = s
                scaleY = s
            }
            .focusRing(shape, focus = { progress.value }, selected = { currentSelected })
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

/**
 * Button used for every action. [selected] marks the primary action of a group (accent fill);
 * [destructive] tints the label for irreversible actions such as delete. Disabled buttons stay
 * focusable (so D-pad navigation never skips over them) but do not scale up or react to clicks.
 */
@Composable
fun FocusButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val bg by animateColorAsState(
        when {
            !enabled -> Panel
            selected -> Accent
            else -> PanelHi
        },
        tween(Motion.FocusMs),
        label = "buttonBg",
    )
    val fg = when {
        !enabled -> TextSecondary.copy(alpha = 0.6f)
        selected -> Ink
        destructive -> Danger
        else -> TextPrimary
    }
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier.defaultMinSize(minHeight = 48.dp),
        focusRequester = focusRequester,
        background = bg,
        shape = RoundedCornerShape(Radius.md),
        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.xs),
        focusScale = if (enabled) FOCUS_SCALE else 1f,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

/** Compact status label on a semantic color (LIVE, OFFLINE, MOTION). Fully rounded ends. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color, PillShape)
            .padding(horizontal = Space.xs, vertical = Space.xxs),
    ) {
        Text(text, color = Ink, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/** Section title with an optional trailing action. Titles are specific nouns, not "Settings". */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleLarge)
            if (subtitle != null) {
                Spacer(Modifier.height(Space.xxs))
                Text(subtitle, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.md))
            trailing()
        }
    }
}

/** Flat panel for grouping related controls. Padding is [Space.md]; children use [Radius.md]. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(Radius.lg))
            .background(Panel)
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
        content = content,
    )
}

/**
 * Centered message for empty or failed lists. Gives the reason and, when possible, the next step;
 * pass [action] (e.g. a [FocusButton] "Retry") to make that step one click away.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(Space.xxl).background(PanelHi, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(Space.sm).background(TextSecondary, CircleShape))
        }
        Spacer(Modifier.height(Space.md))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                message,
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 560.dp),
            )
        }
        if (action != null) {
            Spacer(Modifier.height(Space.lg))
            action()
        }
    }
}

/** Three pulsing dots plus a label. Shown while data loads so the screen never looks frozen. */
@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loading")
    Column(
        modifier.fillMaxSize().padding(Space.lg),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            repeat(3) { index ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(Motion.PulseMs, delayMillis = index * 150),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "dot$index",
                )
                Box(Modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.background(Accent, CircleShape))
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(label, color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Status dot that pulses while [active], e.g. while a connection is being established.
 * When inactive no animation runs, so idle tiles do not request a frame every vsync.
 */
@Composable
fun PulsingDot(color: Color, active: Boolean, modifier: Modifier = Modifier) {
    if (active) {
        val alpha by rememberInfiniteTransition(label = "dot").animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(Motion.PulseMs), RepeatMode.Reverse),
            label = "dotAlpha",
        )
        Box(modifier.size(10.dp).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
    } else {
        Box(modifier.size(10.dp).background(color, CircleShape))
    }
}

private val NoticeStripeWidth = 4.dp

/** Inline notice that slides in and out, marked by an accent stripe on its leading edge. Pass null to hide it. */
@Composable
fun NoticeBar(text: String?, modifier: Modifier = Modifier) {
    // Keep the last message during the exit animation so the bar does not blank out while closing.
    var shown by remember { mutableStateOf("") }
    if (text != null) shown = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = fadeIn(tween(Motion.EnterMs)) + expandVertically(tween(Motion.EnterMs)),
        exit = fadeOut(tween(Motion.FocusMs)) + shrinkVertically(tween(Motion.FocusMs)),
    ) {
        Text(
            shown,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .background(PanelHi)
                .drawBehind {
                    drawRect(Accent, size = Size(NoticeStripeWidth.toPx(), size.height))
                }
                .padding(horizontal = Space.lg, vertical = Space.xs),
        )
    }
}

/** Exponential backoff for stream reconnects: 2 s, 4 s, 8 s, 16 s, then capped at 30 s. */
private fun backoffMs(failures: Int): Long = minOf(30_000L, 2_000L shl minOf(failures, 4))

/**
 * Buffering for live RTSP: start as soon as 250 ms is buffered and hold at most ~2 s. A live
 * view gains nothing from a deep buffer (it only adds latency and memory per tile), and go2rtc
 * on the LAN delivers frames steadily.
 */
private fun liveLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(1_000, 2_000, 250, 500)
    .setPrioritizeTimeOverSizeThresholds(true)
    .build()

/**
 * Buffering for recordings and clips: ExoPlayer's default buffer depth (smooth seeking and
 * playback) but playback starts after 1 s instead of 2.5 s, since the source is on the LAN.
 */
private fun vodLoadControl(): DefaultLoadControl = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
        1_000,
        2_000,
    )
    .build()

/**
 * Plays a stream and recovers from errors.
 *
 * - [rtsp] = true uses RTSP over TCP (live go2rtc streams) with a shallow, low-latency buffer.
 *   Otherwise the URL is passed to ExoPlayer as-is (HLS VOD playlists, MP4 clips).
 * - On error it rebuilds the player after a backoff delay. Each rebuild is tied to the
 *   composition, so retries never leave a duplicate player running.
 * - [networkEpoch] changes when connectivity returns and resets the backoff.
 * - [placeholder] (optional) is drawn over the video until the first frame renders, e.g. the
 *   camera's last snapshot via [RemoteImage], so the tile never shows a black box while connecting.
 */
@Composable
fun StreamPlayer(
    url: String,
    rtsp: Boolean,
    audible: Boolean,
    networkEpoch: Int,
    onState: (StreamState) -> Unit,
    modifier: Modifier = Modifier,
    controls: Boolean = false,
    retry: Boolean = true,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val currentOnState by rememberUpdatedState(onState)
    // Consecutive failed attempts, used for backoff. Reset as soon as playback is healthy.
    val failures = remember(url, networkEpoch) { mutableIntStateOf(0) }
    val errorCount = remember(url, networkEpoch) { mutableIntStateOf(0) }
    // Bumped after a backoff delay to rebuild the player. Kept separate from [failures] so
    // that resetting the backoff on success does not tear down a healthy player.
    var attempt by remember(url, networkEpoch) { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    // True once the current player has drawn a frame; hides [placeholder].
    var firstFrame by remember { mutableStateOf(false) }
    // Video outputs of the current player, kept so the old one is unbound before it is released.
    val playerViewRef = remember { arrayOfNulls<PlayerView>(1) }
    val textureRef = remember { arrayOfNulls<TextureView>(1) }
    val frameRef = remember { arrayOfNulls<AspectRatioFrameLayout>(1) }
    // Player currently bound to the TextureView. setVideoTextureView() wraps the texture in a new
    // Surface and re-targets the decoder on every call, so it is only called when the player changes.
    val textureBound = remember { arrayOfNulls<ExoPlayer>(1) }

    // The player is created inside the effect (not during composition) so that the previous
    // player is fully released before the next decoder is allocated. Devices have a small
    // hardware decoder pool; overlapping instances cause NO_MEMORY / decoder-init failures.
    DisposableEffect(url, networkEpoch, attempt) {
        firstFrame = false
        val created = ExoPlayer.Builder(context)
            .setLoadControl(if (rtsp) liveLoadControl() else vodLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(frigateMediaDataSourceFactory(context)))
            .build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> currentOnState(StreamState.Buffering)
                        Player.STATE_READY -> {
                            failures.intValue = 0
                            currentOnState(
                                if (this@apply.isPlaying) StreamState.Live else StreamState.Buffering
                            )
                        }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        failures.intValue = 0
                        currentOnState(StreamState.Live)
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    val frame = frameRef[0] ?: return
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        frame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "playback error: ${error.errorCodeName}")
                    currentOnState(StreamState.Offline)
                    errorCount.intValue++
                }
            })
            // Only the audible player takes audio focus, so other apps duck or pause for it.
            setAudioAttributes(AudioAttributes.DEFAULT, audible)
            val item = MediaItem.fromUri(url)
            if (rtsp) {
                setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(item))
            } else {
                setMediaItem(item)
            }
            volume = if (audible) 1f else 0f
            // Bind the video output before prepare() when the view already exists (rebuilds and
            // retries), so the decoder is configured with its real surface from the start
            // instead of being re-bound after the first recomposition.
            textureRef[0]?.let {
                setVideoTextureView(it)
                textureBound[0] = this
            }
            playerViewRef[0]?.player = this
            playWhenReady = true
            prepare()
        }
        player = created
        onDispose {
            player = null
            // Unbind the video output first so the decoder's output is not left targeting a released player.
            playerViewRef[0]?.takeIf { it.player === created }?.player = null
            textureRef[0]?.let { created.clearVideoTextureView(it) }
            if (textureBound[0] === created) textureBound[0] = null
            created.clearVideoSurface()
            created.release()
        }
    }

    LaunchedEffect(player, audible) {
        player?.apply {
            volume = if (audible) 1f else 0f
            setAudioAttributes(AudioAttributes.DEFAULT, audible)
        }
    }

    LaunchedEffect(errorCount.intValue) {
        if (errorCount.intValue == 0 || !retry) return@LaunchedEffect
        delay(backoffMs(failures.intValue))
        failures.intValue++
        attempt++
    }

    LaunchedEffect(url, networkEpoch) {
        currentOnState(StreamState.Connecting)
    }

    Box(modifier) {
        if (controls) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setKeepScreenOn(true)
                        playerViewRef[0] = this
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
            )
        } else {
            // Live tiles render through a TextureView, not the default SurfaceView. Several SurfaceView
            // video layers compete for the SoC's hardware overlay plane; the compositor then hangs on
            // a stuck GPU fence (ANR) when a decoder is torn down or re-bound during a camera switch.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    AspectRatioFrameLayout(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        addView(
                            TextureView(ctx).also { textureRef[0] = it },
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT,
                            ),
                        )
                        frameRef[0] = this
                    }
                },
                update = { _ ->
                    val current = player
                    val tv = textureRef[0]
                    if (current != null && tv != null && textureBound[0] !== current) {
                        current.setVideoTextureView(tv)
                        textureBound[0] = current
                    }
                },
            )
        }
        if (placeholder != null && !firstFrame) {
            Box(Modifier.matchParentSize(), content = placeholder)
        }
    }
}

/** Default longest edge of decoded snapshots and thumbnails; larger sources are subsampled on decode. */
private const val MAX_IMAGE_EDGE_PX = 640

/**
 * Shows an image fetched from [url] through Coil, whose loader ([frigateImageLoader]) runs on the
 * shared HTTP client and has memory and disk caches.
 *
 * With [refreshMs] set it re-fetches periodically (offline camera snapshots). Those requests skip
 * both caches, so a snapshot is never stale, and the previous frame stays on screen as the
 * placeholder until the new one arrives, so a refresh never flashes blank. With null it loads once
 * and is cached (event thumbnails), fading in over [Motion.EnterMs].
 *
 * [maxEdgePx] caps the decoded size; pass a smaller value for small tiles to save memory.
 * Until an image is available a [Panel] placeholder fills [modifier].
 */
@Composable
fun RemoteImage(
    url: String,
    refreshMs: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxEdgePx: Int = MAX_IMAGE_EDGE_PX,
) {
    val context = LocalContext.current
    val panel = remember { ColorPainter(Panel) }
    // Advances once per refresh. Each tick builds a new request, which makes Coil fetch again.
    var tick by remember(url, refreshMs) { mutableIntStateOf(0) }
    LaunchedEffect(url, refreshMs) {
        if (refreshMs == null) return@LaunchedEffect
        while (true) {
            delay(refreshMs)
            tick++
        }
    }
    var lastFrame by remember(url) { mutableStateOf<Painter?>(null) }
    val request = remember(url, maxEdgePx, refreshMs, tick) {
        ImageRequest.Builder(context)
            .data(url)
            .size(maxEdgePx)
            .apply {
                if (refreshMs != null) {
                    memoryCachePolicy(CachePolicy.DISABLED)
                    diskCachePolicy(CachePolicy.DISABLED)
                } else {
                    crossfade(Motion.EnterMs)
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        placeholder = lastFrame ?: panel,
        error = panel,
        onSuccess = { lastFrame = it.painter },
    )
}

/** Subtle dark overlay used to dim content behind text. */
fun Modifier.scrim(alpha: Float = 0.55f): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = alpha))
}

/** True on Android TV / Google TV, where remote-key hints apply; false on touch devices. */
@Composable
fun isTelevision(): Boolean {
    val uiMode = androidx.compose.ui.platform.LocalConfiguration.current.uiMode
    return uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK ==
        android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}
