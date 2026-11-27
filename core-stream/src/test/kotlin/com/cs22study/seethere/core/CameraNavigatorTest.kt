package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraNavigatorTest {

    @Test
    fun `steps forward and wraps around backward across favorite cameras`() {
        val favs = CuratedDemoCatalog.demoCameras.filter { it.isFavorite }
        assertEquals(3, favs.size)

        val second = CameraNavigator.stepCamera(favs, favs[0].id, +1)
        assertEquals(favs[1].id, second?.id)

        val wrappedBack = CameraNavigator.stepCamera(favs, favs[0].id, -1)
        assertEquals(favs[2].id, wrappedBack?.id)

        val wrappedForward = CameraNavigator.stepCamera(favs, favs[2].id, +1)
        assertEquals(favs[0].id, wrappedForward?.id)
    }

    @Test
    fun `quad paging cycles cleanly across 4-camera groups`() {
        val all = CuratedDemoCatalog.demoCameras // 8 items -> 2 pages of 4
        assertEquals(2, CameraNavigator.totalQuadPages(all))

        val (p0, list0) = CameraNavigator.getQuadPage(all, 0)
        assertEquals(0, p0)
        assertEquals(4, list0.size)

        val (p1, list1) = CameraNavigator.getQuadPage(all, 1)
        assertEquals(1, p1)
        assertEquals(4, list1.size)
        assertTrue(list0.map { it.id }.intersect(list1.map { it.id }.toSet()).isEmpty())

        // Wraps around cleanly on -1 and +2
        assertEquals(1, CameraNavigator.getQuadPage(all, -1).first)
        assertEquals(0, CameraNavigator.getQuadPage(all, 2).first)
    }

    @Test
    fun `LAN server IP allowlist permits RFC1918 and blocks public WAN IPs`() {
        assertTrue(CameraNavigator.isPrivateOrLoopbackIp("127.0.0.1"))
        assertTrue(CameraNavigator.isPrivateOrLoopbackIp("192.168.1.55"))
        assertTrue(CameraNavigator.isPrivateOrLoopbackIp("10.0.0.12"))
        assertTrue(CameraNavigator.isPrivateOrLoopbackIp("172.16.5.2"))
        assertFalse(CameraNavigator.isPrivateOrLoopbackIp("8.8.8.8"))
        assertFalse(CameraNavigator.isPrivateOrLoopbackIp("172.32.0.1"))
        assertTrue(CameraNavigator.isValidServerPort(8080))
        assertFalse(CameraNavigator.isValidServerPort(80))
    }
}
