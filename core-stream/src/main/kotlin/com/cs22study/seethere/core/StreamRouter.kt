package com.cs22study.seethere.core

import java.util.Locale
import kotlin.math.min

/**
 * Classifies stream endpoints by transport protocol and computes exponential backoff reconnect delays.
 */
object StreamRouter {

    private val MJPEG_HOSTS = listOf(
        ".freeway.gov.tw",
        "cctv-ss",
        "tcnvr",
    )

    fun classifyCleanHttpsUrl(cleanUrl: String, lowerHost: String): StreamProtocol {
        val lower = cleanUrl.lowercase(Locale.ROOT)
        if (lower.contains("opendata.wra.gov.tw")) {
            return StreamProtocol.WRA_API
        }
        if (lower.contains(".m3u8")) {
            return StreamProtocol.HLS
        }
        if (MJPEG_HOSTS.any { lowerHost.contains(it) } || lower.contains("mjpeg") || lower.contains("mjpg")) {
            return StreamProtocol.MJPEG
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.contains("snapshot")) {
            return StreamProtocol.SNAPSHOT
        }
        // Default stream fallback for traffic NVR HLS or generic HTTPS feeds
        return StreamProtocol.HLS
    }

    /**
     * Exponential backoff with bounded cap (60s) and jitter to avoid thundering-herd retries.
     */
    fun computeReconnectDelayMs(
        attemptIndex: Int,
        jitterMs: Long = 0L,
        maxAttempts: Int = 5,
    ): Long? {
        if (attemptIndex < 0 || attemptIndex >= maxAttempts) return null
        val exponent = min(attemptIndex, 6)
        val base = 1_000L shl exponent
        val clampedJitter = jitterMs.coerceIn(0L, 500L)
        return min(60_000L, base) + clampedJitter
    }
}
