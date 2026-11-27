package com.cs22study.seethere.data

import com.cs22study.seethere.core.WraDisasterParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches and caches the WRA (水利署) disaster CCTV catalog for 10 minutes
 * so daily encrypted CCTV_SN image URLs are resolved fresh without hammering upstream APIs.
 */
object WraDisasterClient {

    private const val CACHE_TTL_MS = 10 * 60_000L

    @Volatile
    private var cachedAtMs: Long = 0L

    @Volatile
    private var cachedJson: String? = null

    suspend fun resolveLiveImageUrl(streamUrl: String): String? = withContext(Dispatchers.IO) {
        val cameraId = WraDisasterParser.extractWraCameraId(streamUrl) ?: return@withContext null
        val now = System.currentTimeMillis()
        val json = if (cachedJson != null && now - cachedAtMs < CACHE_TTL_MS) {
            cachedJson!!
        } else {
            runCatching {
                val conn = (URL(WraDisasterParser.WRA_DISASTER_API_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                }
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }.getOrNull()?.also { fresh ->
                cachedJson = fresh
                cachedAtMs = System.currentTimeMillis()
            } ?: return@withContext null
        }
        WraDisasterParser.resolveImageUrlFromJson(json, cameraId)
    }
}
