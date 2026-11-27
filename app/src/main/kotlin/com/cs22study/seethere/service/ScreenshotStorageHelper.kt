package com.cs22study.seethere.service

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Shared screenshot post-processing (optional corner watermark) and MediaStore PNG persistence,
 * plus permission-free PixelCopy fast-path for Android 8.0+ native streams.
 */
object ScreenshotStorageHelper {

    fun tryFastPixelCopyCapture(
        activity: Activity,
        cameraTitle: String,
        addCornerLabel: Boolean,
        onFallbackToMediaProjection: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFallbackToMediaProjection()
            return
        }
        val window = activity.window
        val decor = window.decorView
        val width = decor.width
        val height = decor.height
        if (width <= 0 || height <= 0) {
            onFallbackToMediaProjection()
            return
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(
                window,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        val processed = if (addCornerLabel) {
                            applyCornerWatermark(bitmap, cameraTitle)
                        } else {
                            bitmap
                        }
                        runCatching {
                            saveBitmapToGallery(activity.applicationContext, processed)
                        }.onSuccess { saved ->
                            ScreenCaptureBus.emitSuccess(saved)
                        }.onFailure {
                            onFallbackToMediaProjection()
                        }
                    } else {
                        bitmap.recycle()
                        onFallbackToMediaProjection()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure {
            bitmap.recycle()
            onFallbackToMediaProjection()
        }
    }

    fun applyCornerWatermark(source: Bitmap, cameraTitle: String): Bitmap {
        val mutable = source.copy(Bitmap.Config.ARGB_8888, true)
        if (mutable !== source) {
            source.recycle()
        }
        val canvas = Canvas(mutable)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val label = if (cameraTitle.isNotBlank()) "$cameraTitle  •  $stamp" else "SeeThere  •  $stamp"

        val textSize = (mutable.width.coerceAtMost(mutable.height) * 0.024f).coerceIn(22f, 44f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 15, 23, 42)
        }
        val padH = textSize * 0.7f
        val padV = textSize * 0.45f
        val textWidth = textPaint.measureText(label)
        val fm = textPaint.fontMetrics
        val textHeight = fm.descent - fm.ascent

        val margin = textSize * 0.8f
        val rect = RectF(
            margin,
            mutable.height - margin - textHeight - padV * 2,
            (margin + textWidth + padH * 2).coerceAtMost(mutable.width - margin),
            mutable.height - margin,
        )
        canvas.drawRoundRect(rect, 12f, 12f, bgPaint)
        canvas.drawText(label, rect.left + padH, rect.bottom - padV - fm.descent, textPaint)
        return mutable
    }

    fun saveBitmapToGallery(context: Context, bitmap: Bitmap): CapturedScreenshot {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val fileName = "SeeThere_$timestamp.png"
        val thumbScale = 240f / bitmap.width.coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(
            bitmap,
            240,
            (bitmap.height * thumbScale).toInt().coerceAtLeast(135),
            true,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/SeeThere")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert failed")
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } ?: throw IllegalStateException("Unable to open output stream")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            bitmap.recycle()
            return CapturedScreenshot(uri, fileName, "Pictures/SeeThere/$fileName", thumb)
        } else {
            @Suppress("DEPRECATION")
            val pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val dir = File(pics, "SeeThere").apply { mkdirs() }
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(outFile.absolutePath), arrayOf("image/png"), null)
            return CapturedScreenshot(Uri.fromFile(outFile), fileName, outFile.absolutePath, thumb)
        }
    }
}
