package com.frigatetv.app.ui.recordings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.frigatetv.app.data.FrigateApi
import com.frigatetv.app.data.FrigateRepository
import com.frigatetv.app.data.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

/** Result of loading one day. Loading is shown for every change of camera or day. */
sealed interface RecordingsLoad {
    data object Loading : RecordingsLoad
    data class Loaded(val model: DayModel) : RecordingsLoad
    data class Failed(val message: String?) : RecordingsLoad
}

/**
 * Recordings tab state: the profile, the selected camera and day, and the day's timeline model.
 * [generation] is bumped on each entry to the tab so the day is always fetched fresh.
 */
data class RecordingsUiState(
    val profile: ServerProfile? = null,
    val generation: Int = 0,
    val cameraId: String? = null,
    /** Days before [today]: 0 is today. */
    val dayOffset: Int = 0,
    val today: LocalDate = LocalDate.now(),
    val load: RecordingsLoad = RecordingsLoad.Loading,
)

/** The inputs that decide what is loaded. Anything else in [RecordingsUiState] is presentation only. */
private data class Query(val profile: ServerProfile, val generation: Int, val cameraId: String, val dayOffset: Int)

/**
 * The camera to show: [current] if it is still enabled, else [preferred] (the camera last watched
 * live), else the first camera.
 */
internal fun resolveCameraId(current: String?, preferred: String?, ids: List<String>): String? =
    listOf(current, preferred).firstOrNull { it != null && it in ids } ?: ids.firstOrNull()

class RecordingsViewModel(private val api: FrigateApi = FrigateApi()) : ViewModel() {

    private val _state = MutableStateFlow(RecordingsUiState())
    val state: StateFlow<RecordingsUiState> = _state.asStateFlow()

    private var repo: FrigateRepository? = null
    private var loadedGeneration = -1
    private val zone = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            _state
                .map { s ->
                    val profile = s.profile
                    val cameraId = s.cameraId
                    if (profile == null || cameraId == null) {
                        null
                    } else {
                        Query(profile, s.generation, cameraId, s.dayOffset)
                    }
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collectLatest { query -> load(query) }
        }
    }

    /** Called when the tab appears or the camera list changes. Refreshes the day and keeps a valid camera selection. */
    fun onEnter(profile: ServerProfile, cameraIds: List<String>, preferredCameraId: String?) {
        _state.update { s ->
            s.copy(
                profile = profile,
                generation = s.generation + 1,
                cameraId = resolveCameraId(s.cameraId, preferredCameraId, cameraIds),
                today = LocalDate.now(zone),
            )
        }
    }

    fun selectCamera(cameraId: String) {
        if (_state.value.cameraId != cameraId) _state.update { it.copy(cameraId = cameraId) }
    }

    fun selectDay(dayOffset: Int) {
        if (_state.value.dayOffset != dayOffset) _state.update { it.copy(dayOffset = dayOffset) }
    }

    private suspend fun load(query: Query) {
        val window = dayWindow(_state.value.today, query.dayOffset, zone)
        // A new generation means the tab was entered again: fetch fresh instead of reusing cached segments.
        val refresh = query.generation != loadedGeneration
        loadedGeneration = query.generation
        _state.update { it.copy(load = RecordingsLoad.Loading) }
        val repository = repositoryFor(query.profile)
        val load = runCatching {
            val raw = DayData(
                segments = repository.recordings(query.cameraId, window.start, window.end, refresh = refresh),
                events = repository.events(limit = 300, after = window.start, camera = query.cameraId),
            )
            // Building the timeline sorts and merges the day's ranges; keep it off the main thread.
            RecordingsLoad.Loaded(withContext(Dispatchers.Default) { buildDayModel(raw, window.start, window.end) })
        }.getOrElse { e ->
            // A superseded load must not overwrite the newer result, so cancellation is never recorded.
            if (e is CancellationException) throw e
            RecordingsLoad.Failed(e.message)
        }
        _state.update { it.copy(load = load) }
    }

    private fun repositoryFor(profile: ServerProfile): FrigateRepository =
        repo?.takeIf { it.profile == profile } ?: FrigateRepository(profile, api).also { repo = it }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { RecordingsViewModel(FrigateApi()) }
        }
    }
}
