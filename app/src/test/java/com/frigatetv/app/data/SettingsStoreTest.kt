package com.frigatetv.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Round trip through the real DataStore-backed [SettingsStore] (Robolectric supplies the Context). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest {

    private val store = SettingsStore(ApplicationProvider.getApplicationContext())

    @Test
    fun `profiles and active id survive a write and read`() = runBlocking {
        val profiles = listOf(
            ServerProfile(id = "home", name = "Home", host = "10.0.0.5", port = 5001, scheme = Scheme.HTTPS),
            ServerProfile(id = "lab", name = "Lab", host = "lab.local", rtspPort = 18554),
        )

        store.writeProfiles(profiles, activeId = "lab")

        assertEquals(profiles, store.readProfiles())
        assertEquals("lab", store.readActiveId())
    }

    @Test
    fun `preferences survive a write and read, and an empty lastCamera reads as null`() = runBlocking {
        val prefs = ProfilePrefs(
            cameraOrder = listOf("back", "front"),
            favorites = setOf("front"),
            columns = 3,
            lastCamera = "front",
            muted = true,
        )

        store.writePrefs("round-trip", prefs)
        assertEquals(prefs, store.readPrefs("round-trip"))

        store.writePrefs("round-trip", prefs.copy(lastCamera = ""))
        assertNull(store.readPrefs("round-trip").lastCamera)
    }

    @Test
    fun `deleted or never written preferences read as defaults`() = runBlocking {
        store.writePrefs("to-delete", ProfilePrefs(columns = 2))

        store.deletePrefs("to-delete")

        assertEquals(ProfilePrefs(), store.readPrefs("to-delete"))
        assertEquals(ProfilePrefs(), store.readPrefs("never-written"))
    }
}
