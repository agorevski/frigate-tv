package com.frigatetv.app

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.frigatetv.app.data.CameraInfo
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateException
import com.frigatetv.app.data.ProfilePrefs
import com.frigatetv.app.data.Scheme
import com.frigatetv.app.data.ServerProfile
import com.frigatetv.app.data.SettingsStore
import com.frigatetv.app.data.orderedCameras
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import kotlinx.coroutines.flow.update

private const val DETECT_POLL_MS = 4_000L
/** Server health check runs every this many detection polls (about every 32 s). */
private const val HEALTH_EVERY_POLLS = 8
private const val MAX_RETRY_DELAY_MS = 60_000L
private const val BASE_RETRY_DELAY_MS = 5_000L

sealed interface Connection {
    data object Idle : Connection
    data object Connecting : Connection
    data class Connected(val version: String) : Connection
    data class Failed(val reason: String) : Connection
}

data class AppState(
    val profiles: List<ServerProfile> = emptyList(),
    val active: ServerProfile? = null,
    val connection: Connection = Connection.Idle,
    val cameras: List<CameraInfo> = emptyList(),
    val prefs: ProfilePrefs = ProfilePrefs(),
    /** Camera ids with an event in progress right now. */
    val detecting: Set<String> = emptySet(),
    /** Bumped whenever the network becomes available; tiles use it to retry immediately. */
    val networkEpoch: Int = 0,
    val foreground: Boolean = true,
    val notice: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    val api = FrigateApi()

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private var connectJob: Job? = null
    private var detectJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempts = 0

    private val connectivity = application.getSystemService(ConnectivityManager::class.java)
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            viewModelScope.launch { onNetworkAvailable() }
        }

        override fun onLost(network: Network) {
            viewModelScope.launch { onNetworkLost() }
        }
    }

    /** True when stored profiles could not be read; nothing is written until the next launch so stored data is kept. */
    private var profilesReadFailed = false

    init {
        connectivity?.registerDefaultNetworkCallback(networkCallback)
        viewModelScope.launch { loadProfiles() }
    }

    override fun onCleared() {
        connectivity?.unregisterNetworkCallback(networkCallback)
        super.onCleared()
    }

    private suspend fun loadProfiles() {
        val (stored, storedActive) = runCatching { store.readProfiles() to store.readActiveId() }.getOrElse {
            profilesReadFailed = true
            val fallback = defaultProfile()
            _state.update {
                it.copy(
                    profiles = listOf(fallback),
                    notice = "Could not read saved server profiles. Using the default server for this session; changes will not be saved.",
                )
            }
            connectNow(fallback)
            return
        }
        val profiles = stored.ifEmpty { listOf(defaultProfile()) }
        val active = profiles.firstOrNull { it.id == storedActive } ?: profiles.first()
        persistProfiles(profiles, active.id).onFailure { showNotice("Could not save server profiles: ${it.message}") }
        _state.update { it.copy(profiles = profiles) }
        connectNow(active)
    }

    /** Writes the profile list; refuses while stored profiles are unreadable so existing data is never overwritten. */
    private suspend fun persistProfiles(profiles: List<ServerProfile>, activeId: String): Result<Unit> {
        if (profilesReadFailed) {
            return Result.failure(IllegalStateException("saved profiles could not be read, so changes are not stored"))
        }
        return runCatching { store.writeProfiles(profiles, activeId) }
    }

    private fun defaultProfile() = ServerProfile(
        name = "Home",
        host = BuildConfig.FRIGATE_HOST,
        port = 9150,
        scheme = Scheme.HTTP,
    )

    /** User-initiated reconnect; cancels any scheduled automatic retry. */
    fun reconnect() {
        val active = _state.value.active ?: return
        retryJob?.cancel()
        retryAttempts = 0
        connectNow(active)
    }

    private fun connectNow(profile: ServerProfile) {
        connectJob?.cancel()
        stopHealthLoop()
        _state.update {
            it.copy(
                active = profile,
                connection = Connection.Connecting,
                cameras = emptyList(),
                detecting = emptySet(),
                prefs = ProfilePrefs(),
            )
        }
        connectJob = viewModelScope.launch {
            val prefs = runCatching { store.readPrefs(profile.id) }.getOrDefault(ProfilePrefs())
            _state.update { it.copy(prefs = prefs) }
            try {
                val version = api.version(profile)
                val cams = api.cameras(profile)
                retryAttempts = 0
                _state.update { it.copy(connection = Connection.Connected(version), cameras = cams) }
                startHealthLoop(profile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onConnectionLost(profile, e)
            }
        }
    }

    private fun onConnectionLost(profile: ServerProfile, e: Exception) {
        val reason = (e as? FrigateException)?.message ?: "Unexpected error (${e.javaClass.simpleName})"
        stopHealthLoop()
        _state.update { it.copy(connection = Connection.Failed(reason), cameras = emptyList(), detecting = emptySet()) }
        scheduleRetry(profile)
    }

    /** Retries are deferred while backgrounded; setForeground(true) reconnects. */
    private fun scheduleRetry(profile: ServerProfile) {
        retryJob?.cancel()
        if (!_state.value.foreground) return
        val delayMs = minOf(MAX_RETRY_DELAY_MS, BASE_RETRY_DELAY_MS shl minOf(retryAttempts, 4))
        retryAttempts++
        retryJob = viewModelScope.launch {
            delay(delayMs)
            connectNow(profile)
        }
    }

    private fun stopHealthLoop() {
        detectJob?.cancel()
        detectJob = null
    }

    /** Detection polling for the grid badges, plus a periodic server health check. Does nothing while backgrounded. */
    private fun startHealthLoop(profile: ServerProfile) {
        stopHealthLoop()
        if (!_state.value.foreground) return
        detectJob = viewModelScope.launch {
            var polls = 0
            while (true) {
                delay(DETECT_POLL_MS)
                polls++
                runCatching { api.events(profile, limit = 50, inProgress = true) }
                    .onSuccess { events ->
                        _state.update { it.copy(detecting = events.map { e -> e.camera }.toSet()) }
                    }
                if (polls % HEALTH_EVERY_POLLS == 0) {
                    try {
                        api.version(profile)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onConnectionLost(profile, e)
                        return@launch
                    }
                }
            }
        }
    }

    private fun onNetworkAvailable() {
        _state.update { it.copy(networkEpoch = it.networkEpoch + 1) }
        if (_state.value.foreground && _state.value.connection is Connection.Failed) reconnect()
    }

    /**
     * The default network went away. With no network left, mark the server offline and stop polling.
     * If another network has taken over (e.g. Wi-Fi to cellular), reconnect over it instead.
     */
    private fun onNetworkLost() {
        val s = _state.value
        if (s.active == null) return
        if (connectivity?.activeNetwork != null) {
            if (s.foreground) reconnect()
            return
        }
        connectJob?.cancel()
        retryJob?.cancel()
        stopHealthLoop()
        _state.update {
            it.copy(connection = Connection.Failed("Network unavailable"), cameras = emptyList(), detecting = emptySet())
        }
    }

    fun setForeground(foreground: Boolean) {
        _state.update { it.copy(foreground = foreground) }
        if (!foreground) {
            retryJob?.cancel()
            stopHealthLoop()
            _state.update { it.copy(detecting = emptySet()) }
            return
        }
        val s = _state.value
        when {
            s.connection is Connection.Failed -> reconnect()
            s.connection is Connection.Connected -> s.active?.let { startHealthLoop(it) }
        }
    }

    /**
     * Tests [profile] and returns a summary such as "0.16.2 · 5 cameras".
     * Used by the settings form before saving.
     */
    suspend fun testProfile(profile: ServerProfile): Result<String> = runCatching {
        val version = api.version(profile)
        val cams = api.cameras(profile)
        "Frigate $version · ${cams.size} cameras"
    }

    /**
     * Saves [edited]. If it will become (or already is) the active profile, it must pass a
     * connection test first. On failure nothing is saved and the current profile stays active;
     * the returned failure carries the reason for the form to show.
     */
    suspend fun saveProfile(edited: ServerProfile, makeActive: Boolean): Result<Unit> {
        val current = _state.value
        val affectsActive = makeActive || edited.id == current.active?.id
        if (affectsActive) {
            testProfile(edited).onFailure {
                val message = "Could not connect to ${edited.name}: ${it.message}. Keeping the current profile."
                showNotice(message)
                return Result.failure(IllegalStateException(message))
            }
        }
        val existing = current.profiles.any { it.id == edited.id }
        val updated = if (existing) {
            current.profiles.map { if (it.id == edited.id) edited else it }
        } else {
            current.profiles + edited
        }
        val activeId = if (makeActive) edited.id else current.active?.id ?: edited.id
        persistProfiles(updated, activeId).onFailure {
            val message = "Could not save ${edited.name}: ${it.message}"
            showNotice(message)
            return Result.failure(IllegalStateException(message))
        }
        _state.update { it.copy(profiles = updated) }
        if (affectsActive) {
            retryJob?.cancel()
            retryAttempts = 0
            connectNow(edited)
            showNotice("Connected to ${edited.name}.")
        } else {
            showNotice("Saved ${edited.name}.")
        }
        return Result.success(Unit)
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val current = _state.value
            if (current.profiles.size <= 1) {
                showNotice("Keep at least one server profile.")
                return@launch
            }
            val remaining = current.profiles.filterNot { it.id == id }
            val wasActive = current.active?.id == id
            val newActive = if (wasActive) remaining.first() else current.active ?: remaining.first()
            persistProfiles(remaining, newActive.id).onFailure {
                showNotice("Could not delete the profile: ${it.message}")
                return@launch
            }
            runCatching { store.deletePrefs(id) }
            _state.update { it.copy(profiles = remaining) }
            if (wasActive) {
                retryJob?.cancel()
                retryAttempts = 0
                connectNow(newActive)
            }
        }
    }

    fun toggleMute() = updatePrefs { it.copy(muted = !it.muted) }

    fun selectCamera(id: String) = updatePrefs { if (it.lastCamera == id) it else it.copy(lastCamera = id) }

    fun toggleFavorite(id: String) = updatePrefs {
        it.copy(favorites = if (id in it.favorites) it.favorites - id else it.favorites + id)
    }

    fun setColumns(columns: Int) = updatePrefs { it.copy(columns = columns) }

    fun moveCamera(id: String, delta: Int) {
        val s = _state.value
        val ids = orderedCameras(s.cameras, s.prefs.cameraOrder).map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return
        Collections.swap(ids, from, to)
        updatePrefs { it.copy(cameraOrder = ids) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun showNotice(message: String) = _state.update { it.copy(notice = message) }

    private fun updatePrefs(transform: (ProfilePrefs) -> ProfilePrefs) {
        val s = _state.value
        val profile = s.active ?: return
        val newPrefs = transform(s.prefs)
        if (newPrefs == s.prefs) return
        _state.update { it.copy(prefs = newPrefs) }
        viewModelScope.launch {
            runCatching { store.writePrefs(profile.id, newPrefs) }
                .onFailure { showNotice("Could not save settings: ${it.message}") }
        }
    }
}
