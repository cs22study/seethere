package com.cs22study.seethere.core

/**
 * Circular camera switcher for single-view swipe/arrows, 2x2 quad-wall paging, and scenic carousel.
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

    /**
     * Orders active cameras with favorites first so 2x2 quad paging shows favorites on page 0.
     */
    fun orderedQuadPool(cameras: List<CameraItem>): List<CameraItem> {
        val active = cameras.filter { !it.isDeleted }
        val favs = active.filter { it.isFavorite }
        val others = active.filterNot { it.isFavorite }
        return favs + others
    }

    fun totalQuadPages(cameras: List<CameraItem>, pageSize: Int = 4): Int {
        val pool = orderedQuadPool(cameras)
        if (pool.isEmpty() || pageSize <= 0) return 1
        return (pool.size + pageSize - 1) / pageSize
    }

    fun getQuadPage(
        cameras: List<CameraItem>,
        pageIndex: Int,
        pageSize: Int = 4,
    ): Pair<Int, List<CameraItem>> {
        val pool = orderedQuadPool(cameras)
        if (pool.isEmpty() || pageSize <= 0) return 0 to emptyList()
        val totalPages = (pool.size + pageSize - 1) / pageSize
        val safePage = ((pageIndex % totalPages) + totalPages) % totalPages
        val from = safePage * pageSize
        val to = minOf(from + pageSize, pool.size)
        return safePage to pool.subList(from, to)
    }

    fun isPrivateOrLoopbackIp(ip: String?): Boolean {
        if (ip.isNullOrBlank()) return false
        val clean = ip.trim().removePrefix("::ffff:")
        if (clean == "127.0.0.1" || clean == "localhost" || clean == "::1" || clean == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (clean.startsWith("192.168.") || clean.startsWith("10.")) return true
        if (clean.startsWith("172.")) {
            val second = clean.split('.').getOrNull(1)?.toIntOrNull() ?: 0
            if (second in 16..31) return true
        }
        return false
    }

    fun isValidServerPort(port: Int): Boolean = port in 1024..65535
}
