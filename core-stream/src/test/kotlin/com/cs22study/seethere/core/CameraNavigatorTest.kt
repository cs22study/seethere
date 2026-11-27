package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
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
}
