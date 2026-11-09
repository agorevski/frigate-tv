package com.frigatetv.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Stateless client for the Frigate HTTP API (verified against Frigate 0.16.2).
 * Every call runs on Dispatchers.IO and throws [FrigateException] on failure.
 */
class FrigateApi {

    suspend fun version(profile: ServerProfile): String = withContext(Dispatchers.IO) {
        get("${profile.baseUrl}/api/version").trim().ifEmpty {
            throw FrigateException("Empty version response")
        }
    }

    suspend fun cameras(profile: ServerProfile): List<CameraInfo> = withContext(Dispatchers.IO) {
        parse { parseCameras(JSONObject(get("${profile.baseUrl}/api/config"))) }
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
            val arr = JSONArray(get("${profile.baseUrl}/api/events?$query"))
            List(arr.length()) { parseEvent(arr.getJSONObject(it)) }
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
            val arr = JSONArray(get(url))
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val end = o.optDoubleOrNull("end_time") ?: return@mapNotNull null
                RecordingSegment(o.getDouble("start_time"), end)
            }
        }
    }

    /** Raw bytes for images (snapshots and thumbnails). */
    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        request(url) { conn -> conn.inputStream.use { it.readBytes() } }
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

    private fun get(url: String): String = request(url) { conn ->
        conn.inputStream.bufferedReader().use { it.readText() }
    }

    /**
     * Opens [url], checks the status, and hands the connection to [block], which must read the
     * response body to the end and close it.
     *
     * Connections are kept alive and pooled: a successful, fully read response is not
     * disconnect()ed, because on Android that closes the socket and every poll or thumbnail would
     * pay a new TCP handshake. Only failed requests are disconnected. Gzip is negotiated and
     * decoded transparently by HttpURLConnection as long as no Accept-Encoding header is set here.
     */
    private fun <T> request(url: String, block: (HttpURLConnection) -> T): T {
        val conn = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Accept", "application/json, image/*, */*")
            }
        } catch (e: IOException) {
            throw FrigateException("Invalid server address")
        }
        var completed = false
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw FrigateException("Server returned HTTP $code", code)
            }
            return block(conn).also { completed = true }
        } catch (e: IOException) {
            throw FrigateException("Cannot reach server (${e.javaClass.simpleName})")
        } finally {
            if (!completed) conn.disconnect()
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw FrigateException("Unexpected response from server")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 10_000
        private val RTSP_PATH = Regex("""rtsp://[^/]+/([^?\s]+)""")

        internal fun parseCameras(config: JSONObject): List<CameraInfo> {
            val cams = config.getJSONObject("cameras")
            return cams.keys().asSequence().map { name ->
                val cam = cams.getJSONObject(name)
                val inputs = cam.optJSONObject("ffmpeg")?.optJSONArray("inputs") ?: JSONArray()
                var detect: String? = null
                var record: String? = null
                for (i in 0 until inputs.length()) {
                    val input = inputs.getJSONObject(i)
                    val stream = RTSP_PATH.find(input.getString("path"))?.groupValues?.get(1) ?: continue
                    val roles = input.optJSONArray("roles") ?: JSONArray()
                    for (r in 0 until roles.length()) {
                        when (roles.getString(r)) {
                            "detect" -> detect = stream
                            "record" -> record = stream
                        }
                    }
                }
                val detectCfg = cam.optJSONObject("detect")
                CameraInfo(
                    id = name,
                    enabled = cam.optBoolean("enabled", true),
                    detectStream = detect,
                    recordStream = record,
                    detectWidth = detectCfg?.optInt("width", 0)?.takeIf { it > 0 },
                    detectHeight = detectCfg?.optInt("height", 0)?.takeIf { it > 0 },
                )
            }.toList()
        }

        internal fun parseEvent(o: JSONObject): FrigateEvent {
            val data = o.optJSONObject("data")
            return FrigateEvent(
                id = o.getString("id"),
                camera = o.getString("camera"),
                label = o.getString("label"),
                subLabel = o.optStringOrNull("sub_label"),
                startTime = o.getDouble("start_time"),
                endTime = o.optDoubleOrNull("end_time"),
                hasClip = o.optBoolean("has_clip", false),
                hasSnapshot = o.optBoolean("has_snapshot", false),
                score = data?.optDoubleOrNull("top_score") ?: data?.optDoubleOrNull("score"),
            )
        }

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }
    }
}
