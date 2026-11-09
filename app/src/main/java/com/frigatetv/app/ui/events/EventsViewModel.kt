package com.frigatetv.app.ui.events

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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Result of the latest event query. The list stays on screen while a new filter loads. */
sealed interface EventsLoad {
    data object Loading : EventsLoad
    data class Loaded(val events: List<EventUi>) : EventsLoad
    data class Failed(val message: String?) : EventsLoad
}

/**
 * Event list state. [rows] and [cards] are derived from [load] and [label] so the screen only lays them out.
 * [cards] is the filtered event list in grid order; the detail view pages through it.
 */
data class EventsUiState(
    val label: LabelFilter = LabelFilter.ALL,
    val range: TimeRange = TimeRange.DAY,
    /** Null means every enabled camera. */
    val cameraId: String? = null,
    val load: EventsLoad = EventsLoad.Loading,
    val rows: List<GridRow> = emptyList(),
    val cards: List<EventUi> = emptyList(),
)

/** The profile being shown, plus a counter bumped on each entry so the list always refreshes when the tab opens. */
private data class Session(val profile: ServerProfile, val entry: Int)

class EventsViewModel(private val api: FrigateApi = FrigateApi()) : ViewModel() {

    private val _state = MutableStateFlow(EventsUiState())
    val state: StateFlow<EventsUiState> = _state.asStateFlow()

    private val session = MutableStateFlow<Session?>(null)
    private var repo: FrigateRepository? = null
    private val zone = ZoneId.systemDefault()

    init {
        // Any change to the session or the filters cancels the query in flight and starts a new one.
        viewModelScope.launch {
            combine(
                session.filterNotNull(),
                _state.map { it.range to it.cameraId }.distinctUntilChanged(),
            ) { s, filters -> s.profile to filters }
                .collectLatest { (profile, filters) -> load(profile, filters.first, filters.second) }
        }
    }

    /**
     * Called when the Events tab appears or the active profile changes. Shows loading,
     * then reloads from the server.
     */
    fun bind(profile: ServerProfile) {
        _state.update { withRows(it.copy(load = EventsLoad.Loading)) }
        session.update { Session(profile, (it?.entry ?: 0) + 1) }
    }

    fun setLabel(label: LabelFilter) = mutate { it.copy(label = label) }

    fun setRange(range: TimeRange) = mutate { it.copy(range = range) }

    fun setCamera(cameraId: String?) = mutate { it.copy(cameraId = cameraId) }

    private fun mutate(transform: (EventsUiState) -> EventsUiState) {
        _state.update { withRows(transform(it)) }
    }

    private fun withRows(s: EventsUiState): EventsUiState {
        val events = (s.load as? EventsLoad.Loaded)?.events
        val rows = events?.let { buildRows(it, s.label) }.orEmpty()
        return s.copy(rows = rows, cards = rows.mapNotNull { (it as? CardRow)?.ui })
    }

    private suspend fun load(profile: ServerProfile, range: TimeRange, cameraId: String?) {
        val repository = repositoryFor(profile)
        val now = System.currentTimeMillis() / 1000.0
        val load = runCatching {
            val events = repository.events(limit = 200, after = now - range.seconds, camera = cameraId)
            // Formatting dates for hundreds of events is CPU work; keep it off the main thread.
            val ui = withContext(Dispatchers.Default) { events.map { toUi(it, now, zone) } }
            EventsLoad.Loaded(ui)
        }.getOrElse { e ->
            // A superseded load must not overwrite the newer result, so cancellation is never recorded.
            if (e is CancellationException) throw e
            EventsLoad.Failed(e.message)
        }
        mutate { it.copy(load = load) }
    }

    private fun repositoryFor(profile: ServerProfile): FrigateRepository =
        repo?.takeIf { it.profile == profile } ?: FrigateRepository(profile, api).also { repo = it }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { EventsViewModel(FrigateApi()) }
        }
    }
}
