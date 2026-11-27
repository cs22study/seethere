package com.cs22study.seethere.core

/**
 * Pure Kotlin parsers for external live-camera discovery sources (tw.live & YouTube live search)
 * with strict StreamSecuritySanitizer enforcement on every extracted URL and label.
 */
object ExternalSearchParsers {

    data class TwLiveNearbyEntry(
        val deviceId: String,
        val title: String,
        val distanceMeters: Double,
        val thumbnailUrl: String,
    )

    data class TwLiveResolvedCam(
        val deviceId: String,
        val title: String,
        val streamUrl: String,
        val thumbnailUrl: String,
        val latitude: Double,
        val longitude: Double,
        val sourceName: String,
    )

    data class YouTubeSearchEntry(
        val videoId: String,
        val title: String,
        val channelName: String,
        val thumbnailUrl: String,
        val embedUrl: String,
    )

    private val TW_NEARBY_ANCHOR_RE = Regex(
        "<a\\b[^>]*class=['\"][^'\"]*nearby-link[^'\"]*['\"][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val ATTR_DEVICE_ID_RE = Regex("data-deviceid=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
    private val ATTR_DISTANCE_RE = Regex("data-distance=['\"]([0-9.]+)['\"]", RegexOption.IGNORE_CASE)
    private val ATTR_ALT_RE = Regex("alt=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
    private val ATTR_SRC_RE = Regex("<img\\b[^>]*\\bsrc=['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)

    private val TW_CONTENT_URL_RE = Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val TW_STREAM_VAR_RE = Regex("var\\s+streamUrl\\s*=\\s*\"([^\"]+)\"")
    private val TW_YT_EMBED_RE = Regex("https://www\\.youtube(?:-nocookie)?\\.com/embed/([A-Za-z0-9_-]{11})")
    private val TW_TITLE_RE = Regex("<title>([^<]+)</title>", RegexOption.IGNORE_CASE)
    private val TW_THUMB_RE = Regex("\"thumbnailUrl\"\\s*:\\s*\"([^\"]+)\"")
    private val TW_LAT_LNG_RE = Regex("lat=([0-9.-]+)&(?:amp;)?lng=([0-9.-]+)")
    private val TW_ID_URL_RE = Regex("tw\\.live/cam/?\\?id=([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE)

    private val YT_VIDEO_RENDERER_RE = Regex(
        "\"videoRenderer\"\\s*:\\s*\\{\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\".*?\"title\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val YT_OWNER_RE = Regex("\"ownerText\"\\s*:\\s*\\{\"runs\"\\s*:\\s*\\[\\{\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun extractTwLiveDeviceIdFromUrl(rawUrl: String): String? {
        return TW_ID_URL_RE.find(rawUrl.trim())?.groupValues?.get(1)
    }

    fun parseTwLiveNearbyHtml(html: String): List<TwLiveNearbyEntry> {
        if (html.isBlank()) return emptyList()
        val results = ArrayList<TwLiveNearbyEntry>()
        val seenIds = HashSet<String>()

        for (match in TW_NEARBY_ANCHOR_RE.findAll(html)) {
            val fullTag = match.value
            val deviceId = ATTR_DEVICE_ID_RE.find(fullTag)?.groupValues?.get(1)?.trim() ?: continue
            if (!seenIds.add(deviceId)) continue

            val dist = ATTR_DISTANCE_RE.find(fullTag)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            val rawTitle = ATTR_ALT_RE.find(fullTag)?.groupValues?.get(1) ?: deviceId
            val title = StreamSecuritySanitizer.sanitizeLabel(rawTitle).ifBlank { deviceId }
            val rawThumb = ATTR_SRC_RE.find(fullTag)?.groupValues?.get(1)?.substringBefore("?").orEmpty()
            val safeThumb = StreamSecuritySanitizer.sanitizeStreamUrl(rawThumb).let {
                if (it.isAllowed) it.normalizedUrl else ""
            }
            results.add(
                TwLiveNearbyEntry(
                    deviceId = deviceId,
                    title = title,
                    distanceMeters = dist,
                    thumbnailUrl = safeThumb,
                )
            )
        }
        return results
    }

    fun extractStreamFromTwLiveCamHtml(
        deviceId: String,
        html: String,
        defaultLat: Double = 25.0173,
        defaultLon: Double = 121.5397,
        allowPrivateLanHttp: Boolean = false,
    ): TwLiveResolvedCam? {
        if (html.isBlank()) return null

        val rawTitle = TW_TITLE_RE.find(html)?.groupValues?.get(1)
            ?.substringBefore(" - 即時影像監視器")
            ?: deviceId
        val title = StreamSecuritySanitizer.sanitizeLabel(rawTitle).ifBlank { deviceId }

        val candidateUrl = TW_CONTENT_URL_RE.find(html)?.groupValues?.get(1)
            ?: TW_STREAM_VAR_RE.find(html)?.groupValues?.get(1)
            ?: TW_YT_EMBED_RE.find(html)?.let { "https://www.youtube.com/watch?v=${it.groupValues[1]}" }
            ?: return null

        val sanitized = StreamSecuritySanitizer.sanitizeStreamUrl(candidateUrl, allowPrivateLanHttp)
        if (!sanitized.isAllowed) return null

        val rawThumb = TW_THUMB_RE.find(html)?.groupValues?.get(1).orEmpty()
        val safeThumb = if (sanitized.extractedYouTubeId != null) {
            "https://i.ytimg.com/vi/${sanitized.extractedYouTubeId}/hqdefault.jpg"
        } else {
            StreamSecuritySanitizer.sanitizeStreamUrl(rawThumb, allowPrivateLanHttp).let {
                if (it.isAllowed) it.normalizedUrl else ""
            }
        }

        val latLngMatch = TW_LAT_LNG_RE.find(html)
        val lat = latLngMatch?.groupValues?.get(1)?.toDoubleOrNull()
            ?.takeIf { GeoSpatialEngine.isValidCoordinate(it, 121.0) } ?: defaultLat
        val lon = latLngMatch?.groupValues?.get(2)?.toDoubleOrNull()
            ?.takeIf { GeoSpatialEngine.isValidCoordinate(25.0, it) } ?: defaultLon

        return TwLiveResolvedCam(
            deviceId = deviceId,
            title = title,
            streamUrl = sanitized.normalizedUrl,
            thumbnailUrl = safeThumb,
            latitude = lat,
            longitude = lon,
            sourceName = "tw.live",
        )
    }

    fun extractYtInitialDataJson(html: String): String? {
        val markers = listOf("var ytInitialData = ", "ytInitialData = ")
        for (marker in markers) {
            val idx = html.indexOf(marker)
            if (idx != -1) {
                val start = idx + marker.length
                val end = html.indexOf(";</script>", start)
                if (end > start) {
                    return html.substring(start, end)
                }
            }
        }
        return null
    }

    fun parseYouTubeSearchFromHtml(html: String, maxResults: Int = 8): List<YouTubeSearchEntry> {
        val json = extractYtInitialDataJson(html) ?: html
        val entries = ArrayList<YouTubeSearchEntry>()
        val seenIds = HashSet<String>()

        for (match in YT_VIDEO_RENDERER_RE.findAll(json)) {
            val videoId = match.groupValues[1]
            if (!seenIds.add(videoId)) continue

            val rawTitle = unescapeJsonString(match.groupValues[2])
            val safeTitle = StreamSecuritySanitizer.sanitizeLabel(rawTitle)
            if (safeTitle.isBlank()) continue

            val tailWindow = json.substring(match.range.first, minOf(json.length, match.range.last + 1200))
            val ownerRaw = YT_OWNER_RE.find(tailWindow)?.groupValues?.get(1).orEmpty()
            val owner = StreamSecuritySanitizer.sanitizeLabel(unescapeJsonString(ownerRaw)).ifBlank { "YouTube Live" }

            val sanitized = StreamSecuritySanitizer.sanitizeStreamUrl("https://www.youtube.com/watch?v=$videoId")
            if (!sanitized.isAllowed) continue

            entries.add(
                YouTubeSearchEntry(
                    videoId = videoId,
                    title = safeTitle,
                    channelName = owner,
                    thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    embedUrl = sanitized.normalizedUrl,
                )
            )
            if (entries.size >= maxResults) break
        }
        return entries
    }

    private fun unescapeJsonString(raw: String): String {
        return raw.replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\\", "\\")
    }
}
