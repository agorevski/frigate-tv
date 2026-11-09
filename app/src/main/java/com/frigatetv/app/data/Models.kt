package com.frigatetv.app.data

import java.util.UUID

enum class Scheme(val wire: String) {
    HTTP("http"),
    HTTPS("https");

    companion object {
        fun fromWire(value: String?): Scheme = entries.firstOrNull { it.wire == value } ?: HTTP
    }
}

/** A saved Frigate server. Stored locally only (DataStore). */
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 9150,
    val scheme: Scheme = Scheme.HTTP,
    /** go2rtc RTSP port on the same host (verified 8554 on the reference install). */
    val rtspPort: Int = 8554,
) {
    val baseUrl: String get() = "${scheme.wire}://$host:$port"

    fun rtspUrl(stream: String): String = "rtsp://$host:$rtspPort/$stream"
}

/**
 * A Frigate camera. Stream names come from the Frigate config's ffmpeg inputs
 * (role "detect" = low-res grid stream, role "record" = main stream), never from guesses.
 */
data class CameraInfo(
    val id: String,
    val enabled: Boolean,
    val detectStream: String?,
    val recordStream: String?,
    val detectWidth: Int?,
    val detectHeight: Int?,
)

data class FrigateEvent(
    val id: String,
    val camera: String,
    val label: String,
    val subLabel: String?,
    val startTime: Double,
    val endTime: Double?,
    val hasClip: Boolean,
    val hasSnapshot: Boolean,
    val score: Double?,
)

data class RecordingSegment(val startTime: Double, val endTime: Double)

/** Per-profile user preferences. */
data class ProfilePrefs(
    /** Camera ids in display order. Cameras not listed keep discovery order after listed ones. */
    val cameraOrder: List<String> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** 0 = automatic (balanced for the screen), otherwise a fixed column count. */
    val columns: Int = 0,
    val lastCamera: String? = null,
    val muted: Boolean = false,
)

class FrigateException(message: String, val statusCode: Int? = null, cause: Throwable? = null) :
    Exception(message, cause)

fun orderedCameras(cameras: List<CameraInfo>, order: List<String>): List<CameraInfo> {
    val rank = order.withIndex().associate { it.value to it.index }
    return cameras.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

/** "reolink_camera" -> "Reolink Camera". Display only; the id is what the API uses. */
fun displayName(cameraId: String): String =
    cameraId.split('_').filter { it.isNotEmpty() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }
