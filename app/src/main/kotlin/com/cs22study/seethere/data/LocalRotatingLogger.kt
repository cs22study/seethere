package com.cs22study.seethere.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.cs22study.seethere.core.LogFormatter
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Local rotating JSONL logger:
 * - Stores files under app filesDir/logs/ with 2MB file cap & 14-day retention
 * - Supports one-click export to Documents/SeeThere/logs/
 */
class LocalRotatingLogger(context: Context) {

    private val appContext = context.applicationContext
    private val logsDir = File(appContext.filesDir, "logs").apply { mkdirs() }
    private val lock = Any()

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

    fun exportAllLogsToPublicDocuments(): Result<String> = runCatching {
        synchronized(lock) {
            val files = logsDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
                ?.sortedBy { it.name }
                ?: emptyList()
            val merged = buildString {
                for (f in files) {
                    append(f.readText(Charsets.UTF_8))
                }
            }
            val fileName = "seethere-logs-${LocalDate.now()}.jsonl"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/x-ndjson")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/SeeThere/logs")
                }
                val resolver = appContext.contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: error("無法建立公開日誌檔案")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(merged.toByteArray(Charsets.UTF_8))
                } ?: error("無法寫入公開日誌串流")
                "Documents/SeeThere/logs/$fileName"
            } else {
                @Suppress("DEPRECATION")
                val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val outDir = File(docsDir, "SeeThere/logs").apply { mkdirs() }
                val outFile = File(outDir, fileName)
                outFile.writeText(merged, Charsets.UTF_8)
                outFile.absolutePath
            }
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
