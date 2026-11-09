package com.frigatetv.app.logic

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.frigatetv.app.AppState
import com.frigatetv.app.AppViewModel
import com.frigatetv.app.BuildConfig
import com.frigatetv.app.Connection
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Profile lifecycle, failed connection tests and server or network recovery in [AppViewModel]
 * (the logic behind 06_profile_failure.sh and 08_profile_network.sh).
 *
 * The ViewModel runs on a [StandardTestDispatcher], so the test thread decides when coroutines run.
 * Frigate is a loopback [MockWebServer]. Profiles are seeded into the real DataStore, and each
 * "relaunch" is a new ViewModel reading that store again. Retry and health-check delays are
 * virtual time, advanced explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppViewModelProfileTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val store = SettingsStore(app)
    private val scheduler = TestCoroutineScheduler()
    private lateinit var server: MockWebServer
    private lateinit var vm: AppViewModel
    private var serverPort = 0

    private val home by lazy { ServerProfile(id = HOME_ID, name = "Home", host = "127.0.0.1", port = serverPort) }
    private val qa by lazy { ServerProfile(id = QA_ID, name = "QA-Test-Server", host = "127.0.0.1", port = serverPort) }

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        startServer(0)
        runBlocking {
            store.writeProfiles(emptyList(), "")
            store.deletePrefs(HOME_ID)
            store.deletePrefs(QA_ID)
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun startServer(port: Int) {
        server = MockWebServer()
        server.dispatcher = routes
        server.start(InetAddress.getLoopbackAddress(), port)
        serverPort = server.port
    }

    private val routes = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/api/version" -> MockResponse().setBody("0.16.2-4d58206")
            "/api/config" -> MockResponse().setBody(fixture("config.json"))
            "/api/events?limit=50&in_progress=1" -> MockResponse().setBody("[]")
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("api/$name")) { "missing fixture $name" }.readText()

    /** Replaces the stored profiles and starts a ViewModel that loads them (an app launch). */
    private fun launch(vararg profiles: ServerProfile, active: String) {
        runBlocking { store.writeProfiles(profiles.toList(), active) }
        vm = AppViewModel(app)
        awaitState("launch to finish") { it.profiles.isNotEmpty() && it.active != null }
    }

    private fun relaunch(): AppViewModel {
        vm = AppViewModel(app)
        awaitState("relaunch to finish") { it.profiles.isNotEmpty() && it.active != null }
        return vm
    }

    /** Runs queued coroutines until [done] holds, polling real time for work on the IO threads. */
    private fun awaitState(what: String, timeoutMs: Long = 10_000, done: (AppState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            scheduler.runCurrent()
            val s = vm.state.value
            if (done(s)) return
            if (System.currentTimeMillis() > deadline) {
                fail(
                    "timed out waiting for $what: connection=${s.connection}, " +
                        "active=${s.active?.name}, notice=${s.notice}",
                )
            }
            Thread.sleep(5)
        }
    }

    private fun awaitConnected(name: String) = awaitState("connected to $name") {
        it.connection is Connection.Connected && it.active?.name == name && it.cameras.isNotEmpty()
    }

    /** Lets in-flight IO finish after advancing virtual time (used by the health loop). */
    private fun advanceVirtual(ms: Long) {
        scheduler.advanceTimeBy(ms)
        val end = System.currentTimeMillis() + 60
        do {
            scheduler.runCurrent()
            Thread.sleep(5)
        } while (System.currentTimeMillis() < end)
    }

    @Test
    fun launch_connects_to_the_active_profile_and_lists_its_cameras() {
        launch(home, active = HOME_ID)

        awaitConnected("Home")
        val s = vm.state.value
        assertEquals(Connection.Connected("0.16.2-4d58206"), s.connection)
        assertEquals(listOf("front", "back", "mailbox", "driveway", "reolink_camera"), s.cameras.map { it.id })
    }

    @Test
    fun first_launch_without_saved_profiles_offers_the_default_home_server() {
        runBlocking { store.writeProfiles(emptyList(), "") }

        vm = AppViewModel(app)
        awaitState("default profile loaded") { it.active != null }

        val home = vm.state.value.profiles.single()
        assertEquals("Home", home.name)
        assertEquals(BuildConfig.FRIGATE_HOST, home.host)
        assertEquals(9150, home.port)
        assertEquals(home, vm.state.value.active)
    }

    @Test
    fun unreachable_test_profile_fails_and_activation_is_refused_keeping_home() {
        launch(home, active = HOME_ID)
        awaitConnected("Home")
        val unreachable = ServerProfile(name = "ZZ Unreachable Test", host = "127.0.0.1", port = closedPort())

        val test = runBlocking { vm.testProfile(unreachable) }
        assertTrue(test.isFailure)
        val failure = test.exceptionOrNull()!!.message!!
        assertTrue(failure, failure.startsWith("Cannot reach server"))

        val saved = runBlocking { vm.saveProfile(unreachable, makeActive = true) }

        assertTrue(saved.isFailure)
        val notice = vm.state.value.notice.orEmpty()
        assertTrue(notice, notice.contains("Keeping the current profile"))
        assertEquals(HOME_ID, vm.state.value.active?.id)
        assertEquals(listOf(HOME_ID), vm.state.value.profiles.map { it.id })
        assertEquals(Connection.Connected("0.16.2-4d58206"), vm.state.value.connection)
        assertEquals(listOf(HOME_ID), runBlocking { store.readProfiles() }.map { it.id })
        assertEquals(HOME_ID, runBlocking { store.readActiveId() })
    }

    @Test
    fun edited_port_that_cannot_connect_is_refused_and_not_stored() {
        launch(home, active = HOME_ID)
        awaitConnected("Home")

        val badPort = home.copy(port = closedPort())
        val saved = runBlocking { vm.saveProfile(badPort, makeActive = true) }

        assertTrue(saved.isFailure)
        assertEquals(serverPort, vm.state.value.active?.port)
        assertEquals(serverPort, runBlocking { store.readProfiles() }.single().port)
    }

    @Test
    fun adding_a_reachable_profile_and_activating_it_switches_the_grid() {
        launch(home, active = HOME_ID)
        awaitConnected("Home")

        assertEquals("Frigate 0.16.2-4d58206 · 5 cameras", runBlocking { vm.testProfile(qa) }.getOrThrow().summary)
        runBlocking { vm.saveProfile(qa, makeActive = true) }.getOrThrow()

        awaitConnected("QA-Test-Server")
        assertEquals(listOf(HOME_ID, QA_ID), vm.state.value.profiles.map { it.id })
        assertEquals(QA_ID, runBlocking { store.readActiveId() })
    }

    @Test
    fun switching_back_to_home_reconnects_to_home() {
        launch(home, qa, active = QA_ID)
        awaitConnected("QA-Test-Server")

        runBlocking { vm.saveProfile(home, makeActive = true) }.getOrThrow()

        awaitConnected("Home")
        assertEquals(HOME_ID, runBlocking { store.readActiveId() })
    }

    @Test
    fun deleting_the_active_profile_falls_back_to_home_and_stays_deleted_after_relaunch() {
        launch(home, qa, active = QA_ID)
        awaitConnected("QA-Test-Server")

        vm.deleteProfile(QA_ID)

        awaitConnected("Home")
        assertEquals(listOf(HOME_ID), vm.state.value.profiles.map { it.id })
        relaunch()
        awaitConnected("Home")
        assertEquals(listOf(HOME_ID), vm.state.value.profiles.map { it.id })
        assertEquals(HOME_ID, vm.state.value.active?.id)
    }

    @Test
    fun server_outage_marks_the_connection_failed_then_reconnects_when_it_returns() {
        launch(home, active = HOME_ID)
        awaitConnected("Home")

        server.shutdown()
        // The health check runs every 4 s and calls /api/version on every eighth poll.
        repeat(8) { advanceVirtual(4_000) }
        awaitState("connection marked failed") { it.connection is Connection.Failed }
        assertTrue(vm.state.value.cameras.isEmpty())

        startServer(serverPort)
        var virtualSeconds = 0
        while (vm.state.value.connection !is Connection.Connected && virtualSeconds < 120) {
            advanceVirtual(1_000)
            virtualSeconds++
        }

        awaitConnected("Home")
    }

    @Test
    fun losing_the_network_goes_offline_and_regaining_it_reconnects() {
        launch(home, active = HOME_ID)
        awaitConnected("Home")
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val callback = shadowOf(connectivity).networkCallbacks.single()
        val network = Network::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(100)

        shadowOf(connectivity).setDefaultNetworkActive(false)
        callback.onLost(network)
        awaitState("offline after losing the network") {
            it.connection == Connection.Failed("Network unavailable")
        }
        assertTrue(vm.state.value.cameras.isEmpty())

        val epoch = vm.state.value.networkEpoch
        shadowOf(connectivity).setDefaultNetworkActive(true)
        callback.onAvailable(network)
        awaitConnected("Home")
        assertEquals(epoch + 1, vm.state.value.networkEpoch)
    }

    private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val HOME_ID = "home-test"
        const val QA_ID = "qa-test-server"
    }
}
