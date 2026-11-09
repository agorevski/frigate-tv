package com.frigatetv.app.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Focus must rest on a camera this long before it becomes the selected camera. */
const val SELECT_SETTLE_MS = 800L

/**
 * Turns focus movement into a single "settled" event. Each [focus] restarts the timer, so scanning
 * across the rail never starts main-stream decoders; only the camera where focus comes to rest does.
 *
 * The timer runs on [scope], so tests can drive it with virtual time.
 */
class FocusSettle(
    private val scope: CoroutineScope,
    private val settleMs: Long = SELECT_SETTLE_MS,
) {
    private var pending: Job? = null

    fun focus(id: String, onSettled: (String) -> Unit) {
        pending?.cancel()
        pending = scope.launch {
            delay(settleMs)
            onSettled(id)
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
