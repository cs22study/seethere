package com.cs22study.seethere.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicBoolean

data class CapturedScreenshot(
    val uri: Uri,
    val fileName: String,
    val displayPath: String,
    val thumbnail: Bitmap,
    val capturedAtMillis: Long = System.currentTimeMillis(),
)

object ScreenCaptureBus {
    private val _latest = MutableStateFlow<CapturedScreenshot?>(null)
    val latest: StateFlow<CapturedScreenshot?> = _latest.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun emitSuccess(screenshot: CapturedScreenshot) {
        _error.value = null
        _latest.value = screenshot
    }

    fun emitError(message: String) {
        _error.value = message
    }

    fun clearPreview() {
        _latest.value = null
    }

    fun clearError() {
        _error.value = null
    }
}

/**
 * Full-screen MediaProjection capture service supporting both Android 7+ phones and Google TV boxes.
 * Optionally renders a corner badge (camera name + timestamp) when enabled in user settings.
 */
class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val captured = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForegroundCompat(buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        val cameraTitle = intent?.getStringExtra(EXTRA_CAMERA_TITLE).orEmpty()
        val addCornerLabel = intent?.getBooleanExtra(EXTRA_CORNER_LABEL, true) ?: true

        if (resultCode != Activity.RESULT_OK || data == null) {
            ScreenCaptureBus.emitError("未取得螢幕擷取授權")
            stopGracefully()
            return START_NOT_STICKY
        }

        mainHandler.postDelayed({
            runCatching {
                startCapture(resultCode, data, cameraTitle, addCornerLabel)
            }.onFailure { ex ->
                ScreenCaptureBus.emitError("截圖失敗：${ex.localizedMessage ?: "系統不支援"}")
                stopGracefully()
            }
        }, 350L)

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        data: Intent,
        cameraTitle: String,
        addCornerLabel: Boolean,
    ) {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(resultCode, data)
            ?: throw IllegalStateException("MediaProjection unavailable")
        mediaProjection = projection

        val thread = HandlerThread("SeeThereScreenCapture").apply { start() }
        captureThread = thread
        val bgHandler = Handler(thread.looper)

        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanupResources()
            }
        }, bgHandler)

        val (width, height, densityDpi) = resolveScreenMetrics()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            if (!captured.compareAndSet(false, true)) return@setOnImageAvailableListener
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val rawBitmap = imageToBitmap(image, width, height)
                val finalBitmap = if (addCornerLabel) {
                    ScreenshotStorageHelper.applyCornerWatermark(rawBitmap, cameraTitle)
                } else {
                    rawBitmap
                }
                val saved = ScreenshotStorageHelper.saveBitmapToGallery(applicationContext, finalBitmap)
                ScreenCaptureBus.emitSuccess(saved)
            } catch (ex: Exception) {
                ScreenCaptureBus.emitError("儲存截圖失敗：${ex.localizedMessage ?: "未知錯誤"}")
            } finally {
                image.close()
                mainHandler.post { stopGracefully() }
            }
        }, bgHandler)

        virtualDisplay = projection.createVirtualDisplay(
            "SeeThereScreenCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            bgHandler,
        )
    }

    private fun resolveScreenMetrics(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return Triple(metrics.widthPixels.coerceAtLeast(320), metrics.heightPixels.coerceAtLeast(240), metrics.densityDpi)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val fullWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(fullWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        return if (fullWidth != width) {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        } else {
            padded
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "畫面截圖服務",
                NotificationManager.IMPORTANCE_LOW,
            )
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SeeThere 到處看看")
            .setContentText("正在擷取即時影像畫面...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun cleanupResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { captureThread?.quitSafely() }
        captureThread = null
    }

    private fun stopGracefully() {
        cleanupResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        cleanupResources()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "seethere_capture"
        private const val NOTIFICATION_ID = 4101
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CAMERA_TITLE = "camera_title"
        private const val EXTRA_CORNER_LABEL = "corner_label"

        fun startCapture(
            context: Context,
            resultCode: Int,
            data: Intent,
            cameraTitle: String,
            cornerLabelEnabled: Boolean,
        ) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
                putExtra(EXTRA_CAMERA_TITLE, cameraTitle)
                putExtra(EXTRA_CORNER_LABEL, cornerLabelEnabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
