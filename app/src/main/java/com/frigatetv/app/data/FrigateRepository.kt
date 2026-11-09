package com.frigatetv.app.data

import java.util.concurrent.ConcurrentHashMap

/** How long a day's recording segments are reused before they are fetched again. */
private const val RECORDINGS_TTL_MS = 30_000L

/**
 * Data access for one server profile. Screens and ViewModels use this instead of [FrigateApi]:
 * it binds the profile, builds media URLs, and caches the results that are worth reusing.
 *
 * Live snapshots, events and clips are never cached. Recording segments for a day are reused for
 * [RECORDINGS_TTL_MS] so flicking between days does not refetch.
 */
class FrigateRepository(
    val profile: ServerProfile,
    private val api: FrigateApi = FrigateApi(),
    clock: () -> Long = System::currentTimeMillis,
) {
    private val recordingsCache = TtlCache<RecordingsKey, List<RecordingSegment>>(RECORDINGS_TTL_MS, clock)

    suspend fun cameras(): List<CameraInfo> = api.cameras(profile)

    suspend fun events(
        limit: Int,
        after: Double? = null,
        camera: String? = null,
        inProgress: Boolean = false,
    ): List<FrigateEvent> = api.events(profile, limit = limit, after = after, camera = camera, inProgress = inProgress)

    /** Recording segments for [camera] between two epoch-second bounds. Pass [refresh] to bypass the cache. */
    suspend fun recordings(
        camera: String,
        dayStart: Double,
        dayEnd: Double,
        refresh: Boolean = false,
    ): List<RecordingSegment> = recordingsCache.get(RecordingsKey(camera, dayStart, dayEnd), refresh) {
        api.recordings(profile, camera, dayStart, dayEnd)
    }

    /** Stream URL for a camera: the main (record) stream or the low-res (detect) stream. Null if none is configured. */
    fun liveUrl(cam: CameraInfo, main: Boolean): String? {
        val stream = if (main) cam.recordStream ?: cam.detectStream else cam.detectStream ?: cam.recordStream
        return stream?.let { profile.rtspUrl(it) }
    }

    fun cameraSnapshotUrl(camera: String): String = api.cameraSnapshotUrl(profile, camera)

    fun eventThumbnailUrl(eventId: String): String = api.eventThumbnailUrl(profile, eventId)

    fun eventSnapshotUrl(eventId: String): String = api.eventSnapshotUrl(profile, eventId)

    fun eventClipUrl(eventId: String): String = api.eventClipUrl(profile, eventId)

    fun vodUrl(camera: String, startEpoch: Double, endEpoch: Double): String =
        api.vodUrl(profile, camera, startEpoch, endEpoch)

    private data class RecordingsKey(val camera: String, val dayStart: Double, val dayEnd: Double)
}

/**
 * Keeps values for [ttlMs] after they were loaded. Entries are replaced, never evicted,
 * which is fine for a handful of keys.
 */
internal class TtlCache<K : Any, V>(private val ttlMs: Long, private val clock: () -> Long) {
    private class Entry<V>(val loadedAt: Long, val value: V)

    private val entries = ConcurrentHashMap<K, Entry<V>>()

    suspend fun get(key: K, refresh: Boolean, load: suspend () -> V): V {
        val now = clock()
        if (!refresh) {
            entries[key]?.takeIf { now - it.loadedAt < ttlMs }?.let { return it.value }
        }
        val value = load()
        entries[key] = Entry(now, value)
        return value
    }
}
