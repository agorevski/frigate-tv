package com.frigatetv.app.player

/**
 * Reconnect backoff for a failing stream: 2 s, 4 s, 8 s, 16 s, then 30 s.
 *
 * Callers wait for [delayMs], then call [recordFailure]. A stream that is playing again calls
 * [onHealthy], which restarts the sequence from 2 s.
 */
class ReconnectBackoff(
    private val baseMs: Long = 2_000L,
    private val maxMs: Long = 30_000L,
) {
    var failures = 0
        private set

    val delayMs: Long
        get() = minOf(maxMs, baseMs shl minOf(failures, 4))

    fun recordFailure() {
        failures++
    }

    fun onHealthy() {
        failures = 0
    }
}
