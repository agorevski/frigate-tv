package com.frigatetv.app.data

import android.content.Context
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's single HTTP stack. [FrigateApi], Coil image loads and Media3 playback all go through
 * [client], so they share one connection pool (keep-alive) and one set of timeouts.
 *
 * Auth seam: Frigate login (JWT cookie) belongs here. Add an interceptor to [client] or derive a
 * per-profile client with `client.newBuilder()`, then pass it to [FrigateApi], [frigateImageLoader]
 * and [frigateMediaDataSourceFactory]. Nothing in this file needs to change for that.
 */
object FrigateHttp {
    private const val CONNECT_TIMEOUT_SECONDS = 5L
    private const val READ_TIMEOUT_SECONDS = 10L

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
}

private const val IMAGE_DISK_CACHE_BYTES = 64L * 1024 * 1024
private const val MEMORY_CACHE_MAX_BYTES = 32L * 1024 * 1024
private const val MEMORY_CACHE_HEAP_DIVISOR = 8L

/**
 * Coil loader on [FrigateHttp.client]: an in-memory cache sized to 1/8 of the heap (capped at 32 MB)
 * and a 64 MB disk cache. Opaque JPEG frames are decoded as RGB_565 to halve their memory.
 */
fun frigateImageLoader(context: Context, cacheDir: File = context.cacheDir): ImageLoader =
    ImageLoader.Builder(context)
        .okHttpClient(FrigateHttp.client)
        .memoryCache {
            MemoryCache.Builder(context)
                .maxSizeBytes(
                    minOf(Runtime.getRuntime().maxMemory() / MEMORY_CACHE_HEAP_DIVISOR, MEMORY_CACHE_MAX_BYTES).toInt(),
                )
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("frigate_images"))
                .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                .build()
        }
        .allowRgb565(true)
        .build()

/**
 * Media3 data source for http(s): OkHttp on [FrigateHttp.client]. Local URIs still go through the
 * platform data sources. Used for HLS VOD playlists and clip MP4s; RTSP is separate.
 */
fun frigateMediaDataSourceFactory(context: Context): DataSource.Factory =
    DefaultDataSource.Factory(context, OkHttpDataSource.Factory(FrigateHttp.client))
