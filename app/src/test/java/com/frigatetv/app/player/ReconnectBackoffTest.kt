package com.frigatetv.app.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {

    @Test
    fun doublesFromTwoSecondsThenCapsAtThirtySeconds() {
        val backoff = ReconnectBackoff()
        val delays = mutableListOf<Long>()
        repeat(6) {
            delays += backoff.delayMs
            backoff.recordFailure()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), delays)
    }

    @Test
    fun healthyPlaybackRestartsTheSequence() {
        val backoff = ReconnectBackoff()
        repeat(3) { backoff.recordFailure() }
        assertEquals(16_000L, backoff.delayMs)

        backoff.onHealthy()

        assertEquals(0, backoff.failures)
        assertEquals(2_000L, backoff.delayMs)
    }
}
