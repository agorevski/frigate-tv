package com.frigatetv.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.delay

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
