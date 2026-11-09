package com.frigatetv.app.contract

import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateException
import com.frigatetv.app.data.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress

/**
 * Contract between the app and the Frigate 0.16.2 HTTP API. Fixtures in src/test/resources/api are
 * trimmed copies of real responses with host data replaced. MockWebServer serves them, and each test
 * checks the request the app sent and how the response was decoded.
 */
class FrigateApiContractTest {

    private lateinit var server: MockWebServer
    private val api = FrigateApi()
    private val profile by lazy { ServerProfile(id = "test", name = "Test", host = "127.0.0.1", port = server.port) }

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start(InetAddress.getLoopbackAddress(), 0)
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("api/$name")) { "missing fixture $name" }.readText()

    private fun json(body: String): MockResponse = MockResponse().setBody(body)

    @Test
    fun version_is_read_from_api_version_and_trimmed() {
        server.enqueue(MockResponse().setBody("0.16.2-4d58206\n"))

        val version = runBlocking { api.version(profile) }

        assertEquals("0.16.2-4d58206", version)
        assertEquals("/api/version", server.takeRequest().path)
    }

    @Test
    fun empty_version_body_is_reported_as_unexpected() {
        server.enqueue(MockResponse().setBody("  \n"))

        val error = assertFailsWithFrigate { runBlocking { api.version(profile) } }

        assertEquals("Empty version response", error.message)
    }

    @Test
    fun cameras_come_from_config_in_document_order_with_stream_roles() {
        server.enqueue(json(fixture("config.json")))

        val cameras = runBlocking { api.cameras(profile) }

        assertEquals("/api/config", server.takeRequest().path)
        assertEquals(listOf("front", "back", "mailbox", "driveway", "reolink_camera"), cameras.map { it.id })
        val front = cameras.first()
        assertTrue(front.enabled)
        assertEquals("front_sub", front.detectStream)
        assertEquals("front", front.recordStream)
        assertEquals(352, front.detectWidth)
        assertEquals(240, front.detectHeight)
    }

    @Test
    fun audio_role_does_not_replace_the_record_stream() {
        server.enqueue(json(fixture("config.json")))

        val back = runBlocking { api.cameras(profile) }.first { it.id == "back" }

        assertEquals("back", back.recordStream)
        assertEquals("back_sub", back.detectStream)
    }

    @Test
    fun zero_detect_size_is_unknown_and_missing_ffmpeg_gives_no_streams() {
        server.enqueue(json(fixture("config.json")))
        val reolink = runBlocking { api.cameras(profile) }.first { it.id == "reolink_camera" }
        assertNull(reolink.detectWidth)
        assertNull(reolink.detectHeight)
        assertEquals("reolink_camera", reolink.recordStream)

        server.enqueue(json("""{"cameras":{"porch":{"enabled":false}}}"""))
        val porch = runBlocking { api.cameras(profile) }.single()
        assertFalse(porch.enabled)
        assertNull(porch.detectStream)
        assertNull(porch.recordStream)
        assertNull(porch.detectWidth)
    }

    @Test
    fun events_query_sends_limit_camera_after_and_in_progress_flags() {
        server.enqueue(json("[]"))

        runBlocking { api.events(profile, limit = 50, after = 1794200000.9, camera = "driveway", inProgress = true) }

        assertEquals("/api/events?limit=50&after=1794200000&camera=driveway&in_progress=1", server.takeRequest().path)
    }

    @Test
    fun events_default_query_only_sends_limit() {
        server.enqueue(json("[]"))

        val events = runBlocking { api.events(profile, limit = 1) }

        assertTrue(events.isEmpty())
        assertEquals("/api/events?limit=1", server.takeRequest().path)
    }

    @Test
    fun events_map_fields_and_score_fallbacks_from_the_response() {
        server.enqueue(json(fixture("events.json")))

        val events = runBlocking { api.events(profile, limit = 50) }

        assertEquals(3, events.size)
        val person = events[0]
        assertEquals("driveway", person.camera)
        assertEquals("person", person.label)
        assertNull(person.subLabel)
        assertTrue(person.hasClip)
        assertEquals(0.80078125, person.score!!, 0.0)
        assertEquals(1794200673.652964, person.endTime!!, 0.0)

        val car = events[1]
        assertNull("blank sub_label is treated as absent", car.subLabel)
        assertEquals("score falls back to data.score when top_score is absent", 0.91, car.score!!, 0.0)

        val dog = events[2]
        assertEquals("Biscuit", dog.subLabel)
        assertFalse(dog.hasClip)
        assertEquals(0.6, dog.score!!, 0.0)
    }

    @Test
    fun in_progress_event_has_no_end_time() {
        server.enqueue(json(fixture("events_in_progress.json")))

        val event = runBlocking { api.events(profile, limit = 50, inProgress = true) }.single()

        assertEquals("/api/events?limit=50&in_progress=1", server.takeRequest().path)
        assertEquals("mailbox", event.camera)
        assertNull(event.endTime)
        assertNull(event.score)
        assertFalse(event.hasSnapshot)
    }

    @Test
    fun recordings_query_uses_camera_path_and_whole_epoch_bounds() {
        server.enqueue(json("[]"))

        runBlocking { api.recordings(profile, camera = "driveway", after = 1794196800.0, before = 1794240000.75) }

        assertEquals("/api/driveway/recordings?after=1794196800&before=1794240000", server.takeRequest().path)
    }

    @Test
    fun recording_segments_without_end_time_are_skipped() {
        server.enqueue(json(fixture("recordings.json")))

        val segments = runBlocking { api.recordings(profile, "driveway", 1794196800.0, 1794240000.0) }

        assertEquals(
            listOf(1794196800.0 to 1794197400.5, 1794198000.0 to 1794198600.0),
            segments.map { it.startTime to it.endTime },
        )
    }

    @Test
    fun http_error_status_is_kept_on_the_exception() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

        val error = assertFailsWithFrigate { runBlocking { api.events(profile, limit = 1) } }

        assertEquals(503, error.statusCode)
        assertEquals("Server returned HTTP 503", error.message)
    }

    @Test
    fun non_json_body_is_reported_as_unexpected_response() {
        server.enqueue(MockResponse().setBody("<html>proxy error</html>"))

        val error = assertFailsWithFrigate { runBlocking { api.cameras(profile) } }

        assertEquals("Unexpected response from server", error.message)
    }

    @Test
    fun refused_connection_is_reported_as_cannot_reach_server() {
        val closedPort = server.port
        server.shutdown()

        val error = assertFailsWithFrigate {
            runBlocking { api.version(ServerProfile(name = "Gone", host = "127.0.0.1", port = closedPort)) }
        }

        assertTrue(error.message, error.message!!.startsWith("Cannot reach server"))
    }

    @Test
    fun malformed_host_is_reported_as_invalid_server_address() {
        val error = assertFailsWithFrigate {
            runBlocking { api.version(ServerProfile(name = "Bad", host = "bad host", port = 9150)) }
        }

        assertEquals("Invalid server address", error.message)
    }

    @Test
    fun media_urls_follow_the_frigate_paths() {
        val p = ServerProfile(name = "Home", host = "192.0.2.10", port = 5000, rtspPort = 8554)
        val eventId = "1794200664.461925-uigkj2"

        assertEquals("http://192.0.2.10:5000/api/front/latest.jpg", api.cameraSnapshotUrl(p, "front"))
        assertEquals("http://192.0.2.10:5000/api/events/$eventId/thumbnail.jpg", api.eventThumbnailUrl(p, eventId))
        assertEquals("http://192.0.2.10:5000/api/events/$eventId/snapshot.jpg", api.eventSnapshotUrl(p, eventId))
        assertEquals("http://192.0.2.10:5000/api/events/$eventId/clip.mp4", api.eventClipUrl(p, eventId))
        assertEquals(
            "http://192.0.2.10:5000/vod/driveway/start/1794200000/end/1794200600/index.m3u8",
            api.vodUrl(p, "driveway", 1794200000.7, 1794200600.2),
        )
        assertEquals("rtsp://192.0.2.10:8554/front_sub", p.rtspUrl("front_sub"))
    }

    private fun assertFailsWithFrigate(block: () -> Unit): FrigateException {
        try {
            block()
        } catch (e: FrigateException) {
            return e
        }
        fail("expected FrigateException")
        throw AssertionError("unreachable")
    }
}
