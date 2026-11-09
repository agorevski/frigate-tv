package com.frigatetv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPoolTest {

    @Test
    fun refusesTheSeventhDecoderAtTheDefaultCap() {
        val pool = PlayerPool()
        (1..6).forEach { assertTrue(pool.acquire("tile:$it")) }

        assertFalse(pool.acquire("tile:7"))
        assertEquals(6, pool.inUse)
    }

    @Test
    fun releasedSlotCanBeTakenByAnotherOwner() {
        val pool = PlayerPool(capacity = 2)
        pool.acquire("featured")
        pool.acquire("tile:a")
        assertFalse(pool.acquire("fullscreen"))

        pool.release("featured")

        assertTrue(pool.acquire("fullscreen"))
        assertFalse(pool.holds("featured"))
        assertEquals(2, pool.inUse)
    }

    @Test
    fun reacquiringAHeldSlotDoesNotUseAnotherOne() {
        val pool = PlayerPool(capacity = 1)
        pool.acquire("clip")

        assertTrue(pool.acquire("clip"))
        assertEquals(1, pool.inUse)
    }
}

class StreamBudgetTest {

    @Test
    fun railKeepsTheFirstFiveCamerasInDisplayOrder() {
        val rail = listOf("front", "back", "garage", "porch", "drive", "side", "yard")

        assertEquals(setOf("front", "back", "garage", "porch", "drive"), StreamBudget.railWithinBudget(rail))
    }

    @Test
    fun smallRailFitsCompletely() {
        assertEquals(setOf("front", "back"), StreamBudget.railWithinBudget(listOf("front", "back")))
    }
}
