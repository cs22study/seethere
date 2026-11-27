package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoSpatialEngineTest {

    @Test
    fun `haversine distance around NTU matches expected real-world range`() {
        // NTU main campus (25.0173, 121.5397) to BOT079 Fuxing-Xinhai (25.021568, 121.543298)
        val dist = GeoSpatialEngine.haversineMeters(25.0173, 121.5397, 25.021568, 121.543298)
        assertTrue(dist in 550.0..650.0, "Actual dist=$dist")
    }

    @Test
    fun `findNearby returns NTU cameras sorted nearest-first and excludes deleted and distant cams`() {
        val cameras = CuratedDemoCatalog.demoCameras + listOf(
            CuratedDemoCatalog.demoCameras.first().copy(
                id = "BOT_DELETED",
                title = "已刪除測試鏡頭",
                latitude = 25.0174,
                longitude = 121.5398,
                isDeleted = true,
                deletedAtIso = "2026-11-27T09:00:00Z",
            )
        )

        val nearbyNtu = GeoSpatialEngine.findNearby(
            cameras = cameras,
            centerLat = 25.0173,
            centerLon = 121.5397,
            radiusMeters = 2_000.0,
        )

        assertEquals(5, nearbyNtu.size)
        assertTrue(nearbyNtu.none { it.camera.id == "BOT_DELETED" })
        assertTrue(nearbyNtu.none { it.camera.id == "taoyuanairport" })
        assertTrue(nearbyNtu.none { it.camera.id == "tokyo_kabukicho" })

        // BOT169 (169-基隆路3段156巷) is closest to NTU (25.0173, 121.5397)
        assertEquals("BOT169", nearbyNtu.first().camera.id)
        for (i in 0 until nearbyNtu.size - 1) {
            assertTrue(nearbyNtu[i].distanceMeters <= nearbyNtu[i + 1].distanceMeters)
        }
    }

    @Test
    fun `findNearestBackup finds closest alternative camera when BOT079 goes offline`() {
        val bot079 = CuratedDemoCatalog.demoCameras.first { it.id == "BOT079" }
        val backup = GeoSpatialEngine.findNearestBackup(CuratedDemoCatalog.demoCameras, bot079)
        assertNotNull(backup)
        assertEquals("BOT322", backup!!.camera.id)
        assertTrue(backup.distanceMeters in 350.0..410.0)
    }

    @Test
    fun `formatDistance switches cleanly between meters and kilometers`() {
        assertEquals("376 公尺", GeoSpatialEngine.formatDistance(376.2))
        assertEquals("1.5 公里", GeoSpatialEngine.formatDistance(1520.0))
    }

    @Test
    fun `findAlongCorridor returns cameras ordered from start to end`() {
        val corridor = GeoSpatialEngine.findAlongCorridor(
            cameras = CuratedDemoCatalog.demoCameras,
            startLat = 25.0173,
            startLon = 121.5397, // NTU
            endLat = 25.07706,
            endLon = 121.21743, // Taoyuan Airport
            corridorWidthMeters = 3_000.0,
        )
        assertTrue(corridor.isNotEmpty())
        assertEquals("BOT169", corridor.first().camera.id)
        assertEquals("taoyuanairport", corridor.last().camera.id)
        for (i in 0 until corridor.size - 1) {
            assertTrue(corridor[i].progressFraction <= corridor[i + 1].progressFraction)
        }
    }
}
