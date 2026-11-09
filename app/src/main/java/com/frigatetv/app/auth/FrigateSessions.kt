package com.frigatetv.app.auth

import com.frigatetv.app.data.FrigateHttp
import com.frigatetv.app.data.ServerProfile
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * The authenticated client for each saved profile, keyed by profile id. A client is rebuilt when the
 * profile's connection settings change, which also drops its cached token. Renaming a profile keeps it.
 *
 * Coil and Media3 do not know the profile, so [callFactory] picks the client for the server the
 * request URL points at. Servers with no registered profile use the plain shared client.
 */
object FrigateSessions {
    private val sessions = LinkedHashMap<String, ProfileClients>()

    /** The authenticated client for [profile]. Registers the profile so that image and video requests find it. */
    @Synchronized
    fun clientFor(profile: ServerProfile): OkHttpClient {
        val current = sessions[profile.id]
        if (current != null && sameConnection(current.profile, profile)) return current.authed
        return ProfileClients(profile).also { sessions[profile.id] = it }.authed
    }

    /** Call factory for Coil and Media3: authenticated when the URL is on a registered server. */
    val callFactory: Call.Factory = Call.Factory { request ->
        clientForUrl(request.url).newCall(request)
    }

    @Synchronized
    private fun clientForUrl(url: HttpUrl): OkHttpClient =
        // Last registered wins if two profiles point at the same server.
        sessions.values.lastOrNull { session ->
            session.profile.baseUrl.toHttpUrlOrNull()?.let { sameServer(it, url) } == true
        }?.authed ?: FrigateHttp.client

    private fun sameServer(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun sameConnection(a: ServerProfile, b: ServerProfile): Boolean = a.copy(name = "") == b.copy(name = "")
}
