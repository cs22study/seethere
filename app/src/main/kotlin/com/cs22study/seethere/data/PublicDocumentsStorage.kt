package com.cs22study.seethere.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Shared exporter for writing user-visible files into public Documents/SeeThere/ (Android 7+ compatible).
 */
object PublicDocumentsStorage {

    fun writeTextFile(
        context: Context,
        subFolder: String,
        fileName: String,
        mimeType: String,
        content: String,
    ): Result<String> = runCatching {
        val appContext = context.applicationContext
        val relativeDir = if (subFolder.isBlank()) {
            "${Environment.DIRECTORY_DOCUMENTS}/SeeThere"
        } else {
            "${Environment.DIRECTORY_DOCUMENTS}/SeeThere/$subFolder"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativeDir)
            }
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                ?: error("無法建立 Documents 檔案")
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: error("無法寫入 Documents 串流")
            if (subFolder.isBlank()) "Documents/SeeThere/$fileName" else "Documents/SeeThere/$subFolder/$fileName"
        } else {
            @Suppress("DEPRECATION")
            val publicDocs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val targetDir = if (subFolder.isBlank()) {
                File(publicDocs, "SeeThere")
            } else {
                File(publicDocs, "SeeThere/$subFolder")
            }
            val publicWrite = runCatching {
                targetDir.mkdirs()
                val outFile = File(targetDir, fileName)
                outFile.writeText(content, Charsets.UTF_8)
                outFile.absolutePath
            }
            publicWrite.getOrElse {
                // Graceful fallback to app-specific external Documents if storage permission denied
                val appDocs = appContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
                    ?: appContext.filesDir
                val fallbackDir = if (subFolder.isBlank()) {
                    File(appDocs, "SeeThere")
                } else {
                    File(appDocs, "SeeThere/$subFolder")
                }.apply { mkdirs() }
                val fallbackFile = File(fallbackDir, fileName)
                fallbackFile.writeText(content, Charsets.UTF_8)
                fallbackFile.absolutePath
            }
        }
    }
}
