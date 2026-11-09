package com.frigatetv.app.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusSettleTest {

    @Test
    fun selectsOnlyAfterFocusHasRestedForTheFullDelay() = runTest {
        val settled = mutableListOf<String>()
        val settle = FocusSettle(this, settleMs = 800L)

        settle.focus("front") { settled += it }
        advanceTimeBy(799L)
        runCurrent()
        assertEquals(emptyList<String>(), settled)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("front"), settled)
    }

    @Test
    fun scanningAcrossCamerasSelectsOnlyTheCameraWhereFocusComesToRest() = runTest {
        val settled = mutableListOf<String>()
        val settle = FocusSettle(this, settleMs = 800L)

        settle.focus("front") { settled += it }
        advanceTimeBy(500L)
        settle.focus("back") { settled += it }
        advanceTimeBy(500L)
        settle.focus("garage") { settled += it }
        advanceTimeBy(800L)
        runCurrent()

        assertEquals(listOf("garage"), settled)
    }

    @Test
    fun cancelDropsAPendingSelection() = runTest {
        val settled = mutableListOf<String>()
        val settle = FocusSettle(this, settleMs = 800L)

        settle.focus("front") { settled += it }
        settle.cancel()
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(emptyList<String>(), settled)
    }
}
