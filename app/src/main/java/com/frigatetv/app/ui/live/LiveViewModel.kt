package com.frigatetv.app.ui.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.frigatetv.app.player.FocusSettle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Focus and selection on the live grid. [focusedId] follows the D-pad (or touch) immediately;
 * [selectedId] is the camera shown in the featured view and only follows once focus has settled.
 * Both are null until the grid first picks a camera, and the grid falls back to the saved camera.
 */
data class LiveUiState(
    val focusedId: String? = null,
    val selectedId: String? = null,
)

/**
 * Holds grid focus across tab switches, so returning to Live keeps the camera the user was on.
 * Stream playback itself lives in the composables (see [com.frigatetv.app.player.StreamPlayer]).
 */
class LiveViewModel : ViewModel() {
    private val _state = MutableStateFlow(LiveUiState())
    val state: StateFlow<LiveUiState> = _state.asStateFlow()

    private val settle = FocusSettle(viewModelScope)

    fun onFocused(id: String) {
        _state.update { it.copy(focusedId = id) }
    }

    /** Selects [id] after focus has rested on it, then reports it through [persist] (the saved last camera). */
    fun scheduleSelection(id: String, persist: (String) -> Unit) {
        settle.focus(id) { settled ->
            _state.update { it.copy(selectedId = settled) }
            persist(settled)
        }
    }

    /** Fullscreen switches (LEFT/RIGHT, tapping a tile) select at once; the saved camera follows when focus settles. */
    fun switchTo(id: String) {
        _state.update { it.copy(focusedId = id, selectedId = id) }
    }
}
