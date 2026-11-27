package com.cs22study.seethere.core

import java.net.URI
import java.util.Locale

/**
 * Resolves and validates WRA (水利署) dynamic CCTV endpoints whose encrypted CCTV_SN rotates daily.
 */
object WraDisasterParser {

    const val WRA_DISASTER_API_URL =
        "https://opendata.wra.gov.tw/api/v2/f71b74eb-cbe5-42c6-8be5-7500450e7db0?sort=_importdate+asc&format=JSON"

    private val ALLOWED_WRA_IMAGE_HOSTS = setOf(
        "fmg.wra.gov.tw",
        "web.wra.gov.tw",
    )

    private val CAMERA_ID_REGEX = Regex("cameraid=([A-Za-z0-9_-]+)")
    private val JSON_OBJ_REGEX = Regex("\\{[^{}]*\\}")
    private val JSON_FIELD_REGEX = Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")

    fun extractWraCameraId(streamUrl: String): String? {
        val m = CAMERA_ID_REGEX.find(streamUrl) ?: return null
        return m.groupValues[1]
    }

    /**
     * Strictly allows only HTTPS URLs hosted on official WRA image domains.
     */
    fun safeWraImageUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val cleaned = rawUrl.trim().replace("\\u0026", "&").replace("\\/", "/")
        val uri = try {
            URI(cleaned)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
        return if (scheme == "https" && host in ALLOWED_WRA_IMAGE_HOSTS) {
            cleaned
        } else {
            null
        }
    }

    /**
     * Parses WRA JSON array payload and resolves the daily live imageurl for [targetCameraId].
     */
    fun resolveImageUrlFromJson(jsonArrayText: String, targetCameraId: String): String? {
        if (jsonArrayText.isBlank() || targetCameraId.isBlank()) return null
        val trimmed = jsonArrayText.trim()
        if (!trimmed.startsWith("[")) return null

        for (objMatch in JSON_OBJ_REGEX.findAll(trimmed)) {
            var camId: String? = null
            var imageUrl: String? = null
            for (field in JSON_FIELD_REGEX.findAll(objMatch.value)) {
                val key = field.groupValues[1]
                val valUnescaped = field.groupValues[2]
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                when (key) {
                    "cameraid" -> camId = valUnescaped
                    "imageurl" -> imageUrl = valUnescaped
                }
            }
            if (camId == targetCameraId) {
                return safeWraImageUrl(imageUrl)
            }
        }
        return null
    }
}
