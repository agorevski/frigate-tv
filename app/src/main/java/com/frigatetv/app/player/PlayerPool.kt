package com.frigatetv.app.player

import androidx.compose.runtime.compositionLocalOf

/**
 * Decides which camera tiles get a live decoder. The featured camera always takes one slot, so the
 * rail gets the rest, in display order.
 */
object StreamBudget {
    const val MAX_LIVE_STREAMS = 6

    /** Rail tiles with a decoder: the cap minus the featured camera's slot. */
    const val RAIL_SLOTS = MAX_LIVE_STREAMS - 1

    fun railWithinBudget(railOrder: List<String>, slots: Int = RAIL_SLOTS): Set<String> =
        railOrder.take(slots).toSet()
}

/**
 * Hard cap on decoders alive at once. Owners are opaque keys, one per player. [acquire] refuses
 * when the pool is full, so a new decoder is never created on top of [capacity] live ones.
 *
 * The app provides one pool for the whole process (see [LocalPlayerPool]) because the hardware
 * decoder pool is device-wide, not per screen. Only the main thread uses it.
 */
class PlayerPool(val capacity: Int = StreamBudget.MAX_LIVE_STREAMS) {
    private val owners = LinkedHashSet<String>()

    val inUse: Int get() = owners.size

    /** True when [owner] holds a slot afterwards. Acquiring a slot already held succeeds without taking another. */
    fun acquire(owner: String): Boolean {
        if (owner in owners) return true
        if (owners.size >= capacity) return false
        owners += owner
        return true
    }

    fun release(owner: String) {
        owners -= owner
    }

    fun holds(owner: String): Boolean = owner in owners
}

/** The pool shared by every player in the app. Null means uncapped (previews and tests). */
val LocalPlayerPool = compositionLocalOf<PlayerPool?> { null }
