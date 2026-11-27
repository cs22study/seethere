package com.cs22study.seethere.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppUpdateCheckerTest {

    @Test
    fun `compareVersions detects newer equal and older tags`() {
        assertTrue(AppUpdateChecker.isNewerVersion("v0.5.1", "0.5.0"))
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.0", "0.9.9"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.5.0", "0.5.0"))
        assertFalse(AppUpdateChecker.isNewerVersion("v0.4.1", "0.5.0"))
    }

    @Test
    fun `parseLatestReleaseFromArray parses tag arm64 url and enforces host allowlist`() {
        val json = """
            [
              {
                "tag_name": "v0.6.0",
                "name": "SeeThere v0.6.0",
                "html_url": "https://github.com/cs22study/seethere/releases/tag/v0.6.0",
                "prerelease": true,
                "published_at": "2026-11-28T10:00:00Z",
                "assets": [
                  { "browser_download_url": "https://github.com/cs22study/seethere/releases/download/v0.6.0/SeeThere-v0.6.0-armeabi-v7a.apk" },
                  { "browser_download_url": "https://github.com/cs22study/seethere/releases/download/v0.6.0/SeeThere-v0.6.0-arm64-v8a.apk" }
                ]
              }
            ]
        """.trimIndent()

        val info = AppUpdateChecker.parseLatestReleaseFromArray(json, "0.5.0")
        assertNotNull(info)
        assertEquals("v0.6.0", info!!.latestTag)
        assertTrue(info.hasUpdate)
        assertTrue(info.isPrerelease)
        assertEquals(
            "https://github.com/cs22study/seethere/releases/download/v0.6.0/SeeThere-v0.6.0-arm64-v8a.apk",
            info.arm64ApkUrl,
        )
    }

    @Test
    fun `rejects untrusted release download host`() {
        val spoofed = """
            [
              {
                "tag_name": "v9.9.9",
                "html_url": "https://evil.example.com/phish",
                "assets": [
                  { "browser_download_url": "https://evil.example.com/trojan.apk" }
                ]
              }
            ]
        """.trimIndent()

        val info = AppUpdateChecker.parseLatestReleaseFromArray(spoofed, "0.5.0")!!
        assertEquals("https://github.com/cs22study/seethere/releases", info.releasePageUrl)
        assertNull(info.arm64ApkUrl)
    }
}
