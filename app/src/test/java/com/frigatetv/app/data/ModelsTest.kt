package com.frigatetv.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {

    private fun camera(id: String) = CameraInfo(id, true, null, null, null, null)

    @Test
    fun `scheme wire values map back to schemes and anything else falls back to http`() {
        assertEquals(Scheme.HTTPS, Scheme.fromWire("https"))
        assertEquals(Scheme.HTTP, Scheme.fromWire("http"))
        assertEquals(Scheme.HTTP, Scheme.fromWire(null))
        assertEquals(Scheme.HTTP, Scheme.fromWire(""))
        assertEquals(Scheme.HTTP, Scheme.fromWire("ftp"))
    }

    @Test
    fun `listed cameras come first in saved order and unlisted ones keep discovery order`() {
        val discovered = listOf("a", "b", "c", "d").map(::camera)

        val ordered = orderedCameras(discovered, listOf("c", "missing", "a"))

        assertEquals(listOf("c", "a", "b", "d"), ordered.map { it.id })
    }

    @Test
    fun `display name title-cases underscore separated parts and drops empty ones`() {
        assertEquals("Reolink Camera", displayName("reolink_camera"))
        assertEquals("Front Door", displayName("__front__door_"))
        assertEquals("Garage", displayName("garage"))
        assertEquals("", displayName(""))
    }
}
