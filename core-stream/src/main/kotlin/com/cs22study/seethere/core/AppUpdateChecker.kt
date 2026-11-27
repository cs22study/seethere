package com.cs22study.seethere.core

import java.net.URI
import java.util.Locale

data class AppUpdateInfo(
    val latestTag: String,
    val releaseName: String,
    val releasePageUrl: String,
    val arm64ApkUrl: String?,
    val publishedAtIso: String,
    val isPrerelease: Boolean,
    val hasUpdate: Boolean,
)

/**
 * Pure-Kotlin GitHub Releases update parser and semantic version comparator with strict host allowlist.
 */
object AppUpdateChecker {

    private val TRUSTED_RELEASE_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "cs22study.github.io",
    )

    fun normalizeVersionTuple(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (cleaned.isBlank()) return listOf(0, 0, 0)
        return cleaned.split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun compareVersions(candidateTag: String, currentVersion: String): Int {
        val a = normalizeVersionTuple(candidateTag)
        val b = normalizeVersionTuple(currentVersion)
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    fun isNewerVersion(candidateTag: String, currentVersion: String): Boolean =
        compareVersions(candidateTag, currentVersion) > 0

    fun isTrustedUpdateUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return scheme == "https" && host in TRUSTED_RELEASE_HOSTS
    }

    /**
     * Extracts the latest release summary from GitHub REST `/releases` JSON array without org.json dependency.
     */
    fun parseLatestReleaseFromArray(rawJson: String, currentVersion: String): AppUpdateInfo? {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("[")) return null

        val tag = extractFirstStringField(trimmed, "tag_name") ?: return null
        val releaseName = extractFirstStringField(trimmed, "name").orEmpty().ifBlank { tag }
        val pageUrlRaw = extractFirstStringField(trimmed, "html_url")
            ?: "https://github.com/cs22study/seethere/releases"
        val releasePageUrl = if (isTrustedUpdateUrl(pageUrlRaw)) pageUrlRaw else "https://github.com/cs22study/seethere/releases"
        val publishedAt = extractFirstStringField(trimmed, "published_at").orEmpty()
        val isPrerelease = Regex("\"prerelease\"\\s*:\\s*(true|false)").find(trimmed)?.groupValues?.get(1) == "true"

        val apkMatches = Regex("\"browser_download_url\"\\s*:\\s*\"(https:[^\"]+\\.apk)\"")
            .findAll(trimmed)
            .map { it.groupValues[1] }
            .filter { isTrustedUpdateUrl(it) }
            .toList()
        val arm64Url = apkMatches.firstOrNull { it.contains("arm64", ignoreCase = true) }
            ?: apkMatches.firstOrNull()

        return AppUpdateInfo(
            latestTag = tag,
            releaseName = releaseName,
            releasePageUrl = releasePageUrl,
            arm64ApkUrl = arm64Url,
            publishedAtIso = publishedAt,
            isPrerelease = isPrerelease,
            hasUpdate = isNewerVersion(tag, currentVersion),
        )
    }

    private fun extractFirstStringField(json: String, field: String): String? {
        val pattern = Regex("\"${Regex.escape(field)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val raw = pattern.find(json)?.groupValues?.get(1) ?: return null
        return raw.replace("\\/", "/").replace("\\\"", "\"")
    }
}
