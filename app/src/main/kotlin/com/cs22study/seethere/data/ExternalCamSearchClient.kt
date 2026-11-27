package com.cs22study.seethere.data

import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.ExternalSearchParsers
import com.cs22study.seethere.core.GeoSpatialEngine
import com.cs22study.seethere.core.StreamSecuritySanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ExternalSearchCandidate(
    val id: String,
    val title: String,
    val sourceBadge: String,
    val subtitle: String,
    val thumbnailUrl: String,
    val twLiveDeviceId: String? = null,
    val directStreamUrl: String? = null,
    val latitude: Double = 25.0173,
    val longitude: Double = 121.5397,
)

/**
 * Zero-cost live camera discovery client supporting:
 * 1) tw.live nearby spatial query + cam stream extraction (via Nominatim geocoding for keywords)
 * 2) YouTube Live keyword search (adapted from androidtv-news-fork620 YouTubeSearcher)
 */
object ExternalCamSearchClient {

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun geocodePlace(keyword: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val q = keyword.trim()
        if (q.isBlank()) return@withContext null
        runCatching {
            val encoded = URLEncoder.encode(q, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?q=$encoded&format=json&limit=1")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                setRequestProperty("User-Agent", "SeeThereApp/0.3 (https://github.com/cs22study/seethere)")
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body)
            if (arr.length() > 0) {
                val obj = arr.getJSONObject(0)
                val lat = obj.getString("lat").toDouble()
                val lon = obj.getString("lon").toDouble()
                if (GeoSpatialEngine.isValidCoordinate(lat, lon)) lat to lon else null
            } else {
                null
            }
        }.getOrNull()
    }

    suspend fun searchTwLiveNearby(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        keywordFilter: String = "",
    ): List<ExternalSearchCandidate> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://tw.live/nearby/?ajax=1&lat=$lat&lon=$lon&radius=$radiusMeters")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = ExternalSearchParsers.parseTwLiveNearbyHtml(html)
            val kw = keywordFilter.trim()
            val filtered = if (kw.isBlank()) {
                parsed
            } else {
                val matching = parsed.filter { it.title.contains(kw, ignoreCase = true) }
                matching.ifEmpty { parsed }
            }
            filtered.take(20).map { entry ->
                ExternalSearchCandidate(
                    id = "twlive_${entry.deviceId}",
                    title = entry.title,
                    sourceBadge = "tw.live",
                    subtitle = "tw.live · 距離約 ${GeoSpatialEngine.formatDistance(entry.distanceMeters)}",
                    thumbnailUrl = entry.thumbnailUrl,
                    twLiveDeviceId = entry.deviceId,
                    latitude = lat,
                    longitude = lon,
                )
            }
        }.getOrDefault(emptyList())
    }

    suspend fun resolveTwLiveCamera(
        deviceId: String,
        fallbackLat: Double = 25.0173,
        fallbackLon: Double = 121.5397,
        allowPrivateLanHttp: Boolean = false,
    ): CameraItem? = withContext(Dispatchers.IO) {
        runCatching {
            val safeId = URLEncoder.encode(deviceId.trim(), "UTF-8")
            val url = URL("https://tw.live/cam/?id=$safeId")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", BROWSER_UA)
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val resolved = ExternalSearchParsers.extractStreamFromTwLiveCamHtml(
                deviceId = deviceId,
                html = html,
                defaultLat = fallbackLat,
                defaultLon = fallbackLon,
                allowPrivateLanHttp = allowPrivateLanHttp,
            ) ?: return@withContext null

            CameraItem(
                id = "twlive_${resolved.deviceId}",
                title = resolved.title,
                region = "tw.live",
                city = "tw.live 搜尋",
                category = "公開即時影像",
                streamUrl = resolved.streamUrl,
                thumbnailUrl = resolved.thumbnailUrl,
                latitude = resolved.latitude,
                longitude = resolved.longitude,
                sourceName = "tw.live",
                isFavorite = false,
            )
        }.getOrNull()
    }

    suspend fun searchYouTubeLive(
        keyword: String,
        anchorLat: Double = 25.0173,
        anchorLon: Double = 121.5397,
    ): List<ExternalSearchCandidate> = withContext(Dispatchers.IO) {
        val q = keyword.trim().ifBlank { "台灣 即時影像" }
        runCatching {
            val encoded = URLEncoder.encode("$q live", "UTF-8")
            // sp=EgJAAQ%3D%3D filters YouTube search results to Live broadcasts
            val url = URL("https://www.youtube.com/results?search_query=$encoded&sp=EgJAAQ%3D%3D")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", BROWSER_UA)
                setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8")
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            ExternalSearchParsers.parseYouTubeSearchFromHtml(html, maxResults = 10).map { entry ->
                ExternalSearchCandidate(
                    id = "yt_${entry.videoId}",
                    title = entry.title,
                    sourceBadge = "YouTube",
                    subtitle = "YouTube 直播 · ${StreamSecuritySanitizer.sanitizeLabel(entry.channelName, 40)}",
                    thumbnailUrl = entry.thumbnailUrl,
                    directStreamUrl = entry.embedUrl,
                    latitude = anchorLat,
                    longitude = anchorLon,
                )
            }
        }.getOrDefault(emptyList())
    }
}
