package com.frigatetv.app.data

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** On-disk JSON formats, including the org.json-era shape written by builds before kotlinx.serialization. */
class SettingsStoreFormatTest {

    private val profilesSerializer = ListSerializer(StoredProfile.serializer())

    @Test
    fun `profiles saved by the org json build decode, with missing scheme and rtspPort defaulted`() {
        val legacy = """[{"id":"home-1","name":"Home","host":"10.0.0.5","port":9150}]"""

        val profile = frigateJson.decodeFromString(profilesSerializer, legacy).single()

        assertEquals(StoredProfile("home-1", "Home", "10.0.0.5", 9150, scheme = null, rtspPort = 8554), profile)
        assertEquals(Scheme.HTTP, Scheme.fromWire(profile.scheme))
    }

    @Test
    fun `profile written with every field round-trips and keeps the org json key names`() {
        val written = StoredProfile("p", "Lab", "frigate.lan", 5001, scheme = "https", rtspPort = 18554)

        val json = frigateJson.encodeToString(profilesSerializer, listOf(written))

        assertTrue(json.contains("\"scheme\":\"https\""))
        assertTrue(json.contains("\"rtspPort\":18554"))
        assertEquals(listOf(written), frigateJson.decodeFromString(profilesSerializer, json))
    }

    @Test
    fun `prefs saved by the org json build decode fully`() {
        val legacy =
            """{"order":["back","front"],"favorites":["front"],"columns":3,"lastCamera":"front","muted":true}"""

        val prefs = frigateJson.decodeFromString<StoredPrefs>(legacy)

        assertEquals(
            StoredPrefs(
                order = listOf("back", "front"),
                favorites = listOf("front"),
                columns = 3,
                lastCamera = "front",
                muted = true,
            ),
            prefs,
        )
    }

    @Test
    fun `prefs with missing fields or nulls fall back to defaults`() {
        assertEquals(StoredPrefs(), frigateJson.decodeFromString<StoredPrefs>("{}"))
        assertEquals(
            StoredPrefs(columns = 0, lastCamera = null),
            frigateJson.decodeFromString<StoredPrefs>("""{"columns": null, "lastCamera": null}"""),
        )
    }

    @Test
    fun `prefs written now keep the field names older builds read`() {
        val prefs = StoredPrefs(
            order = listOf("a"),
            favorites = listOf("a"),
            columns = 2,
            lastCamera = "a",
            muted = true,
        )

        val json = frigateJson.encodeToString(prefs)

        assertTrue(json.contains("\"order\":[\"a\"]"))
        assertTrue(json.contains("\"favorites\":[\"a\"]"))
        assertTrue(json.contains("\"columns\":2"))
        assertTrue(json.contains("\"lastCamera\":\"a\""))
        assertTrue(json.contains("\"muted\":true"))
        assertEquals(prefs, frigateJson.decodeFromString<StoredPrefs>(json))
    }

    @Test
    fun `stored prefs that are not an object are rejected so the caller can use defaults`() {
        assertThrows(SerializationException::class.java) {
            frigateJson.decodeFromString<StoredPrefs>("[1, 2]")
        }
        assertNull(runCatching { frigateJson.decodeFromString<StoredPrefs>("not json") }.getOrNull())
    }
}
