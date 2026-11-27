package com.cs22study.seethere.data

import android.content.Context
import com.cs22study.seethere.core.LogFormatter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Local rotating JSONL logger:
 * - Stores files under app filesDir/logs/ with 2MB file cap & 14-day retention
 * - Supports one-click export to Documents/SeeThere/logs/ and clearing old logs
 */
class LocalRotatingLogger(context: Context) {

    private val appContext = context.applicationContext
    private val logsDir = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val lock = Any()

    fun getStoragePathHint(): String = logsDir.absolutePath

    fun info(module: String, event: String, message: String) = write("INFO", module, event, message)
    fun warn(module: String, event: String, message: String) = write("WARN", module, event, message)
    fun error(module: String, event: String, message: String) = write("ERROR", module, event, message)

    private fun write(level: String, module: String, event: String, message: String) {
        val nowIso = Instant.now().toString()
        val line = LogFormatter.formatJsonLine(nowIso, level, module, event, message)
        synchronized(lock) {
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val target = resolveWritableLogFile(today)
            target.appendText(line + "\n", Charsets.UTF_8)
            pruneOldLogsLocked()
        }
    }

    fun readRecentLines(maxLines: Int = 80): List<String> {
        synchronized(lock) {
            val files = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedByDescending { it.lastModified() }
                ?: return emptyList()
            val collected = ArrayList<String>()
            for (f in files) {
                val lines = runCatching { f.readLines(Charsets.UTF_8) }.getOrDefault(emptyList())
                for (i in lines.indices.reversed()) {
                    collected.add(lines[i])
                    if (collected.size >= maxLines) return collected
                }
            }
            return collected
        }
    }

    fun exportAllLogsToPublicDocuments(): Result<String> {
        val merged = synchronized(lock) {
            val files = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }
                ?: emptyList()
            buildString {
                for (f in files) {
                    append(f.readText(Charsets.UTF_8))
                }
            }
        }
        val fileName = "seethere-logs-${LocalDate.now()}.jsonl"
        return PublicDocumentsStorage.writeTextFile(
            context = appContext,
            subFolder = "logs",
            fileName = fileName,
            mimeType = "application/x-ndjson",
            content = merged,
        )
    }

    fun clearAllLogs(): Int {
        synchronized(lock) {
            val files = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return 0
            var deleted = 0
            for (f in files) {
                if (f.delete()) deleted++
            }
            return deleted
        }
    }

    private fun resolveWritableLogFile(dateStr: String): File {
        val base = File(logsDir, "seethere-$dateStr.jsonl")
        if (!base.exists() || base.length() < MAX_FILE_BYTES) {
            return base
        }
        var part = 1
        while (true) {
            val rotated = File(logsDir, "seethere-$dateStr.$part.jsonl")
            if (!rotated.exists() || rotated.length() < MAX_FILE_BYTES) {
                return rotated
            }
            part++
        }
    }

    private fun pruneOldLogsLocked() {
        val cutoffMillis = System.currentTimeMillis() - RETENTION_DAYS * 24L * 3600L * 1000L
        val files = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
        for (f in files) {
            if (f.lastModified() < cutoffMillis) {
                f.delete()
            }
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
        private const val RETENTION_DAYS = 14L
    }
}
