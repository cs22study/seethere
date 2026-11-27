package com.cs22study.seethere.core

/**
 * Supported camera transport / presentation protocols.
 */
enum class StreamProtocol {
    HLS,
    MJPEG,
    SNAPSHOT,
    YOUTUBE,
    WRA_API,
    BLOCKED,
}

/**
 * Core domain model for a live camera feed, including soft-delete fields for recoverability.
 */
data class CameraItem(
    val id: String,
    val title: String,
    val region: String,
    val city: String,
    val category: String,
    val streamUrl: String,
    val thumbnailUrl: String = "",
    val latitude: Double,
    val longitude: Double,
    val sourceName: String = "",
    val isFavorite: Boolean = false,
    val sortOrder: Int = 0,
    val isDeleted: Boolean = false,
    val deletedAtIso: String? = null,
    val allowLocalHttp: Boolean = false,
)

/**
 * Geographic anchor bookmark (essential for Android TV / Google TV boxes without GPS hardware).
 */
data class LocationAnchor(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val isDefault: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedAtIso: String? = null,
)

/**
 * Result of a nearby spatial query with exact spherical distance in meters.
 */
data class NearbyCameraResult(
    val camera: CameraItem,
    val distanceMeters: Double,
)

/**
 * Result of a start-to-end route corridor search, ordered by progression along the route.
 */
data class CorridorCameraResult(
    val camera: CameraItem,
    val distanceToRouteMeters: Double,
    val progressFraction: Double,
)

/**
 * Output of URL security inspection and normalization.
 */
data class SanitizedUrlResult(
    val isAllowed: Boolean,
    val normalizedUrl: String,
    val protocol: StreamProtocol,
    val extractedYouTubeId: String? = null,
    val blockReason: String? = null,
)

/**
 * Result of batch CSV/JSON camera import with rejected entries itemized for auditability.
 */
data class ImportRejection(
    val lineNumber: Int,
    val rawTitle: String,
    val reason: String,
)

data class CameraImportReport(
    val accepted: List<CameraItem>,
    val rejected: List<ImportRejection>,
)
