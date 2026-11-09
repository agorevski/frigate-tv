package com.frigatetv.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.Closeable
import java.util.Locale

/** Fixture events, relative to the current time. The events file marks offsets as {{NOW-600}}. */
const val EVENTS_FIXTURE = "/ui/events.json"
const val PEOPLE_ONLY_EVENTS_FIXTURE = "/ui/events-people-only.json"

private const val CONFIG_FIXTURE = "/ui/config.json"
private const val FRIGATE_VERSION = "0.16.2"
private val NOW_TOKEN = Regex("""\{\{NOW([+-]\d+)}}""")
private val RECORDINGS_PATH = Regex("""/api/[^/]+/recordings""")

/**
 * A stand-in for the Frigate HTTP API, served by MockWebServer on localhost. It answers the calls
 * the app makes on connect, the events list, and recordings. Anything else is a 404.
 */
class FakeFrigateServer(private val eventsFixture: String = EVENTS_FIXTURE) : Closeable {

    private val server = MockWebServer()

    /** Port the server listens on. Still reported after [shutdown], so tests can point at a dead server. */
    val port: Int

    init {
        server.dispatcher = Routes()
        server.start()
        port = server.port
    }

    /** Stops listening. Later requests to [port] are refused, which is how tests simulate an unreachable server. */
    fun shutdown() = server.shutdown()

    override fun close() = shutdown()

    private inner class Routes : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl ?: return notFound()
            return when {
                url.encodedPath == "/api/version" -> MockResponse().setBody(FRIGATE_VERSION)
                url.encodedPath == "/api/config" -> json(fixture(CONFIG_FIXTURE))
                url.encodedPath == "/api/events" -> when {
                    url.queryParameter("in_progress") != null -> json("[]")
                    else -> json(eventsFor(url))
                }
                RECORDINGS_PATH.matches(url.encodedPath) -> json(recordingsFor(url))
                else -> notFound()
            }
        }
    }

    private fun eventsFor(url: HttpUrl): String {
        val camera = url.queryParameter("camera")
        val after = url.queryParameter("after")?.toDoubleOrNull() ?: 0.0
        val events = Json.parseToJsonElement(fixture(eventsFixture)).jsonArrayOrEmpty()
        val matching = events.filter { element ->
            val obj = element.jsonObject()
            (camera == null || obj.string("camera") == camera) && obj.double("start_time") >= after
        }
        return JsonArray(matching).toString()
    }

    private fun recordingsFor(url: HttpUrl): String {
        val after = url.queryParameter("after")?.toDoubleOrNull() ?: 0.0
        val before = url.queryParameter("before")?.toDoubleOrNull() ?: Double.MAX_VALUE
        // Two blocks per day, so the timeline has gaps and both chips have footage.
        val blocks = listOf(3_600.0 to 14_400.0, 36_000.0 to 39_600.0)
        return buildJsonArray {
            for ((from, to) in blocks) {
                val start = after + from
                val end = after + to
                if (start < before && end > after) {
                    add(
                        buildJsonObject {
                            put("start_time", start)
                            put("end_time", end)
                        },
                    )
                }
            }
        }.toString()
    }

    private fun json(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun notFound() = MockResponse().setResponseCode(404)

    private fun fixture(path: String): String {
        val raw = checkNotNull(javaClass.getResource(path)) { "missing fixture $path" }.readText()
        val nowSeconds = System.currentTimeMillis() / 1000.0
        return NOW_TOKEN.replace(raw) { match ->
            String.format(Locale.ROOT, "%.3f", nowSeconds + match.groupValues[1].toDouble())
        }
    }
}

private fun JsonElement.jsonArrayOrEmpty(): List<JsonElement> = (this as? JsonArray).orEmpty()

private fun JsonElement.jsonObject(): JsonObject = this as JsonObject

private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.double(key: String): Double = this[key]?.jsonPrimitive?.double ?: 0.0
