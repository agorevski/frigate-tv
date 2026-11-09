package com.frigatetv.app.data

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrigateConfigParsingTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/parse/$name")) { "missing fixture $name" }.readText()

    private fun camera(cameras: List<CameraInfo>, id: String) = cameras.single { it.id == id }

    @Test
    fun `realistic 0_16 config yields every camera in document order`() {
        val cameras = FrigateApi.parseCameras(fixture("frigate_config_0_16.json"))

        assertEquals(listOf("front", "back", "reolink_camera", "garage", "patio", "shed"), cameras.map { it.id })
        assertEquals(
            CameraInfo("front", true, "front_sub", "front", 352, 240),
            camera(cameras, "front"),
        )
        assertEquals(
            CameraInfo("reolink_camera", true, "reolink_camera_sub", "reolink_camera", 1280, 720),
            camera(cameras, "reolink_camera"),
        )
    }

    @Test
    fun `detect and record streams come from the ffmpeg input roles with query strings stripped`() {
        val front = camera(FrigateApi.parseCameras(fixture("frigate_config_0_16.json")), "front")

        assertEquals("front_sub", front.detectStream)
        assertEquals("front", front.recordStream)
    }

    @Test
    fun `disabled camera is reported as disabled but keeps its streams`() {
        val garage = camera(FrigateApi.parseCameras(fixture("frigate_config_0_16.json")), "garage")

        assertEquals(false, garage.enabled)
        assertEquals("garage_sub", garage.detectStream)
        assertEquals("garage", garage.recordStream)
    }

    @Test
    fun `missing enabled defaults to true, and a record-only camera has no detect stream or size`() {
        val patio = camera(FrigateApi.parseCameras(fixture("frigate_config_0_16.json")), "patio")

        assertEquals(CameraInfo("patio", true, null, "patio_main", null, null), patio)
    }

    @Test
    fun `non-rtsp inputs are ignored and zero detect dimensions become null`() {
        val shed = camera(FrigateApi.parseCameras(fixture("frigate_config_0_16.json")), "shed")

        assertEquals(CameraInfo("shed", true, null, null, null, null), shed)
    }

    @Test
    fun `detect and record streams are read from roles`() {
        val config = """
            {"cameras": {"yard": {"ffmpeg": {"inputs": [
              {"path": "rtsp://127.0.0.1:8554/yard", "roles": ["detect", "record"]}
            ]}}}}
        """.trimIndent()

        val yard = FrigateApi.parseCameras(config).single()

        assertEquals("yard", yard.detectStream)
        assertEquals("yard", yard.recordStream)
    }

    @Test
    fun `when a role is listed twice the later input wins`() {
        val config = """
            {"cameras": {"yard": {"ffmpeg": {"inputs": [
              {"path": "rtsp://127.0.0.1:8554/yard_a", "roles": ["detect"]},
              {"path": "rtsp://127.0.0.1:8554/yard_b", "roles": ["detect"]}
            ]}}}}
        """.trimIndent()

        assertEquals("yard_b", FrigateApi.parseCameras(config).single().detectStream)
    }

    @Test
    fun `detect size is kept only when both dimensions come from a positive detect block`() {
        val config = """
            {"cameras": {"w": {"detect": {"width": 1920}}, "h": {"detect": {"width": 640, "height": 480}}}}
        """.trimIndent()

        val (w, h) = FrigateApi.parseCameras(config)

        assertEquals(1920, w.detectWidth)
        assertNull(w.detectHeight)
        assertEquals(640, h.detectWidth)
        assertEquals(480, h.detectHeight)
    }

    @Test
    fun `config without a cameras object is rejected`() {
        assertThrows(SerializationException::class.java) {
            FrigateApi.parseCameras("""{"version": "0.16.2"}""")
        }
    }

    @Test
    fun `event with null end_time is in progress and missing optional fields take defaults`() {
        val event = FrigateApi.parseEvent(
            """
            {"id": "1700000000.1-abc", "camera": "front", "label": "person",
             "start_time": 1700000000.1, "end_time": null}
            """.trimIndent(),
        )

        assertNull(event.endTime)
        assertEquals(false, event.hasClip)
        assertEquals(false, event.hasSnapshot)
        assertNull(event.score)
        assertNull(event.subLabel)
    }

    @Test
    fun `score prefers top_score and falls back to score`() {
        fun scoreOf(data: String) = FrigateApi.parseEvent(
            """{"id": "e", "camera": "c", "label": "car", "start_time": 1.0, "data": $data}""",
        ).score

        assertEquals(0.91, scoreOf("""{"top_score": 0.91, "score": 0.5}"""))
        assertEquals(0.5, scoreOf("""{"top_score": null, "score": 0.5}"""))
        assertNull(scoreOf("""{}"""))
    }

    @Test
    fun `blank or null sub_label is treated as absent`() {
        fun subLabelOf(value: String) = FrigateApi.parseEvent(
            """{"id": "e", "camera": "c", "label": "person", "start_time": 1.0, "sub_label": $value}""",
        ).subLabel

        assertNull(subLabelOf("null"))
        assertNull(subLabelOf("\"\""))
        assertNull(subLabelOf("\"   \""))
        assertEquals("Alice", subLabelOf("\"Alice\""))
    }

    @Test
    fun `null for a defaulted non-null flag falls back to its default`() {
        val event = FrigateApi.parseEvent(
            """{"id": "e", "camera": "c", "label": "dog", "start_time": 1.0, "has_clip": null, "has_snapshot": true}""",
        )

        assertEquals(false, event.hasClip)
        assertEquals(true, event.hasSnapshot)
    }

    @Test
    fun `wrongly typed flag is rejected rather than guessed`() {
        assertThrows(SerializationException::class.java) {
            FrigateApi.parseEvent(
                """{"id": "e", "camera": "c", "label": "dog", "start_time": 1.0, "has_clip": "yes"}""",
            )
        }
    }

    @Test
    fun `event without start_time is rejected`() {
        assertThrows(SerializationException::class.java) {
            FrigateApi.parseEvent("""{"id": "e", "camera": "c", "label": "dog"}""")
        }
    }
}
