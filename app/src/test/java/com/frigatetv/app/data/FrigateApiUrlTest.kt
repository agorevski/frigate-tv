package com.frigatetv.app.data

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class FrigateApiUrlTest {

    private val api = FrigateApi(OkHttpClient())
    private val lan = ServerProfile(name = "Home", host = "10.0.0.5", port = 5001, scheme = Scheme.HTTPS)

    @Test
    fun `base url uses the scheme host and port of the profile`() {
        assertEquals("https://10.0.0.5:5001", lan.baseUrl)
        assertEquals("http://10.0.0.5:9150", ServerProfile(name = "Default", host = "10.0.0.5").baseUrl)
    }

    @Test
    fun `rtsp url uses the go2rtc port rather than the http port`() {
        val profile = ServerProfile(name = "Home", host = "10.0.0.5", port = 5001, rtspPort = 18554)

        assertEquals("rtsp://10.0.0.5:18554/front_sub", profile.rtspUrl("front_sub"))
    }

    @Test
    fun `camera snapshot url encodes characters that would change the path`() {
        assertEquals("https://10.0.0.5:5001/api/back/latest.jpg", api.cameraSnapshotUrl(lan, "back"))
        assertEquals(
            "https://10.0.0.5:5001/api/front%2Fdoor%3Fx%3D1%23a%25b/latest.jpg",
            api.cameraSnapshotUrl(lan, "front/door?x=1#a%b"),
        )
    }

    @Test
    fun `event urls keep dots and dashes of Frigate event ids and encode anything else`() {
        val id = "1700000000.123-abc_DEF"

        assertEquals("https://10.0.0.5:5001/api/events/$id/thumbnail.jpg", api.eventThumbnailUrl(lan, id))
        assertEquals("https://10.0.0.5:5001/api/events/$id/snapshot.jpg", api.eventSnapshotUrl(lan, id))
        assertEquals("https://10.0.0.5:5001/api/events/$id/clip.mp4", api.eventClipUrl(lan, id))
        assertEquals(
            "http://10.0.0.5:9150/api/events/a%2Fb/clip.mp4",
            api.eventClipUrl(ServerProfile(name = "x", host = "10.0.0.5"), "a/b"),
        )
    }

    @Test
    fun `vod url truncates both epoch bounds to whole seconds and encodes the camera`() {
        assertEquals(
            "https://10.0.0.5:5001/vod/back/start/1700000000/end/1700003600/index.m3u8",
            api.vodUrl(lan, "back", 1_700_000_000.9, 1_700_003_600.2),
        )
        assertEquals("https://10.0.0.5:5001/vod/a%2Fb/start/1/end/2/index.m3u8", api.vodUrl(lan, "a/b", 1.0, 2.0))
    }
}
