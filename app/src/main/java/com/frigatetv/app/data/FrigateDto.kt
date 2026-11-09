package com.frigatetv.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Lenient decoder for Frigate responses and persisted settings. Unknown keys are ignored, and a
 * null or wrongly typed value for a field with a default falls back to that default.
 */
internal val frigateJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** `/api/config`. Only the fields the app reads are declared. */
@Serializable
internal data class ConfigDto(
    val cameras: Map<String, CameraDto>,
)

@Serializable
internal data class CameraDto(
    val enabled: Boolean = true,
    val ffmpeg: FfmpegDto? = null,
    val detect: DetectDto? = null,
)

@Serializable
internal data class FfmpegDto(
    val inputs: List<FfmpegInputDto> = emptyList(),
)

@Serializable
internal data class FfmpegInputDto(
    val path: String,
    val roles: List<String> = emptyList(),
)

@Serializable
internal data class DetectDto(
    val width: Int? = null,
    val height: Int? = null,
)

/** One element of `/api/events`. */
@Serializable
internal data class EventDto(
    val id: String,
    val camera: String,
    val label: String,
    @SerialName("sub_label") val subLabel: String? = null,
    @SerialName("start_time") val startTime: Double,
    @SerialName("end_time") val endTime: Double? = null,
    @SerialName("has_clip") val hasClip: Boolean = false,
    @SerialName("has_snapshot") val hasSnapshot: Boolean = false,
    val data: EventDataDto? = null,
)

@Serializable
internal data class EventDataDto(
    @SerialName("top_score") val topScore: Double? = null,
    val score: Double? = null,
)

/** One element of `/api/{camera}/recordings`. Start is optional so entries without an end are skipped, as before. */
@Serializable
internal data class RecordingDto(
    @SerialName("start_time") val startTime: Double? = null,
    @SerialName("end_time") val endTime: Double? = null,
)
