package com.frigatetv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "frigatetv_settings")

private val PROFILES_KEY = stringPreferencesKey("profiles_json")
private val ACTIVE_KEY = stringPreferencesKey("active_profile_id")
private fun prefsKey(profileId: String) = stringPreferencesKey("prefs_$profileId")
private const val CREDENTIAL_PREFIX = "cred_"
private fun credentialKey(profileId: String) = stringPreferencesKey("$CREDENTIAL_PREFIX$profileId")

/*
 * On-disk shapes. Field names are the ones written by the earlier org.json code, so data saved by
 * older builds still reads, and older builds still read what is written now.
 */

@Serializable
internal data class StoredProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val scheme: String? = null,
    val rtspPort: Int = 8554,
    val authEnabled: Boolean = false,
    val username: String? = null,
    val trustSelfSigned: Boolean = false,
    val pinnedCertSha256: String? = null,
)

@Serializable
internal data class StoredPrefs(
    val order: List<String> = emptyList(),
    val favorites: List<String> = emptyList(),
    val columns: Int = 0,
    val lastCamera: String? = null,
    val muted: Boolean = false,
)

private val profilesSerializer = ListSerializer(StoredProfile.serializer())

/** Persists server profiles, the active profile id, and per-profile preferences in DataStore. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    suspend fun readProfiles(): List<ServerProfile> {
        val prefs = store.data.first()
        val raw = prefs[PROFILES_KEY] ?: return emptyList()
        return frigateJson.decodeFromString(profilesSerializer, raw).map {
            ServerProfile(
                id = it.id,
                name = it.name,
                host = it.host,
                port = it.port,
                scheme = Scheme.fromWire(it.scheme),
                rtspPort = it.rtspPort,
                authEnabled = it.authEnabled,
                username = it.username,
                // A password that can no longer be decrypted (for example after the Keystore was
                // reset) reads as missing; the user is asked to enter it again.
                password = prefs[credentialKey(it.id)]?.let { enc ->
                    runCatching { SecretBox.decrypt(enc) }.getOrNull()
                },
                trustSelfSigned = it.trustSelfSigned,
                pinnedCertSha256 = it.pinnedCertSha256,
            )
        }
    }

    suspend fun readActiveId(): String? = store.data.first()[ACTIVE_KEY]

    /**
     * Writes the profile list. Passwords are encrypted into their own entries; entries for profiles
     * that are no longer in [profiles] are removed.
     */
    suspend fun writeProfiles(profiles: List<ServerProfile>, activeId: String) {
        val encoded = frigateJson.encodeToString(profilesSerializer, profiles.map { it.toStored() })
        store.edit { prefs ->
            prefs[PROFILES_KEY] = encoded
            prefs[ACTIVE_KEY] = activeId
            prefs.asMap().keys
                .filter { it.name.startsWith(CREDENTIAL_PREFIX) }
                .forEach { prefs.remove(it) }
            profiles.forEach { p ->
                val password = p.password
                if (p.authEnabled && !password.isNullOrEmpty()) {
                    prefs[credentialKey(p.id)] = SecretBox.encrypt(password)
                }
            }
        }
    }

    suspend fun readPrefs(profileId: String): ProfilePrefs {
        val raw = store.data.first()[prefsKey(profileId)] ?: return ProfilePrefs()
        return runCatching { frigateJson.decodeFromString<StoredPrefs>(raw).toDomain() }
            .getOrDefault(ProfilePrefs())
    }

    suspend fun writePrefs(profileId: String, prefs: ProfilePrefs) {
        store.edit { it[prefsKey(profileId)] = frigateJson.encodeToString(prefs.toStored()) }
    }

    suspend fun deletePrefs(profileId: String) {
        store.edit { it.remove(prefsKey(profileId)) }
    }

    private fun ServerProfile.toStored() = StoredProfile(
        id = id,
        name = name,
        host = host,
        port = port,
        scheme = scheme.wire,
        rtspPort = rtspPort,
        authEnabled = authEnabled,
        username = username,
        trustSelfSigned = trustSelfSigned,
        pinnedCertSha256 = pinnedCertSha256,
    )

    private fun ProfilePrefs.toStored() = StoredPrefs(
        order = cameraOrder,
        favorites = favorites.toList(),
        columns = columns,
        lastCamera = lastCamera,
        muted = muted,
    )

    private fun StoredPrefs.toDomain() = ProfilePrefs(
        cameraOrder = order,
        favorites = favorites.toSet(),
        columns = columns,
        lastCamera = lastCamera?.takeIf { it.isNotEmpty() },
        muted = muted,
    )
}
