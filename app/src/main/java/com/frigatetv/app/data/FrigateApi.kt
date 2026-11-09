package com.frigatetv.app.data

import com.frigatetv.app.auth.FrigateSessions
import com.frigatetv.app.auth.HTTP_UNAUTHORIZED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.security.cert.CertificateException

/**
 * Stateless client for the Frigate HTTP API (verified against Frigate 0.16.2).
 * Every call runs on Dispatchers.IO and throws [FrigateException] on failure.
 *
 * [clientFor] supplies the HTTP client for a profile. By default it is the profile's authenticated
 * client ([FrigateSessions]), which attaches the login token and re-logs in on 401.
 */
class FrigateApi(private val clientFor: (ServerProfile) -> OkHttpClient = FrigateSessions::clientFor) {

    suspend fun version(profile: ServerProfile): String = withContext(Dispatchers.IO) {
        get(profile, "${profile.baseUrl}/api/version").trim().ifEmpty {
            throw FrigateException("Empty version response")
        }
    }

    suspend fun cameras(profile: ServerProfile): List<CameraInfo> = withContext(Dispatchers.IO) {
        parse { parseCameras(get(profile, "${profile.baseUrl}/api/config")) }
    }

    suspend fun events(
        profile: ServerProfile,
        limit: Int,
        after: Double? = null,
        camera: String? = null,
        inProgress: Boolean = false,
    ): List<FrigateEvent> = withContext(Dispatchers.IO) {
        val query = buildString {
            append("limit=$limit")
            if (after != null) append("&after=${after.toLong()}")
            if (camera != null) append("&camera=${encode(camera)}")
            if (inProgress) append("&in_progress=1")
        }
        parse {
            frigateJson.decodeFromString<List<EventDto>>(get(profile, "${profile.baseUrl}/api/events?$query"))
                .map { it.toDomain() }
        }
    }

    suspend fun recordings(
        profile: ServerProfile,
        camera: String,
        after: Double,
        before: Double,
    ): List<RecordingSegment> = withContext(Dispatchers.IO) {
        parse {
            val url = "${profile.baseUrl}/api/${encode(camera)}/recordings" +
                "?after=${after.toLong()}&before=${before.toLong()}"
            frigateJson.decodeFromString<List<RecordingDto>>(get(profile, url)).mapNotNull { r ->
                val end = r.endTime ?: return@mapNotNull null
                RecordingSegment(r.startTime ?: throw SerializationException("recording without start_time"), end)
            }
        }
    }

    fun cameraSnapshotUrl(profile: ServerProfile, camera: String): String =
        "${profile.baseUrl}/api/${encode(camera)}/latest.jpg"

    fun eventThumbnailUrl(profile: ServerProfile, eventId: String): String =
        "${profile.baseUrl}/api/events/${encode(eventId)}/thumbnail.jpg"

    fun eventSnapshotUrl(profile: ServerProfile, eventId: String): String =
        "${profile.baseUrl}/api/events/${encode(eventId)}/snapshot.jpg"

    fun eventClipUrl(profile: ServerProfile, eventId: String): String =
        "${profile.baseUrl}/api/events/${encode(eventId)}/clip.mp4"

    /** HLS playlist for recorded video between two epoch-second timestamps (verified). */
    fun vodUrl(profile: ServerProfile, camera: String, startEpoch: Double, endEpoch: Double): String =
        "${profile.baseUrl}/vod/${encode(camera)}/start/${startEpoch.toLong()}/end/${endEpoch.toLong()}/index.m3u8"

    /**
     * Sends a GET to [url] and returns the body as text. Non-2xx responses and network failures
     * become [FrigateException]s.
     *
     * OkHttp keeps connections alive and pools them, so each poll or thumbnail reuses a socket.
     */
    private fun get(profile: ServerProfile, url: String): String {
        val call = buildCall(clientFor(profile), url)
        try {
            return call.execute().use { response ->
                if (!response.isSuccessful) throw httpFailure(response)
                response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw networkFailure(e)
        }
    }

    private fun httpFailure(response: Response): FrigateException =
        if (response.code == HTTP_UNAUTHORIZED) {
            FrigateException("Login required or rejected. Check the server's login settings.", response.code)
        } else {
            FrigateException("Server returned HTTP ${response.code}", response.code)
        }

    /** No Accept-Encoding header is set, so OkHttp negotiates gzip and decodes it transparently. */
    private fun buildCall(client: OkHttpClient, url: String): Call = try {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json, image/*, */*").build())
    } catch (e: IllegalArgumentException) {
        throw FrigateException("Invalid server address", cause = e)
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: SerializationException) {
        throw FrigateException("Unexpected response from server", cause = e)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private val RTSP_PATH = Regex("""rtsp://[^/]+/([^?\s]+)""")

        /** Camera ids keep the document order of `cameras` in the config. */
        internal fun parseCameras(config: String): List<CameraInfo> =
            parseCameras(frigateJson.parseToJsonElement(config))

        internal fun parseCameras(config: JsonElement): List<CameraInfo> =
            frigateJson.decodeFromJsonElement<ConfigDto>(config).cameras.map { (name, cam) ->
                var detect: String? = null
                var record: String? = null
                for (input in cam.ffmpeg?.inputs.orEmpty()) {
                    val stream = RTSP_PATH.find(input.path)?.groupValues?.get(1) ?: continue
                    for (role in input.roles) {
                        when (role) {
                            "detect" -> detect = stream
                            "record" -> record = stream
                        }
                    }
                }
                CameraInfo(
                    id = name,
                    enabled = cam.enabled,
                    detectStream = detect,
                    recordStream = record,
                    detectWidth = cam.detect?.width?.takeIf { it > 0 },
                    detectHeight = cam.detect?.height?.takeIf { it > 0 },
                )
            }

        internal fun parseEvent(o: String): FrigateEvent = parseEvent(frigateJson.parseToJsonElement(o))

        internal fun parseEvent(o: JsonElement): FrigateEvent =
            frigateJson.decodeFromJsonElement<EventDto>(o).toDomain()
    }
}

private fun EventDto.toDomain(): FrigateEvent = FrigateEvent(
    id = id,
    camera = camera,
    label = label,
    subLabel = subLabel?.takeIf { it.isNotBlank() },
    startTime = startTime,
    endTime = endTime,
    hasClip = hasClip,
    hasSnapshot = hasSnapshot,
    score = data?.topScore ?: data?.score,
)

/** Maps a transport failure to a [FrigateException]. A rejected TLS certificate gets its own message. */
internal fun networkFailure(e: IOException): FrigateException {
    val certificate = generateSequence<Throwable>(e) { it.cause }
        .firstOrNull { it is CertificateException }
    return if (certificate != null) {
        FrigateException("Server certificate rejected: ${certificate.message}", cause = e)
    } else {
        FrigateException("Cannot reach server (${e.javaClass.simpleName})", cause = e)
    }
}
