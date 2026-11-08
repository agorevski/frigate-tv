package com.frigatetv.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.BoxScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.frigatetv.app.data.FrigateApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "FrigateTV"
private val CornerShape = RoundedCornerShape(12.dp)

/** Shared API instance for image loading (the client is stateless). */
private val imageApi = FrigateApi()

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

/**
 * A focusable, clickable surface. Shows a clear white ring when focused (TV remote focus)
 * and works with touch on phones.
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
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.isFocused
                onFocus?.invoke(it.isFocused)
            }
            .clip(shape)
            .background(background)
            .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        content(focused)
    }
}

@Composable
fun FocusButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
) {
    val bg = when {
        !enabled -> Panel
        selected -> Accent
        else -> PanelHi
    }
    val fg = when {
        !enabled -> TextMuted
        selected -> Ink
        else -> TextPrimary
    }
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        focusRequester = focusRequester,
        background = bg,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Text(label, color = fg, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/** Exponential backoff for stream reconnects: 2 s, 4 s, 8 s, 16 s, then capped at 30 s. */
private fun backoffMs(failures: Int): Long = minOf(30_000L, 2_000L shl minOf(failures, 4))

/**
 * Plays a stream and recovers from errors.
 *
 * - [rtsp] = true uses RTSP over TCP (live go2rtc streams). Otherwise the URL is passed to
 *   ExoPlayer as-is (HLS VOD playlists, MP4 clips).
 * - On error it rebuilds the player after a backoff delay. Each rebuild is tied to the
 *   composition, so retries never leave a duplicate player running.
 * - [networkEpoch] changes when connectivity returns and resets the backoff.
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
    // Video outputs of the current player, kept so the old one is unbound before it is released.
    val playerViewRef = remember { arrayOfNulls<PlayerView>(1) }
    val textureRef = remember { arrayOfNulls<TextureView>(1) }
    val frameRef = remember { arrayOfNulls<AspectRatioFrameLayout>(1) }

    // The player is created inside the effect (not during composition) so that the previous
    // player is fully released before the next decoder is allocated. Devices have a small
    // hardware decoder pool; overlapping instances cause NO_MEMORY / decoder-init failures.
    DisposableEffect(url, networkEpoch, attempt) {
        val created = ExoPlayer.Builder(context).build().apply {
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
            playWhenReady = true
            prepare()
        }
        player = created
        onDispose {
            player = null
            // Unbind the video output first so the decoder's output is not left targeting a released player.
            playerViewRef[0]?.takeIf { it.player === created }?.player = null
            textureRef[0]?.let { created.clearVideoTextureView(it) }
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

    if (controls) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepScreenOn(true)
                    playerViewRef[0] = this
                }
            },
            update = { view -> view.player = player },
        )
    } else {
        // Live tiles render through a TextureView, not the default SurfaceView. Several SurfaceView
        // video layers compete for the SoC's hardware overlay plane; the compositor then hangs on
        // a stuck GPU fence (ANR) when a decoder is torn down or re-bound during a camera switch.
        AndroidView(
            modifier = modifier,
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
            update = { _ -> textureRef[0]?.let { tv -> player?.setVideoTextureView(tv) } },
        )
    }
}

/** Longest edge of decoded snapshots and thumbnails; larger sources are subsampled on decode. */
private const val MAX_IMAGE_EDGE_PX = 640

/**
 * Decodes [bytes] at the smallest power-of-two sample size that keeps the longest edge within
 * [MAX_IMAGE_EDGE_PX]. Returns null if the data is invalid or the decode runs out of memory.
 */
private fun decodeScaled(bytes: ByteArray): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (longest <= 0) {
        null
    } else {
        var sample = 1
        while (longest / sample > MAX_IMAGE_EDGE_PX) sample *= 2
        // Frigate serves JPEG (no alpha), so RGB_565 halves memory without visible loss.
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }
} catch (e: OutOfMemoryError) {
    Log.w(TAG, "image decode out of memory; showing placeholder")
    null
}

/**
 * Shows an image fetched from [url]. With [refreshMs] set it re-fetches periodically
 * (used for offline camera snapshots); with null it loads once (event thumbnails).
 *
 * Only the current bitmap is held, in the producer state, so it is freed when the composable
 * leaves composition. Until an image is available a placeholder fills [modifier].
 */
@Composable
fun RemoteImage(
    url: String,
    refreshMs: Long?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, url, refreshMs) {
        while (true) {
            val decoded = runCatching { imageApi.getBytes(url) }.getOrNull()?.let { bytes ->
                withContext(Dispatchers.Default) { decodeScaled(bytes) }
            }
            if (decoded != null) value = decoded
            if (refreshMs == null) break
            delay(refreshMs)
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(bitmap = loaded, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier.background(Panel))
    }
}

/** Subtle dark overlay used to dim content behind text. */
fun Modifier.scrim(alpha: Float = 0.55f): Modifier = drawBehind {
    drawRect(Color.Black.copy(alpha = alpha))
}
