package com.frigatetv.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "frigatetv_settings")

private val PROFILES_KEY = stringPreferencesKey("profiles_json")
private val ACTIVE_KEY = stringPreferencesKey("active_profile_id")
private fun prefsKey(profileId: String) = stringPreferencesKey("prefs_$profileId")

/** Persists server profiles, the active profile id, and per-profile preferences in DataStore. */
class SettingsStore(context: Context) {
    private val store = context.applicationContext.settingsStore

    suspend fun readProfiles(): List<ServerProfile> {
        val raw = store.data.first()[PROFILES_KEY] ?: return emptyList()
        val arr = JSONArray(raw)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            ServerProfile(
                id = o.getString("id"),
                name = o.getString("name"),
                host = o.getString("host"),
                port = o.getInt("port"),
                scheme = Scheme.fromWire(o.optString("scheme")),
                rtspPort = o.optInt("rtspPort", 8554),
            )
        }
    }

    suspend fun readActiveId(): String? = store.data.first()[ACTIVE_KEY]

    suspend fun writeProfiles(profiles: List<ServerProfile>, activeId: String) {
        store.edit { prefs ->
            prefs[PROFILES_KEY] = JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()
            prefs[ACTIVE_KEY] = activeId
        }
    }

    suspend fun readPrefs(profileId: String): ProfilePrefs {
        val raw = store.data.first()[prefsKey(profileId)] ?: return ProfilePrefs()
        return runCatching { parsePrefs(JSONObject(raw)) }.getOrDefault(ProfilePrefs())
    }

    suspend fun writePrefs(profileId: String, prefs: ProfilePrefs) {
        store.edit { it[prefsKey(profileId)] = prefs.toJson().toString() }
    }

    suspend fun deletePrefs(profileId: String) {
        store.edit { it.remove(prefsKey(profileId)) }
    }

    private fun ServerProfile.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("scheme", scheme.wire)
        .put("rtspPort", rtspPort)

    private fun ProfilePrefs.toJson(): JSONObject = JSONObject()
        .put("order", JSONArray(cameraOrder))
        .put("favorites", JSONArray(favorites.toList()))
        .put("columns", columns)
        .put("lastCamera", lastCamera ?: JSONObject.NULL)
        .put("muted", muted)

    private fun parsePrefs(o: JSONObject): ProfilePrefs = ProfilePrefs(
        cameraOrder = o.optJSONArray("order")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
        favorites = o.optJSONArray("favorites")?.let { a -> List(a.length()) { a.getString(it) }.toSet() } ?: emptySet(),
        columns = o.optInt("columns", 0),
        lastCamera = if (o.isNull("lastCamera")) null else o.optString("lastCamera").takeIf { it.isNotEmpty() },
        muted = o.optBoolean("muted", false),
    )
}
