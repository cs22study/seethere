package com.cs22study.seethere.core

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/**
 * Defense-in-depth sanitizer for external camera URLs, display labels, WebView allowlists, and CSV cells.
 * Prevents XSS, SSRF against loopback/metadata/private LAN hosts, and CSV/Log injection.
 */
object StreamSecuritySanitizer {

    private val YOUTUBE_ID_REGEX = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_WATCH_REGEX = Regex("[?&]v=([A-Za-z0-9_-]{11})")
    private val YOUTUBE_PATH_REGEX = Regex("^/(?:embed|live|shorts|v)/([A-Za-z0-9_-]{11})")
    private val IPV4_REGEX = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val CONTROL_CHAR_REGEX = Regex("[\\u0000-\\u001F\\u007F]")
    private val SCRIPT_BLOCK_REGEX = Regex("(?is)<(script|style)[^>]*>.*?</\\1>")
    private val HTML_TAG_REGEX = Regex("<[^>]*>")

    private val YOUTUBE_HOST_ALLOWLIST = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtu.be",
        "www.youtube-nocookie.com",
        "youtube-nocookie.com",
    )

    private val WEBVIEW_MEDIA_HOST_ALLOWLIST = setOf(
        "com.cs22study.seethere",
        "www.youtube-nocookie.com",
        "youtube-nocookie.com",
        "www.youtube.com",
        "m.youtube.com",
        "youtube.com",
        "i.ytimg.com",
        "s.ytimg.com",
        "yt3.ggpht.com",
    )

    private val XFO_IFRAME_BLOCKLIST = setOf(
        "atis.ntpc.gov.tw",
    )

    /**
     * Cleans raw URL strings (such as TDX entries with stray leading single quotes) and validates security rules.
     */
    fun sanitizeStreamUrl(
        rawUrl: String?,
        allowPrivateLanHttp: Boolean = false,
    ): SanitizedUrlResult {
        if (rawUrl.isNullOrBlank()) {
            return blocked("網址不可為空白")
        }

        val cleaned = rawUrl.trim()
            .trim('\'', '"')
            .trim()

        if (CONTROL_CHAR_REGEX.containsMatchIn(cleaned)) {
            return blocked("網址包含非法控制字元")
        }

        val lower = cleaned.lowercase(Locale.ROOT)
        if (lower.startsWith("javascript:") ||
            lower.startsWith("data:") ||
            lower.startsWith("file:") ||
            lower.startsWith("content:") ||
            lower.startsWith("vbscript:") ||
            lower.startsWith("intent:") ||
            lower.startsWith("wss:") ||
            lower.startsWith("ws:")
        ) {
            return blocked("不安全的通訊協定：${lower.substringBefore(':')}")
        }

        val uri = try {
            URI(cleaned)
        } catch (_: Exception) {
            return blocked("網址格式不合法")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return blocked("缺少通訊協定 (需為 https://)")
        if (scheme != "https" && scheme != "http") {
            return blocked("僅支援 HTTPS（或受控區網 HTTP）")
        }

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return blocked("缺少主機網域名稱")
        if (host.isEmpty()) {
            return blocked("主機名稱不可為空")
        }

        if (isUnconditionallyBlockedHost(host)) {
            return blocked("基於資安防護，禁止存取本機或雲端中繼資料位址 ($host)")
        }

        val isPrivateLan = isPrivateLanHost(host)
        if (isPrivateLan && !allowPrivateLanHttp) {
            return blocked("預設封鎖內網 IP ($host) 以防範 SSRF；若為自家區網監視器請先手動開啟區網授權")
        }

        if (scheme == "http" && !(allowPrivateLanHttp && isPrivateLan)) {
            return blocked("為避免明文洩漏與中間人攻擊，公開來源僅允許 HTTPS")
        }

        if (host in XFO_IFRAME_BLOCKLIST) {
            return blocked("上游網站限制內嵌 (X-Frame-Options: SAMEORIGIN)")
        }

        val ytId = extractYouTubeVideoId(uri, host)
        if (host in YOUTUBE_HOST_ALLOWLIST) {
            if (ytId == null) {
                return blocked("無法解析合法的 11 碼 YouTube 直播 ID")
            }
            val safeEmbed = "https://www.youtube-nocookie.com/embed/$ytId?autoplay=1&playsinline=1&rel=0&mute=1"
            return SanitizedUrlResult(
                isAllowed = true,
                normalizedUrl = safeEmbed,
                protocol = StreamProtocol.YOUTUBE,
                extractedYouTubeId = ytId,
            )
        }

        val protocol = StreamRouter.classifyCleanHttpsUrl(cleaned, host)
        return SanitizedUrlResult(
            isAllowed = true,
            normalizedUrl = cleaned,
            protocol = protocol,
        )
    }

    /**
     * Extracts strict 11-character YouTube video ID from watch/live/embed/youtu.be URLs.
     */
    fun extractYouTubeVideoId(uri: URI, host: String): String? {
        if (host !in YOUTUBE_HOST_ALLOWLIST) return null
        val path = uri.path ?: ""
        if (host == "youtu.be") {
            val candidate = path.trimStart('/').substringBefore('/')
            return candidate.takeIf { YOUTUBE_ID_REGEX.matches(it) }
        }
        val query = uri.rawQuery ?: ""
        val watchMatch = YOUTUBE_WATCH_REGEX.find("?$query")
        if (watchMatch != null) {
            return watchMatch.groupValues[1]
        }
        val pathMatch = YOUTUBE_PATH_REGEX.find(path)
        if (pathMatch != null) {
            return pathMatch.groupValues[1]
        }
        return null
    }

    /**
     * Checks whether a host is permitted inside the isolated YouTube WebView sandbox.
     */
    fun isAllowedInYouTubeSandbox(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = try {
            URI(rawUrl.trim())
        } catch (_: Exception) {
            return false
        }
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (host in WEBVIEW_MEDIA_HOST_ALLOWLIST) return true
        return host.endsWith(".googlevideo.com") || host.endsWith(".ytimg.com")
    }

    /**
     * Builds a sanitized HTML wrapper with strict HTTPS Referer policy to prevent YouTube Error 153 in Android WebView.
     */
    fun buildSafeYouTubeIframeHtml(
        videoId: String,
        originUrl: String = "https://com.cs22study.seethere",
    ): String {
        require(YOUTUBE_ID_REGEX.matches(videoId)) { "Invalid YouTube video ID" }
        val safeSrc = "https://www.youtube-nocookie.com/embed/$videoId" +
            "?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=$originUrl"
        return """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1.0,maximum-scale=1.0">
              <meta name="referrer" content="strict-origin-when-cross-origin">
              <style>
                html, body { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }
                iframe { border:0; width:100%; height:100%; display:block; }
              </style>
            </head>
            <body>
              <iframe
                src="$safeSrc"
                referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media; picture-in-picture"
                allowfullscreen>
              </iframe>
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * Strips HTML markup and control characters from external titles/notes to prevent XSS and log forging.
     */
    fun sanitizeLabel(raw: String?, maxLength: Int = 120): String {
        if (raw.isNullOrBlank()) return ""
        val noScript = SCRIPT_BLOCK_REGEX.replace(raw, "")
        val noTags = HTML_TAG_REGEX.replace(noScript, "")
        val noCtrl = CONTROL_CHAR_REGEX.replace(noTags, " ")
        return noCtrl.trim().take(maxLength)
    }

    /**
     * Escapes a CSV cell per RFC 4180 and prefixes spreadsheet formula triggers (=, +, -, @, tab, CR).
     */
    fun escapeCsvCell(raw: String): String {
        val cleaned = CONTROL_CHAR_REGEX.replace(raw, " ").trim()
        val formulaSafe = if (cleaned.isNotEmpty() && cleaned[0] in charArrayOf('=', '+', '-', '@', '\t', '\r')) {
            "'$cleaned"
        } else {
            cleaned
        }
        val escaped = formulaSafe.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /**
     * Removes our anti-formula leading apostrophe when importing trusted CSV back into the app.
     */
    fun unescapeImportedText(raw: String): String {
        val trimmed = raw.trim()
        val unprefixed = if (trimmed.length >= 2 && trimmed[0] == '\'' && trimmed[1] in charArrayOf('=', '+', '-', '@')) {
            trimmed.substring(1)
        } else {
            trimmed
        }
        return sanitizeLabel(unprefixed)
    }

    private fun isUnconditionallyBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" || host == "::1" || host == "[::1]") {
            return true
        }
        val ipv4 = parseIpv4(host) ?: return false
        val (a, b, _, _) = ipv4
        if (a == 127 || a == 0) return true
        // Link-local / cloud metadata (e.g. 169.254.169.254)
        if (a == 169 && b == 254) return true
        return false
    }

    internal fun isPrivateLanHost(host: String): Boolean {
        if (host.endsWith(".local")) return true
        val ipv4 = parseIpv4(host)
        if (ipv4 != null) {
            val (a, b, _, _) = ipv4
            return a == 10 ||
                (a == 172 && b in 16..31) ||
                (a == 192 && b == 168)
        }
        return try {
            if (host.contains(':')) {
                val addr = InetAddress.getByName(host)
                addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun parseIpv4(host: String): List<Int>? {
        val m = IPV4_REGEX.matchEntire(host) ?: return null
        val octets = (1..4).map { m.groupValues[it].toIntOrNull() ?: return null }
        if (octets.any { it !in 0..255 }) return null
        return octets
    }

    private fun blocked(reason: String) = SanitizedUrlResult(
        isAllowed = false,
        normalizedUrl = "",
        protocol = StreamProtocol.BLOCKED,
        blockReason = reason,
    )
}
