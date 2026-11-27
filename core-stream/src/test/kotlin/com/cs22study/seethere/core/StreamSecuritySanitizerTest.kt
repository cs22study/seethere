package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StreamSecuritySanitizerTest {

    @Test
    fun `allows valid https HLS stream and strips TDX leading single quote`() {
        val raw = "'https://jtmctrafficcctv5.gov.taipei/NVR/9e1db4ca-c5f8-4c6c-87ac-07abfe5ffb1a/live.m3u8"
        val res = StreamSecuritySanitizer.sanitizeStreamUrl(raw)
        assertTrue(res.isAllowed)
        assertEquals(StreamProtocol.HLS, res.protocol)
        assertEquals(
            "https://jtmctrafficcctv5.gov.taipei/NVR/9e1db4ca-c5f8-4c6c-87ac-07abfe5ffb1a/live.m3u8",
            res.normalizedUrl,
        )
    }

    @Test
    fun `normalizes YouTube watch and youtu_be URLs to privacy-enhanced nocookie embed`() {
        val watchRes = StreamSecuritySanitizer.sanitizeStreamUrl("https://www.youtube.com/watch?v=DjdUEyjx8GM")
        assertTrue(watchRes.isAllowed)
        assertEquals(StreamProtocol.YOUTUBE, watchRes.protocol)
        assertEquals("DjdUEyjx8GM", watchRes.extractedYouTubeId)
        assertEquals(
            "https://www.youtube-nocookie.com/embed/DjdUEyjx8GM?autoplay=1&playsinline=1&rel=0&mute=1",
            watchRes.normalizedUrl,
        )

        val shortRes = StreamSecuritySanitizer.sanitizeStreamUrl("https://youtu.be/91PfFoqvuUk")
        assertTrue(shortRes.isAllowed)
        assertEquals("91PfFoqvuUk", shortRes.extractedYouTubeId)
    }

    @Test
    fun `blocks javascript data file wss and control characters`() {
        listOf(
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd",
            "wss://example.com/stream",
            "https://example.com/live.m3u8\nInjected",
        ).forEach { bad ->
            val res = StreamSecuritySanitizer.sanitizeStreamUrl(bad)
            assertFalse(res.isAllowed, "Expected blocked for: $bad")
            assertEquals(StreamProtocol.BLOCKED, res.protocol)
        }
    }

    @Test
    fun `blocks localhost and cloud metadata unconditionally even if LAN HTTP allowed`() {
        listOf(
            "http://127.0.0.1/cam.mjpg",
            "http://localhost:8080/live",
            "http://169.254.169.254/latest/meta-data/",
        ).forEach { ssrf ->
            val res = StreamSecuritySanitizer.sanitizeStreamUrl(ssrf, allowPrivateLanHttp = true)
            assertFalse(res.isAllowed, "Expected SSRF blocked for: $ssrf")
        }
    }

    @Test
    fun `blocks private LAN IP by default and allows only when explicitly opted in`() {
        val lanCam = "http://192.168.1.88/mjpeg"
        assertFalse(StreamSecuritySanitizer.sanitizeStreamUrl(lanCam, allowPrivateLanHttp = false).isAllowed)
        val allowed = StreamSecuritySanitizer.sanitizeStreamUrl(lanCam, allowPrivateLanHttp = true)
        assertTrue(allowed.isAllowed)
        assertEquals(StreamProtocol.MJPEG, allowed.protocol)
    }

    @Test
    fun `blocks public plaintext HTTP and X-Frame-Options blocked host`() {
        assertFalse(StreamSecuritySanitizer.sanitizeStreamUrl("http://example.com/live.m3u8").isAllowed)
        assertFalse(StreamSecuritySanitizer.sanitizeStreamUrl("https://atis.ntpc.gov.tw/cam").isAllowed)
    }

    @Test
    fun `youtube WebView sandbox only permits allowlisted HTTPS media hosts`() {
        assertTrue(StreamSecuritySanitizer.isAllowedInYouTubeSandbox("https://com.cs22study.seethere/"))
        assertTrue(StreamSecuritySanitizer.isAllowedInYouTubeSandbox("https://www.youtube-nocookie.com/embed/DjdUEyjx8GM"))
        assertTrue(StreamSecuritySanitizer.isAllowedInYouTubeSandbox("https://rr3---sn-ogueln7s.googlevideo.com/videoplayback"))
        assertFalse(StreamSecuritySanitizer.isAllowedInYouTubeSandbox("https://evil-ad-network.example/click"))
        assertFalse(StreamSecuritySanitizer.isAllowedInYouTubeSandbox("http://www.youtube-nocookie.com/embed/DjdUEyjx8GM"))
    }

    @Test
    fun `builds safe YouTube iframe html with strict origin referrer policy for Error 153 prevention`() {
        val html = StreamSecuritySanitizer.buildSafeYouTubeIframeHtml("DjdUEyjx8GM")
        assertTrue(html.contains("https://www.youtube-nocookie.com/embed/DjdUEyjx8GM"))
        assertTrue(html.contains("strict-origin-when-cross-origin"))
        assertTrue(html.contains("origin=https://com.cs22study.seethere"))
    }

    @Test
    fun `escapes CSV formula injection triggers and strips HTML from labels`() {
        assertEquals("\"'=cmd|' /C calc'!A0\"", StreamSecuritySanitizer.escapeCsvCell("=cmd|' /C calc'!A0"))
        assertEquals("復興辛亥", StreamSecuritySanitizer.sanitizeLabel("<script>alert(1)</script>復興辛亥\r\n"))
    }
}
