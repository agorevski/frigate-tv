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
        val raw = store.data.first()[PROFILES_KEY] ?: return emptyList()
        return frigateJson.decodeFromString(profilesSerializer, raw).map {
            ServerProfile(
                id = it.id,
                name = it.name,
                host = it.host,
                port = it.port,
                scheme = Scheme.fromWire(it.scheme),
                rtspPort = it.rtspPort,
            )
        }
    }

    suspend fun readActiveId(): String? = store.data.first()[ACTIVE_KEY]

    suspend fun writeProfiles(profiles: List<ServerProfile>, activeId: String) {
        val encoded = frigateJson.encodeToString(profilesSerializer, profiles.map { it.toStored() })
        store.edit { prefs ->
            prefs[PROFILES_KEY] = encoded
            prefs[ACTIVE_KEY] = activeId
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
