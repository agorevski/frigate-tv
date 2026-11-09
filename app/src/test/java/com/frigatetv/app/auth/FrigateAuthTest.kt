package com.frigatetv.app.auth

import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateException
import com.frigatetv.app.data.Scheme
import com.frigatetv.app.data.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FrigateAuthTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun profile(password: String = "secret") = ServerProfile(
        name = "test",
        host = server.hostName,
        port = server.port,
        scheme = Scheme.HTTP,
        authEnabled = true,
        username = "admin",
        password = password,
    )

    private fun loginResponse(token: String) = MockResponse()
        .setHeader("Set-Cookie", "$TOKEN_COOKIE=$token; Path=/; HttpOnly; SameSite=Strict")
        .setBody("{}")

    private fun versionResponse(version: String) = MockResponse().setBody(version)

    private fun RecordedRequest.cookieToken(): String? =
        getHeader("Cookie")?.substringAfter("$TOKEN_COOKIE=", "")?.takeIf { it.isNotEmpty() }

    @Test
    fun loginStoresTokenAndLaterRequestsCarryIt() = runBlocking {
        server.enqueue(loginResponse("token-1"))
        server.enqueue(versionResponse("0.16.2"))
        val p = profile()
        val clients = ProfileClients(p)

        clients.login()
        val version = FrigateApi { clients.authed }.version(p)

        assertEquals("0.16.2", version)
        val login = server.takeRequest()
        assertEquals("/api/login", login.path)
        assertEquals("""{"user":"admin","password":"secret"}""", login.body.readUtf8())
        assertEquals("token-1", server.takeRequest().cookieToken())
    }

    @Test
    fun expiredTokenTriggersOneReloginAndRetry() = runBlocking {
        server.enqueue(loginResponse("token-1"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(loginResponse("token-2"))
        server.enqueue(versionResponse("0.16.2"))
        val p = profile()
        val clients = ProfileClients(p)
        clients.login()

        val version = FrigateApi { clients.authed }.version(p)

        assertEquals("0.16.2", version)
        assertEquals(4, server.requestCount)
        server.takeRequest() // initial login
        assertEquals("token-1", server.takeRequest().cookieToken())
        assertEquals("/api/login", server.takeRequest().path)
        assertEquals("token-2", server.takeRequest().cookieToken())
    }

    @Test
    fun wrongPasswordReportsInvalidCredentials() {
        server.enqueue(MockResponse().setResponseCode(401))

        try {
            ProfileClients(profile(password = "wrong")).login()
            fail("expected login to fail")
        } catch (e: FrigateException) {
            assertEquals("Invalid username or password", e.message)
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun persistent401StopsAfterOneReloginWithoutLooping() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path == "/api/login") loginResponse("token-n") else MockResponse().setResponseCode(401)
        }
        val p = profile()
        val clients = ProfileClients(p)
        clients.login()

        try {
            FrigateApi { clients.authed }.version(p)
            fail("expected a 401 failure")
        } catch (e: FrigateException) {
            assertEquals(401, e.statusCode)
        }

        // One explicit login, one re-login after the first 401, then the second 401 is returned as-is.
        val paths = List(server.requestCount) { server.takeRequest().path }
        assertEquals(2, paths.count { it == "/api/login" })
        assertEquals(2, paths.count { it == "/api/version" })
    }

    @Test
    fun profileWithoutLoginSendsNoCookie() = runBlocking {
        server.enqueue(versionResponse("0.16.2"))
        val p = profile().copy(authEnabled = false)
        val clients = ProfileClients(p)

        FrigateApi { clients.authed }.version(p)

        assertNull(server.takeRequest().getHeader("Cookie"))
    }
}
