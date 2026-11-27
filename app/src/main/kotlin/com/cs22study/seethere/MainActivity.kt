package com.cs22study.seethere

import android.Manifest
import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.StreamProtocol
import com.cs22study.seethere.core.StreamSecuritySanitizer
import com.cs22study.seethere.server.SeeThereLanWebServer
import com.cs22study.seethere.service.ScreenCaptureService
import com.cs22study.seethere.service.ScreenshotStorageHelper
import com.cs22study.seethere.service.SeeThereLanService
import com.cs22study.seethere.ui.PlayerViewMode
import com.cs22study.seethere.ui.SeeThereHomeScreen
import com.cs22study.seethere.ui.SeeThereViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val isTvMode = uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        setContent {
            val vm: SeeThereViewModel = viewModel()
            val settings by vm.prefsRepo.settings.collectAsState()
            val context = LocalContext.current

            var pendingShotCamera by remember { mutableStateOf<CameraItem?>(null) }
            var pendingStorageAction by remember { mutableStateOf<(() -> Unit)?>(null) }
            var hasLocationPermission by remember { mutableStateOf(checkLocationPermission()) }

            val locationLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                hasLocationPermission = checkLocationPermission()
                vm.refreshGpsLocation(hasLocationPermission)
            }

            val documentsStorageLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                pendingStorageAction?.invoke()
                pendingStorageAction = null
            }

            val runWithDocumentsPermission: (() -> Unit) -> Unit = { action ->
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    pendingStorageAction = action
                    documentsStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    action()
                }
            }

            val screenCaptureLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null) {
                    val camTitle = pendingShotCamera?.title.orEmpty()
                    ScreenCaptureService.startCapture(
                        context = context,
                        resultCode = result.resultCode,
                        data = data,
                        cameraTitle = camTitle,
                        cornerLabelEnabled = settings.screenshotCornerLabelEnabled,
                    )
                    vm.logger.info("Capture", "SCREENSHOT", "Captured screen for camera=$camTitle cornerLabel=${settings.screenshotCornerLabelEnabled}")
                } else {
                    vm.showBanner("已取消畫面截圖")
                }
            }

            val launchProjectionIntent: () -> Unit = {
                val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
            }

            val storagePermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                launchProjectionIntent()
            }

            val triggerScreenshot: (CameraItem?, PlayerViewMode) -> Unit = { cam, viewMode ->
                pendingShotCamera = cam
                val needsMediaProjection = viewMode == PlayerViewMode.QUAD_2X2 ||
                    (cam != null && StreamSecuritySanitizer.sanitizeStreamUrl(cam.streamUrl, cam.allowLocalHttp).protocol == StreamProtocol.YOUTUBE)

                val runCapture = {
                    if (!needsMediaProjection) {
                        ScreenshotStorageHelper.tryFastPixelCopyCapture(
                            activity = this@MainActivity,
                            cameraTitle = cam?.title.orEmpty(),
                            addCornerLabel = settings.screenshotCornerLabelEnabled,
                            onFallbackToMediaProjection = { launchProjectionIntent() },
                        )
                        vm.logger.info("Capture", "PIXEL_COPY", "Fast PixelCopy capture for ${cam?.id ?: "home"}")
                    } else {
                        launchProjectionIntent()
                    }
                }

                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                ) {
                    storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    runCapture()
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF38BDF8),
                    secondary = Color(0xFFFACC15),
                    background = Color(0xFF090D16),
                    surface = Color(0xFF0F172A),
                )
            ) {
                SeeThereHomeScreen(
                    vm = vm,
                    isTvMode = isTvMode,
                    hasLocationPermission = hasLocationPermission,
                    onRequestLocation = {
                        locationLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            )
                        )
                    },
                    onTriggerScreenshot = triggerScreenshot,
                    onWithDocumentsPermission = runWithDocumentsPermission,
                    onExitApp = {
                        SeeThereLanWebServer.stop()
                        SeeThereLanService.stop(applicationContext)
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    private fun checkLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }
}
