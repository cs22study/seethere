package com.cs22study.seethere.core

/**
 * Formats structured JSONL local log lines with log-forging prevention and sensitive credential redaction.
 */
object LogFormatter {

    private val SENSITIVE_KEY_PATTERN = Regex("(?i)(password|passwd|secret|token|apikey|api_key|auth)=([^\\s,;]+)")

    fun formatJsonLine(
        timestampIso: String,
        level: String,
        module: String,
        event: String,
        message: String,
    ): String {
        val safeTs = jsonEscape(StreamSecuritySanitizer.sanitizeLabel(timestampIso, 40))
        val safeLvl = jsonEscape(StreamSecuritySanitizer.sanitizeLabel(level, 16))
        val safeMod = jsonEscape(StreamSecuritySanitizer.sanitizeLabel(module, 48))
        val safeEvt = jsonEscape(StreamSecuritySanitizer.sanitizeLabel(event, 64))
        val redactedMsg = SENSITIVE_KEY_PATTERN.replace(message) { "${it.groupValues[1]}=***" }
        val safeMsg = jsonEscape(StreamSecuritySanitizer.sanitizeLabel(redactedMsg, 500))
        return """{"ts":"$safeTs","level":"$safeLvl","module":"$safeMod","event":"$safeEvt","message":"$safeMsg"}"""
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n', '\r', '\t' -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
