@file:OptIn(ExperimentalTestApi::class)

package com.frigatetv.app.ui

import android.app.Application
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.data.ProfilePrefs
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.SettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket

const val HOME_PROFILE_NAME = "Fixture Home"
private const val LONG_TIMEOUT_MS = 20_000L

/**
 * Base for Compose UI tests of the real app shell ([FrigateTvApp]) under Robolectric. The
 * [AppViewModel] is created as the app creates it, with the active profile seeded in DataStore
 * to point at a [FakeFrigateServer] on localhost.
 */
@RunWith(RobolectricTestRunner::class)
// Android TV size (960x540dp), the app's main target, so lazy lists and forms lay out on screen.
@Config(qualifiers = "w960dp-h540dp-land")
abstract class FrigateUiTest {

    @get:Rule
    val compose = createComposeRule()

    protected val app: Application = ApplicationProvider.getApplicationContext()

    private val viewModelStore = ViewModelStore()
    protected lateinit var frigate: FakeFrigateServer
    protected lateinit var vm: AppViewModel
    protected lateinit var home: ServerProfile

    /**
     * Seeds the active profile, creates the view model, and sets the app as content. The profile
     * points at the fake server; its RTSP port is closed, so every live stream fails at once.
     * Pass [reachable] = false to stop the server before the app connects.
     */
    protected fun launchApp(eventsFixture: String = EVENTS_FIXTURE, reachable: Boolean = true) {
        frigate = FakeFrigateServer(eventsFixture)
        home = ServerProfile(
            id = "home",
            name = HOME_PROFILE_NAME,
            host = "127.0.0.1",
            port = frigate.port,
            rtspPort = closedPort(),
        )
        seedProfiles(listOf(home), activeId = home.id)
        if (!reachable) frigate.shutdown()
        vm = ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory.getInstance(app))
            .get(AppViewModel::class.java)
        compose.setContent { FrigateTvTheme { FrigateTvApp(vm) } }
    }

    protected fun seedProfiles(profiles: List<ServerProfile>, activeId: String) = runBlocking {
        val store = SettingsStore(app)
        store.writeProfiles(profiles, activeId)
        profiles.forEach { store.writePrefs(it.id, ProfilePrefs()) }
    }

    protected fun openTab(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    protected fun waitForText(text: String, timeoutMs: Long = LONG_TIMEOUT_MS) {
        compose.waitUntilAtLeastOneExists(hasText(text), timeoutMs)
    }

    protected fun waitForTextContaining(text: String, timeoutMs: Long = LONG_TIMEOUT_MS) {
        compose.waitUntilAtLeastOneExists(hasText(text, substring = true), timeoutMs)
    }

    /** Types into the add-profile form's fields in order: name, host, port (the port field starts as 9150). */
    protected fun fillProfileForm(name: String, host: String, port: Int) {
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performScrollTo().performTextReplacement(name)
        fields[1].performScrollTo().performTextReplacement(host)
        fields[2].performScrollTo().performTextReplacement(port.toString())
        compose.waitForIdle()
    }

    protected fun openAddProfileForm() {
        compose.onNodeWithText("Add server profile").performScrollTo().performClick()
        waitForText("New server profile")
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        if (::frigate.isInitialized) frigate.shutdown()
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }
}
