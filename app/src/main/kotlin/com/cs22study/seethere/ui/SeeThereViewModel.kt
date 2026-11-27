package com.cs22study.seethere.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cs22study.seethere.core.CameraCodec
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.CorridorCameraResult
import com.cs22study.seethere.core.GeoSpatialEngine
import com.cs22study.seethere.core.LocationAnchor
import com.cs22study.seethere.core.NearbyCameraResult
import com.cs22study.seethere.core.StreamSecuritySanitizer
import com.cs22study.seethere.data.LocalRotatingLogger
import com.cs22study.seethere.data.PreferencesRepository
import com.cs22study.seethere.data.db.AppDatabase
import com.cs22study.seethere.data.db.CameraEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID

enum class HomeTab {
    FAVORITES,
    NEARBY,
    ADD_IMPORT,
    SETTINGS_LOGS,
}

enum class PlayerViewMode {
    SINGLE,
    QUAD_2X2,
    CAROUSEL_30S,
}

class SeeThereViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val dao = db.cameraDao()
    val prefsRepo = PreferencesRepository(application)
    val logger = LocalRotatingLogger(application)

    val activeCameras: StateFlow<List<CameraItem>> = dao.observeActiveCameras()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val deletedCameras: StateFlow<List<CameraItem>> = dao.observeDeletedCameras()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val anchors: StateFlow<List<LocationAnchor>> = dao.observeAnchors()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _gpsLocation = MutableStateFlow<Pair<Double, Double>?>(null)
    val gpsLocation: StateFlow<Pair<Double, Double>?> = _gpsLocation.asStateFlow()

    private val _selectedCameraId = MutableStateFlow<String?>(null)
    val selectedCamera: StateFlow<CameraItem?> = combine(activeCameras, _selectedCameraId) { cams, id ->
        cams.firstOrNull { it.id == id } ?: cams.firstOrNull { it.isFavorite } ?: cams.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _recentLogs = MutableStateFlow<List<String>>(emptyList())
    val recentLogs: StateFlow<List<String>> = _recentLogs.asStateFlow()

    private val _corridorStartAnchorId = MutableStateFlow("anchor_ntu")
    val corridorStartAnchorId: StateFlow<String> = _corridorStartAnchorId.asStateFlow()

    private val _corridorEndAnchorId = MutableStateFlow("anchor_tpe_airport")
    val corridorEndAnchorId: StateFlow<String> = _corridorEndAnchorId.asStateFlow()

    val corridorCameras: StateFlow<List<CorridorCameraResult>> = combine(
        activeCameras,
        anchors,
        _corridorStartAnchorId,
        _corridorEndAnchorId,
    ) { cams, anchorList, startId, endId ->
        val start = anchorList.firstOrNull { it.id == startId } ?: anchorList.firstOrNull()
        val end = anchorList.firstOrNull { it.id == endId } ?: anchorList.getOrNull(1) ?: start
        if (start == null || end == null || start.id == end.id) {
            emptyList()
        } else {
            GeoSpatialEngine.findAlongCorridor(
                cameras = cams,
                startLat = start.latitude,
                startLon = start.longitude,
                endLat = end.latitude,
                endLon = end.longitude,
                corridorWidthMeters = 5_000.0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nearbyCameras: StateFlow<List<NearbyCameraResult>> = combine(
        activeCameras,
        anchors,
        prefsRepo.settings,
        _gpsLocation,
    ) { cams, anchorList, settings, gps ->
        val selectedAnchor = anchorList.firstOrNull { it.id == settings.selectedAnchorId }
            ?: anchorList.firstOrNull()
        val (lat, lon) = gps ?: if (selectedAnchor != null) {
            selectedAnchor.latitude to selectedAnchor.longitude
        } else {
            25.0173 to 121.5397
        }
        GeoSpatialEngine.findNearby(
            cameras = cams,
            centerLat = lat,
            centerLon = lon,
            radiusMeters = settings.searchRadiusMeters.toDouble(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.ensureSeeded()
            logger.info("App", "INIT", "SeeThere initialized and demo catalog verified")
            refreshLogs()
        }
    }

    fun selectCamera(camera: CameraItem) {
        _selectedCameraId.value = camera.id
        logger.info("Player", "SELECT_CAMERA", "Selected ${camera.id} (${camera.title})")
    }

    fun stepCarouselNext() {
        val cams = activeCameras.value
        val favs = cams.filter { it.isFavorite }
        val pool = if (favs.size >= 2) favs else cams
        if (pool.isEmpty()) return
        val currentIdx = pool.indexOfFirst { it.id == selectedCamera.value?.id }
        val next = pool[(currentIdx + 1) % pool.size]
        _selectedCameraId.value = next.id
    }

    fun setCorridorStartAnchor(anchorId: String) {
        _corridorStartAnchorId.value = anchorId
    }

    fun setCorridorEndAnchor(anchorId: String) {
        _corridorEndAnchorId.value = anchorId
    }

    fun toggleFavorite(camera: CameraItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.setFavorite(camera.id, !camera.isFavorite)
            logger.info("Camera", "FAVORITE", "Set ${camera.id} favorite=${!camera.isFavorite}")
        }
    }

    fun softDeleteCamera(camera: CameraItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.softDelete(camera.id, Instant.now().toString())
            logger.warn("Camera", "SOFT_DELETE", "Soft deleted ${camera.id} (${camera.title})")
            showBanner("已移至回收桶：${camera.title}")
        }
    }

    fun restoreCamera(camera: CameraItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.restore(camera.id)
            logger.info("Camera", "RESTORE", "Restored ${camera.id}")
            showBanner("已還原：${camera.title}")
        }
    }

    fun purgeCamera(camera: CameraItem) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.purgeDeleted(camera.id)
            logger.warn("Camera", "PURGE", "Permanently purged ${camera.id}")
            showBanner("已永久刪除：${camera.title}")
        }
    }

    fun selectBackupFor(failedCamera: CameraItem) {
        val backup = GeoSpatialEngine.findNearestBackup(activeCameras.value, failedCamera)
        if (backup != null) {
            _selectedCameraId.value = backup.camera.id
            val dist = GeoSpatialEngine.formatDistance(backup.distanceMeters)
            logger.info("Player", "BACKUP_SWITCH", "Switched ${failedCamera.id} -> ${backup.camera.id} ($dist)")
            showBanner("已切換至最近備援：${backup.camera.title}（距離 $dist）")
        } else {
            showBanner("附近 10 公里內暫無其他備援鏡頭")
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshGpsLocation(hasLocationPermission: Boolean) {
        if (!hasLocationPermission) {
            _gpsLocation.value = null
            showBanner("未授予 GPS 權限，目前使用自訂地點錨點搜尋")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val lm = getApplication<Application>().getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var bestLatLon: Pair<Double, Double>? = null
            for (p in providers) {
                val loc = runCatching { lm?.getLastKnownLocation(p) }.getOrNull()
                if (loc != null && GeoSpatialEngine.isValidCoordinate(loc.latitude, loc.longitude)) {
                    bestLatLon = loc.latitude to loc.longitude
                    break
                }
            }
            if (bestLatLon != null) {
                _gpsLocation.value = bestLatLon
                logger.info("Geo", "GPS_FIX", "Updated GPS anchor")
                showBanner("已更新目前 GPS 位置")
            } else {
                _gpsLocation.value = null
                showBanner("裝置無 GPS 訊號（電視盒屬正常現象），改用地點錨點搜尋")
            }
        }
    }

    fun clearGpsOverrideAndUseAnchor(anchorId: String) {
        _gpsLocation.value = null
        prefsRepo.setSelectedAnchorId(anchorId)
        logger.info("Geo", "ANCHOR_SELECT", "Selected anchor=$anchorId")
    }

    fun addSingleCamera(
        rawTitle: String,
        rawUrl: String,
        region: String,
        city: String,
        latText: String,
        lonText: String,
        markFavorite: Boolean,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val allowLan = prefsRepo.settings.value.allowLocalLanHttp
            val urlCheck = StreamSecuritySanitizer.sanitizeStreamUrl(rawUrl, allowLan)
            if (!urlCheck.isAllowed) {
                logger.warn("Security", "ADD_BLOCKED", "Blocked URL: ${urlCheck.blockReason}")
                showBanner("資安攔截：${urlCheck.blockReason}")
                return@launch
            }

            var resolvedTitle = StreamSecuritySanitizer.sanitizeLabel(rawTitle)
            var resolvedThumb = if (urlCheck.extractedYouTubeId != null) {
                "https://i.ytimg.com/vi/${urlCheck.extractedYouTubeId}/hqdefault.jpg"
            } else {
                ""
            }

            if (resolvedTitle.isBlank() && urlCheck.extractedYouTubeId != null) {
                val oembed = fetchYouTubeOEmbed(urlCheck.extractedYouTubeId!!)
                if (oembed != null) {
                    resolvedTitle = StreamSecuritySanitizer.sanitizeLabel(oembed.first)
                    if (oembed.second.isNotBlank()) resolvedThumb = oembed.second
                }
            }

            if (resolvedTitle.isBlank()) {
                showBanner("請輸入攝影機名稱")
                return@launch
            }

            val lat = latText.trim().toDoubleOrNull() ?: 25.0173
            val lon = lonText.trim().toDoubleOrNull() ?: 121.5397
            if (!GeoSpatialEngine.isValidCoordinate(lat, lon)) {
                showBanner("經緯度超出合理範圍")
                return@launch
            }

            val item = CameraItem(
                id = "custom_${UUID.randomUUID().toString().take(8)}",
                title = resolvedTitle,
                region = StreamSecuritySanitizer.sanitizeLabel(region).ifBlank { "自訂" },
                city = StreamSecuritySanitizer.sanitizeLabel(city).ifBlank { "個人清單" },
                category = "自訂新增",
                streamUrl = urlCheck.normalizedUrl,
                thumbnailUrl = resolvedThumb,
                latitude = lat,
                longitude = lon,
                sourceName = "手動新增",
                isFavorite = markFavorite,
                sortOrder = activeCameras.value.size + 1,
                allowLocalHttp = allowLan,
            )
            dao.upsert(CameraEntity.fromDomain(item))
            _selectedCameraId.value = item.id
            logger.info("Camera", "ADD", "Added ${item.id} (${item.title}) protocol=${urlCheck.protocol}")
            showBanner("已新增即時影像：${item.title}")
        }
    }

    fun importCsvText(csvContent: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val allowLan = prefsRepo.settings.value.allowLocalLanHttp
            val report = CameraCodec.importFromCsv(csvContent, allowLan)
            if (report.accepted.isNotEmpty()) {
                dao.upsertAll(report.accepted.map { CameraEntity.fromDomain(it) })
            }
            logger.info(
                "Import",
                "CSV_IMPORT",
                "Accepted=${report.accepted.size}, Rejected=${report.rejected.size}",
            )
            val firstReject = report.rejected.firstOrNull()?.let { "；首筆攔截：${it.reason}" }.orEmpty()
            showBanner("匯入完成：成功 ${report.accepted.size} 筆、阻擋 ${report.rejected.size} 筆$firstReject")
        }
    }

    fun buildExportCsv(): String = CameraCodec.exportToCsv(activeCameras.value)

    fun exportLogsToDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            logger.exportAllLogsToPublicDocuments()
                .onSuccess { path -> showBanner("日誌已匯出至 $path") }
                .onFailure { ex -> showBanner("匯出日誌失敗：${ex.localizedMessage}") }
            refreshLogs()
        }
    }

    fun refreshLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            _recentLogs.value = logger.readRecentLines(60)
        }
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    private suspend fun fetchYouTubeOEmbed(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
            }
            conn.inputStream.bufferedReader().use { reader ->
                val obj = JSONObject(reader.readText())
                val title = obj.optString("title", "")
                val thumb = obj.optString("thumbnail_url", "")
                title to thumb
            }
        }.getOrNull()
    }
}
