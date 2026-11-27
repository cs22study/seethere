package com.cs22study.seethere.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Two-stage geospatial search engine:
 * 1) O(1) latitude/longitude bounding-box pre-filter
 * 2) Exact spherical Haversine distance sorting
 */
object GeoSpatialEngine {

    const val EARTH_RADIUS_METERS = 6_371_000.0
    private const val RAD_PER_DEG = PI / 180.0
    private const val DEG_PER_RAD = 180.0 / PI

    fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        return !lat.isNaN() && !lon.isNaN() &&
            !lat.isInfinite() && !lon.isInfinite() &&
            lat in -90.0..90.0 && lon in -180.0..180.0
    }

    /**
     * Computes great-circle distance in meters using the Haversine formula.
     */
    fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        if (!isValidCoordinate(lat1, lon1) || !isValidCoordinate(lat2, lon2)) {
            return Double.POSITIVE_INFINITY
        }
        val phi1 = lat1 * RAD_PER_DEG
        val phi2 = lat2 * RAD_PER_DEG
        val dPhi = (lat2 - lat1) * RAD_PER_DEG
        val dLambda = (lon2 - lon1) * RAD_PER_DEG

        val sinHalfPhi = sin(dPhi / 2.0)
        val sinHalfLambda = sin(dLambda / 2.0)
        val a = sinHalfPhi.pow(2) + cos(phi1) * cos(phi2) * sinHalfLambda.pow(2)
        val c = 2.0 * asin(min(1.0, sqrt(max(0.0, a))))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Finds non-deleted cameras within [radiusMeters] around ([centerLat], [centerLon]), sorted nearest-first.
     */
    fun findNearby(
        cameras: Iterable<CameraItem>,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Double = 3_000.0,
        limit: Int = 50,
    ): List<NearbyCameraResult> {
        if (!isValidCoordinate(centerLat, centerLon) || radiusMeters <= 0.0 || limit <= 0) {
            return emptyList()
        }

        val deltaLatDeg = (radiusMeters / EARTH_RADIUS_METERS) * DEG_PER_RAD
        val cosLat = max(1e-6, cos(centerLat * RAD_PER_DEG))
        val deltaLonDeg = ((radiusMeters / (EARTH_RADIUS_METERS * cosLat)) * DEG_PER_RAD)

        val minLat = centerLat - deltaLatDeg
        val maxLat = centerLat + deltaLatDeg
        val minLon = centerLon - deltaLonDeg
        val maxLon = centerLon + deltaLonDeg

        val matches = ArrayList<NearbyCameraResult>()
        for (cam in cameras) {
            if (cam.isDeleted) continue
            if (!isValidCoordinate(cam.latitude, cam.longitude)) continue

            // Stage 1: Bounding-box pre-filter (handle antimeridian wrap conservatively)
            if (cam.latitude < minLat || cam.latitude > maxLat) continue
            if (minLon >= -180.0 && maxLon <= 180.0) {
                if (cam.longitude < minLon || cam.longitude > maxLon) continue
            }

            // Stage 2: Exact Haversine distance
            val dist = haversineMeters(centerLat, centerLon, cam.latitude, cam.longitude)
            if (dist <= radiusMeters) {
                matches.add(NearbyCameraResult(camera = cam, distanceMeters = dist))
            }
        }

        matches.sortWith(compareBy<NearbyCameraResult> { it.distanceMeters }.thenBy { it.camera.id })
        return if (matches.size > limit) matches.subList(0, limit) else matches
    }

    /**
     * Finds the closest alternative active camera when the current feed goes offline.
     */
    fun findNearestBackup(
        cameras: Iterable<CameraItem>,
        failedCamera: CameraItem,
        maxRadiusMeters: Double = 10_000.0,
    ): NearbyCameraResult? {
        return findNearby(
            cameras = cameras.filter { it.id != failedCamera.id },
            centerLat = failedCamera.latitude,
            centerLon = failedCamera.longitude,
            radiusMeters = maxRadiusMeters,
            limit = 1,
        ).firstOrNull()
    }

    /**
     * Searches active cameras within [corridorWidthMeters] along the route from Start to End,
     * ordered by progression from Start (0.0) to End (1.0).
     */
    fun findAlongCorridor(
        cameras: Iterable<CameraItem>,
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        corridorWidthMeters: Double = 3_000.0,
        limit: Int = 50,
    ): List<CorridorCameraResult> {
        if (!isValidCoordinate(startLat, startLon) ||
            !isValidCoordinate(endLat, endLon) ||
            corridorWidthMeters <= 0.0 ||
            limit <= 0
        ) {
            return emptyList()
        }

        val totalDist = haversineMeters(startLat, startLon, endLat, endLon)
        val steps = kotlin.math.ceil(totalDist / 2_000.0).toInt().coerceIn(2, 40)
        val samples = ArrayList<Triple<Double, Double, Double>>(steps + 1)
        for (i in 0..steps) {
            val t = i.toDouble() / steps.toDouble()
            samples.add(
                Triple(
                    startLat + (endLat - startLat) * t,
                    startLon + (endLon - startLon) * t,
                    t,
                )
            )
        }

        val minLat = min(startLat, endLat) - (corridorWidthMeters / EARTH_RADIUS_METERS) * DEG_PER_RAD
        val maxLat = max(startLat, endLat) + (corridorWidthMeters / EARTH_RADIUS_METERS) * DEG_PER_RAD
        val midCos = max(1e-6, cos(((startLat + endLat) * 0.5) * RAD_PER_DEG))
        val dLon = (corridorWidthMeters / (EARTH_RADIUS_METERS * midCos)) * DEG_PER_RAD
        val minLon = min(startLon, endLon) - dLon
        val maxLon = max(startLon, endLon) + dLon

        val results = ArrayList<CorridorCameraResult>()
        for (cam in cameras) {
            if (cam.isDeleted || !isValidCoordinate(cam.latitude, cam.longitude)) continue
            if (cam.latitude < minLat || cam.latitude > maxLat) continue
            if (minLon >= -180.0 && maxLon <= 180.0) {
                if (cam.longitude < minLon || cam.longitude > maxLon) continue
            }

            var bestDist = Double.POSITIVE_INFINITY
            var bestT = 0.0
            for ((sLat, sLon, t) in samples) {
                val d = haversineMeters(cam.latitude, cam.longitude, sLat, sLon)
                if (d < bestDist) {
                    bestDist = d
                    bestT = t
                }
            }
            if (bestDist <= corridorWidthMeters) {
                results.add(CorridorCameraResult(cam, bestDist, bestT))
            }
        }

        results.sortWith(compareBy<CorridorCameraResult> { it.progressFraction }.thenBy { it.distanceToRouteMeters })
        return if (results.size > limit) results.subList(0, limit) else results
    }

    fun formatDistance(distanceMeters: Double): String {
        if (!distanceMeters.isFinite() || distanceMeters < 0) return "--"
        return if (distanceMeters < 1000.0) {
            "${distanceMeters.roundToInt()} 公尺"
        } else {
            val km = (distanceMeters / 100.0).roundToInt() / 10.0
            "$km 公里"
        }
    }
}
