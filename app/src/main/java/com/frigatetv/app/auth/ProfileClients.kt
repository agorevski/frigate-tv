package com.frigatetv.app.auth

import com.frigatetv.app.data.FrigateException
import com.frigatetv.app.data.FrigateHttp
import com.frigatetv.app.data.Scheme
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.frigateJson
import com.frigatetv.app.data.networkFailure
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

internal const val TOKEN_COOKIE = "frigate_token"
internal const val HTTP_UNAUTHORIZED = 401

@Serializable
private class LoginBody(val user: String, val password: String)

/**
 * HTTP clients for one server profile, derived from [FrigateHttp.client] with `newBuilder()` so they
 * share its connection pool.
 *
 * - [plain] has the profile's TLS settings and no login. It is used for `/api/login`.
 * - [authed] adds the `frigate_token` cookie to each request. A 401 triggers one re-login with the
 *   stored credentials and one retry. A second 401 is returned to the caller, so there is no loop.
 *
 * The token lives in memory only. It is never written to storage or logged.
 */
internal class ProfileClients(
    val profile: ServerProfile,
    base: OkHttpClient = FrigateHttp.client,
) {
    private val selfSigned: SelfSignedTrust? =
        if (profile.scheme == Scheme.HTTPS && profile.trustSelfSigned) {
            SelfSignedTrust(profile.pinnedCertSha256)
        } else {
            null
        }

    val plain: OkHttpClient = base.newBuilder().apply {
        selfSigned?.let { trust ->
            sslSocketFactory(trust.socketFactory(), trust)
            // The pinned certificate fingerprint is the identity check; self-signed certificates
            // rarely carry the LAN address as a name.
            hostnameVerifier(HostnameVerifier { _, _ -> true })
        }
    }.build()

    @Volatile
    private var token: String? = null

    val authed: OkHttpClient = if (profile.authEnabled) {
        plain.newBuilder()
            .addInterceptor(TokenInterceptor())
            .authenticator(ReloginAuthenticator())
            .build()
    } else {
        plain
    }

    /** SHA-256 of the certificate the server presented, when the profile trusts a self-signed certificate. */
    val observedFingerprint: String? get() = selfSigned?.observed

    /**
     * Logs in with the profile's credentials and keeps the token for [authed]. Blocking; call off the
     * main thread. Throws [FrigateException] with "Invalid username or password" on HTTP 401.
     */
    fun login(): String {
        val user = profile.username?.trim().orEmpty()
        val password = profile.password.orEmpty()
        if (user.isEmpty() || password.isEmpty()) {
            throw FrigateException("Enter the username and password for this server")
        }
        val body = frigateJson.encodeToString(LoginBody(user, password))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${profile.baseUrl}/api/login").post(body).build()
        val newToken = try {
            plain.newCall(request).execute().use { response -> tokenOf(request, response) }
        } catch (e: IOException) {
            throw networkFailure(e)
        }
        token = newToken
        return newToken
    }

    private fun tokenOf(request: Request, response: Response): String {
        if (!response.isSuccessful) throw loginFailure(response)
        return tokenFrom(request, response)
            ?: throw FrigateException("Login response did not include a session token")
    }

    private fun loginFailure(response: Response): FrigateException =
        if (response.code == HTTP_UNAUTHORIZED) {
            FrigateException("Invalid username or password", HTTP_UNAUTHORIZED)
        } else {
            FrigateException("Login failed: server returned HTTP ${response.code}", response.code)
        }

    private fun tokenFrom(request: Request, response: Response): String? =
        response.headers("Set-Cookie")
            .asSequence()
            .mapNotNull { Cookie.parse(request.url, it) }
            .firstOrNull { it.name == TOKEN_COOKIE && it.value.isNotEmpty() }
            ?.value

    private inner class TokenInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val current = token ?: return chain.proceed(chain.request())
            return chain.proceed(chain.request().withToken(current))
        }
    }

    private inner class ReloginAuthenticator : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            // The retry already carried a freshly obtained token and was rejected too: stop here.
            if (response.priorResponse != null) return null
            val sentToken = response.request.header("Cookie")?.substringAfter("$TOKEN_COOKIE=")
            return synchronized(this@ProfileClients) {
                val latest = token
                when {
                    // Another request already logged in again while this one was in flight.
                    latest != null && latest != sentToken -> response.request.withToken(latest)
                    // A failed re-login gives null, which hands the original 401 to the caller.
                    else -> runCatching { login() }.getOrNull()?.let { response.request.withToken(it) }
                }
            }
        }
    }

    private fun Request.withToken(value: String): Request =
        newBuilder().header("Cookie", "$TOKEN_COOKIE=$value").build()
}

/**
 * Trust for a single self-signed server certificate. Without a pin it accepts the first certificate
 * and records its fingerprint (trust on first use). With a pin it accepts only a certificate whose
 * SHA-256 matches. Each instance is attached only to one profile's client, so no other server is
 * affected and TLS verification is not disabled globally.
 */
internal class SelfSignedTrust(private val pinned: String?) : X509TrustManager {
    @Volatile
    var observed: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("Server sent no certificate")
        val fingerprint = sha256Colon(leaf.encoded)
        observed = fingerprint
        if (pinned != null && !pinned.equals(fingerprint, ignoreCase = true)) {
            throw CertificateException(
                "certificate does not match the pinned fingerprint; turn 'Trust self-signed' off and on to re-pin",
            )
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) {
        throw CertificateException("client certificates are not used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    fun socketFactory(): SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(this@SelfSignedTrust), null) }.socketFactory
}

/** "AB:CD:…" uppercase colon-separated SHA-256 of [der]. */
internal fun sha256Colon(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }
