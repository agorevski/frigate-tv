package com.frigatetv.app.player

import android.util.Log
import android.view.TextureView
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
import com.frigatetv.app.data.frigateMediaDataSourceFactory
import kotlinx.coroutines.delay

private const val TAG = "FrigateTV"

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
 * - [slot] names this player's decoder slot in [LocalPlayerPool]. A player is only created when a
 *   slot is free, and its slot is released before the replacement is created. Callers must give
 *   each concurrent player a distinct slot.
 * - On error it rebuilds the player after a [ReconnectBackoff] delay. Each rebuild is tied to the
 *   composition, so retries never leave a duplicate player running.
 * - [networkEpoch] changes when connectivity returns and resets the backoff.
 * - [placeholder] (optional) is drawn over the video until the first frame renders, e.g. the
 *   camera's last snapshot, so the tile never shows a black box while connecting.
 */
@Composable
fun StreamPlayer(
    url: String,
    rtsp: Boolean,
    audible: Boolean,
    networkEpoch: Int,
    slot: String,
    onState: (StreamState) -> Unit,
    modifier: Modifier = Modifier,
    controls: Boolean = false,
    retry: Boolean = true,
    placeholder: (@Composable BoxScope.() -> Unit)? = null,
) {
    val context = LocalContext.current
    val slots = LocalPlayerPool.current
    val currentOnState by rememberUpdatedState(onState)
    // Failed attempts since the last healthy playback, used for backoff.
    val backoff = remember(url, networkEpoch) { ReconnectBackoff() }
    val errorCount = remember(url, networkEpoch) { mutableIntStateOf(0) }
    // Bumped after a backoff delay to rebuild the player.
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
    DisposableEffect(url, networkEpoch, attempt, slot) {
        firstFrame = false
        val acquired = slots?.acquire(slot) ?: true
        if (!acquired) {
            // Callers budget tiles within the cap, so this is a guard, not a normal path.
            Log.w(TAG, "no decoder slot free for $slot")
            return@DisposableEffect onDispose {}
        }
        val created = ExoPlayer.Builder(context)
            .setLoadControl(if (rtsp) liveLoadControl() else vodLoadControl())
            .setMediaSourceFactory(DefaultMediaSourceFactory(frigateMediaDataSourceFactory(context)))
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> currentOnState(StreamState.Buffering)
                            Player.STATE_READY -> {
                                backoff.onHealthy()
                                currentOnState(
                                    if (this@apply.isPlaying) StreamState.Live else StreamState.Buffering
                                )
                            }
                            else -> Unit
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            backoff.onHealthy()
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
            slots?.release(slot)
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
        delay(backoff.delayMs)
        backoff.recordFailure()
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
