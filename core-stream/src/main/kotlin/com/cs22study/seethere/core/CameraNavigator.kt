package com.cs22study.seethere.core

/**
 * Circular camera switcher for swipe gestures, arrow controls, and scenic carousel.
 */
object CameraNavigator {

    fun stepCamera(
        cameras: List<CameraItem>,
        currentCameraId: String?,
        delta: Int,
    ): CameraItem? {
        val active = cameras.filter { !it.isDeleted }
        if (active.isEmpty()) return null
        val currentIdx = active.indexOfFirst { it.id == currentCameraId }
        val baseIdx = if (currentIdx >= 0) currentIdx else 0
        val nextIdx = ((baseIdx + delta) % active.size + active.size) % active.size
        return active[nextIdx]
    }
}
