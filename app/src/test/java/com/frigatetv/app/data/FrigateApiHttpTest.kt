package com.frigatetv.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrigateApiHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var api: FrigateApi
    private lateinit var profile: ServerProfile

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        api = FrigateApi(OkHttpClient())
        profile = ServerProfile(name = "Test", host = server.hostName, port = server.port)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `events query carries limit, truncated after, encoded camera and in_progress flag`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.events(profile, limit = 5, after = 1_700_000_000.9, camera = "front door", inProgress = true)

        assertEquals(
            "/api/events?limit=5&after=1700000000&camera=front+door&in_progress=1",
            server.takeRequest().path,
        )
    }

    @Test
    fun `events query omits optional parameters when they are not given`() = runBlocking {
        server.enqueue(MockResponse().setBody("[]"))

        api.events(profile, limit = 50)

        assertEquals("/api/events?limit=50", server.takeRequest().path)
    }

    @Test
    fun `recordings skip segments without an end and keep fractional times`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """[{"start_time": 1.0, "end_time": 2.0},
                    {"start_time": 3.0, "end_time": null},
                    {"start_time": 5.5, "end_time": 9.25}]""",
            ),
        )

        val segments = api.recordings(profile, camera = "back", after = 100.7, before = 200.0)

        assertEquals(listOf(RecordingSegment(1.0, 2.0), RecordingSegment(5.5, 9.25)), segments)
        assertEquals("/api/back/recordings?after=100&before=200", server.takeRequest().path)
    }

    @Test
    fun `recording with an end but no start fails the whole response`() {
        server.enqueue(MockResponse().setBody("""[{"end_time": 2.0}]"""))

        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.recordings(profile, camera = "back", after = 0.0, before = 1.0) }
        }

        assertEquals("Unexpected response from server", error.message)
    }

    @Test
    fun `truncated config json is reported as an unexpected response`() {
        server.enqueue(MockResponse().setBody("""{"cameras": {"front": """))

        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.cameras(profile) }
        }

        assertEquals("Unexpected response from server", error.message)
    }

    @Test
    fun `non-2xx status is reported with its code`() {
        server.enqueue(MockResponse().setResponseCode(500))

        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.version(profile) }
        }

        assertEquals("Server returned HTTP 500", error.message)
        assertEquals(500, error.statusCode)
    }

    @Test
    fun `version is trimmed and a blank body is an error`() {
        server.enqueue(MockResponse().setBody("  0.16.2\n"))
        server.enqueue(MockResponse().setBody("   "))

        assertEquals("0.16.2", runBlocking { api.version(profile) })
        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.version(profile) }
        }
        assertEquals("Empty version response", error.message)
    }

    @Test
    fun `host that cannot form a url is reported as an invalid address`() {
        val badProfile = ServerProfile(name = "Bad", host = "not a host")

        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.version(badProfile) }
        }

        assertEquals("Invalid server address", error.message)
    }

    @Test
    fun `refused connection is reported as unreachable`() {
        val closedPort = MockWebServer().let { s ->
            s.start()
            s.port.also { s.shutdown() }
        }
        val deadProfile = ServerProfile(name = "Dead", host = "127.0.0.1", port = closedPort)

        val error = assertThrows(FrigateException::class.java) {
            runBlocking { api.version(deadProfile) }
        }

        assertTrue(error.message.orEmpty().startsWith("Cannot reach server"))
    }
}
