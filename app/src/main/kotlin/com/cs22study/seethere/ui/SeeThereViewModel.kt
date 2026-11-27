package com.cs22study.seethere.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cs22study.seethere.core.AppUpdateChecker
import com.cs22study.seethere.core.AppUpdateInfo
import com.cs22study.seethere.core.CameraCodec
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.CameraNavigator
import com.cs22study.seethere.core.CorridorCameraResult
import com.cs22study.seethere.core.ExternalSearchParsers
import com.cs22study.seethere.core.GeoSpatialEngine
import com.cs22study.seethere.core.LocationAnchor
import com.cs22study.seethere.core.NearbyCameraResult
import com.cs22study.seethere.core.StreamSecuritySanitizer
import com.cs22study.seethere.data.ExternalCamSearchClient
import com.cs22study.seethere.data.ExternalSearchCandidate
import com.cs22study.seethere.data.LocalRotatingLogger
import com.cs22study.seethere.data.PreferencesRepository
import com.cs22study.seethere.data.PublicDocumentsStorage
import com.cs22study.seethere.data.db.AppDatabase
import com.cs22study.seethere.data.db.CameraEntity
import com.cs22study.seethere.server.SeeThereLanWebServer
import com.cs22study.seethere.service.SeeThereLanService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class HomeTab {
    FAVORITES,
    NEARBY,
    ADD_IMPORT,
    SETTINGS_LOGS,
    ABOUT,
}

data class UpdateCheckUiState(
    val isChecking: Boolean = false,
    val statusText: String = "尚未檢查線上更新（目前可連線 GitHub Releases 驗證）",
    val latestInfo: AppUpdateInfo? = null,
)

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

    private val _searchKeyword = MutableStateFlow("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    private val _includeLocalSource = MutableStateFlow(true)
    val includeLocalSource: StateFlow<Boolean> = _includeLocalSource.asStateFlow()

    private val _includeTwLiveSource = MutableStateFlow(true)
    val includeTwLiveSource: StateFlow<Boolean> = _includeTwLiveSource.asStateFlow()

    private val _includeYouTubeSource = MutableStateFlow(true)
    val includeYouTubeSource: StateFlow<Boolean> = _includeYouTubeSource.asStateFlow()

    private val _externalSearchResults = MutableStateFlow<List<ExternalSearchCandidate>>(emptyList())
    val externalSearchResults: StateFlow<List<ExternalSearchCandidate>> = _externalSearchResults.asStateFlow()

    private val _isSearchingExternal = MutableStateFlow(false)
    val isSearchingExternal: StateFlow<Boolean> = _isSearchingExternal.asStateFlow()

    private val _quadPageIndex = MutableStateFlow(0)
    val quadPageIndex: StateFlow<Int> = _quadPageIndex.asStateFlow()

    private val _lanServerUrl = MutableStateFlow<String?>(null)
    val lanServerUrl: StateFlow<String?> = _lanServerUrl.asStateFlow()

    private val _lastSettingsNotice = MutableStateFlow("本機日誌正常記錄中（支援檢視、匯出至 Documents 與清除）")
    val lastSettingsNotice: StateFlow<String> = _lastSettingsNotice.asStateFlow()

    private val _updateState = MutableStateFlow(UpdateCheckUiState())
    val updateState: StateFlow<UpdateCheckUiState> = _updateState.asStateFlow()

    val filteredLocalCameras: StateFlow<List<CameraItem>> = combine(
        activeCameras,
        _searchKeyword,
        _includeLocalSource,
    ) { cams, kw, includeLocal ->
        if (!includeLocal) return@combine emptyList()
        val trimmed = kw.trim()
        if (trimmed.isBlank()) emptyList() else cams.filter {
            it.title.contains(trimmed, ignoreCase = true) ||
                it.region.contains(trimmed, ignoreCase = true) ||
                it.city.contains(trimmed, ignoreCase = true) ||
                it.category.contains(trimmed, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun stepCarouselNext() = stepFavoriteCamera(+1)

    fun stepFavoriteCamera(delta: Int) {
        val cams = activeCameras.value
        val favs = cams.filter { it.isFavorite }
        val pool = if (favs.isNotEmpty()) favs else cams
        stepFromPool(pool, delta)
    }

    fun stepFromPool(pool: List<CameraItem>, delta: Int) {
        val target = CameraNavigator.stepCamera(pool, selectedCamera.value?.id, delta) ?: return
        _selectedCameraId.value = target.id
        logger.info("Player", "STEP_CAMERA", "Switched to ${target.id} (${target.title})")
    }

    fun setCorridorStartAnchor(anchorId: String) {
        _corridorStartAnchorId.value = anchorId
    }

    fun setCorridorEndAnchor(anchorId: String) {
        _corridorEndAnchorId.value = anchorId
    }

    fun updateSearchKeyword(keyword: String) {
        _searchKeyword.value = keyword
    }

    fun toggleSearchSource(local: Boolean? = null, twLive: Boolean? = null, youtube: Boolean? = null) {
        local?.let { _includeLocalSource.value = it }
        twLive?.let { _includeTwLiveSource.value = it }
        youtube?.let { _includeYouTubeSource.value = it }
    }

    fun runNearbyAndKeywordSearch() {
        viewModelScope.launch(Dispatchers.IO) {
            _isSearchingExternal.value = true
            try {
                val kw = _searchKeyword.value.trim()
                val settings = prefsRepo.settings.value
                val selectedAnchor = anchors.value.firstOrNull { it.id == settings.selectedAnchorId }
                    ?: anchors.value.firstOrNull()
                var (lat, lon) = _gpsLocation.value ?: if (selectedAnchor != null) {
                    selectedAnchor.latitude to selectedAnchor.longitude
                } else {
                    25.0173 to 121.5397
                }

                if (kw.isNotBlank() && _includeTwLiveSource.value) {
                    ExternalCamSearchClient.geocodePlace(kw)?.let { geocoded ->
                        lat = geocoded.first
                        lon = geocoded.second
                    }
                }

                val combined = ArrayList<ExternalSearchCandidate>()
                if (_includeTwLiveSource.value) {
                    combined.addAll(
                        ExternalCamSearchClient.searchTwLiveNearby(
                            lat = lat,
                            lon = lon,
                            radiusMeters = settings.searchRadiusMeters,
                            keywordFilter = kw,
                        )
                    )
                }
                if (_includeYouTubeSource.value) {
                    val ytQuery = kw.ifBlank { selectedAnchor?.name ?: "台北 即時影像" }
                    combined.addAll(
                        ExternalCamSearchClient.searchYouTubeLive(
                            keyword = ytQuery,
                            anchorLat = lat,
                            anchorLon = lon,
                        )
                    )
                }
                _externalSearchResults.value = combined
                logger.info("Search", "EXTERNAL_SEARCH", "kw='$kw' results=${combined.size}")
                if (combined.isEmpty() && !_includeLocalSource.value) {
                    showBanner("未搜尋到符合的外部即時影像，請嘗試調整關鍵字或半徑")
                }
            } finally {
                _isSearchingExternal.value = false
            }
        }
    }

    fun playOrSaveExternalCandidate(candidate: ExternalSearchCandidate, saveAsFavorite: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val allowLan = prefsRepo.settings.value.allowLocalLanHttp
            val resolved: CameraItem? = if (candidate.twLiveDeviceId != null) {
                ExternalCamSearchClient.resolveTwLiveCamera(
                    deviceId = candidate.twLiveDeviceId,
                    fallbackLat = candidate.latitude,
                    fallbackLon = candidate.longitude,
                    allowPrivateLanHttp = allowLan,
                )
            } else if (!candidate.directStreamUrl.isNullOrBlank()) {
                val check = StreamSecuritySanitizer.sanitizeStreamUrl(candidate.directStreamUrl, allowLan)
                if (check.isAllowed) {
                    CameraItem(
                        id = candidate.id,
                        title = candidate.title,
                        region = "YouTube 搜尋",
                        city = candidate.sourceBadge,
                        category = "直播搜尋",
                        streamUrl = check.normalizedUrl,
                        thumbnailUrl = candidate.thumbnailUrl,
                        latitude = candidate.latitude,
                        longitude = candidate.longitude,
                        sourceName = candidate.sourceBadge,
                        isFavorite = saveAsFavorite,
                        sortOrder = activeCameras.value.size + 1,
                    )
                } else {
                    null
                }
            } else {
                null
            }

            if (resolved == null) {
                showBanner("無法解析該鏡頭串流網址（或遭資安規則攔截）")
                return@launch
            }

            val existing = activeCameras.value.firstOrNull { it.id == resolved.id }
            val toSave = resolved.copy(
                isFavorite = saveAsFavorite || (existing?.isFavorite == true),
                sortOrder = existing?.sortOrder ?: (activeCameras.value.size + 1),
            )
            dao.upsert(CameraEntity.fromDomain(toSave))
            _selectedCameraId.value = toSave.id
            logger.info("Search", "OPEN_CANDIDATE", "Opened ${toSave.id} (${toSave.title}) fav=${toSave.isFavorite}")
            if (saveAsFavorite) {
                showBanner("已收藏並播放：${toSave.title}")
            }
        }
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
            val twLiveId = ExternalSearchParsers.extractTwLiveDeviceIdFromUrl(rawUrl)
            if (twLiveId != null) {
                val resolvedTw = ExternalCamSearchClient.resolveTwLiveCamera(
                    deviceId = twLiveId,
                    allowPrivateLanHttp = allowLan,
                )
                if (resolvedTw == null) {
                    showBanner("無法從 tw.live 解析此攝影機串流")
                    return@launch
                }
                val finalTw = resolvedTw.copy(
                    title = StreamSecuritySanitizer.sanitizeLabel(rawTitle).ifBlank { resolvedTw.title },
                    isFavorite = markFavorite,
                    sortOrder = activeCameras.value.size + 1,
                )
                dao.upsert(CameraEntity.fromDomain(finalTw))
                _selectedCameraId.value = finalTw.id
                logger.info("Camera", "ADD_TWLIVE", "Added ${finalTw.id} (${finalTw.title})")
                showBanner("已解析並新增 tw.live 影像：${finalTw.title}")
                return@launch
            }

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

    fun stepQuadPage(delta: Int) {
        val total = CameraNavigator.totalQuadPages(activeCameras.value, pageSize = 4)
        val next = ((_quadPageIndex.value + delta) % total + total) % total
        _quadPageIndex.value = next
        logger.info("Player", "QUAD_PAGE", "Switched 2x2 quad wall to page ${next + 1}/$total")
    }

    fun exportCsvToDocuments(onCsvReady: (String) -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            val csv = buildExportCsv()
            withContext(Dispatchers.Main) { onCsvReady(csv) }
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val fileName = "seethere-cameras-$stamp.csv"
            PublicDocumentsStorage.writeTextFile(
                context = getApplication(),
                subFolder = "",
                fileName = fileName,
                mimeType = "text/csv",
                content = "\uFEFF$csv",
            ).onSuccess { path ->
                logger.info("Export", "CSV_DOCUMENTS", "Exported CSV to $path")
                _lastSettingsNotice.value = "✅ 已匯出清單至 $path"
                showBanner("已匯出 CSV 至 $path")
            }.onFailure { ex ->
                logger.error("Export", "CSV_FAILED", ex.localizedMessage ?: "unknown")
                showBanner("匯出 CSV 失敗：${ex.localizedMessage}")
            }
        }
    }

    fun startLanServer(portText: String) {
        val port = portText.trim().toIntOrNull()
        if (port == null || !CameraNavigator.isValidServerPort(port)) {
            showBanner("請輸入合法 Port（1024–65535，例如 8080）")
            return
        }
        prefsRepo.setLanServerPort(port)
        viewModelScope.launch(Dispatchers.IO) {
            val listener = object : SeeThereLanWebServer.Listener {
                override fun getActiveCameras(): List<CameraItem> = activeCameras.value

                override fun onLanAddCamera(
                    title: String,
                    url: String,
                    region: String,
                    city: String,
                    favorite: Boolean,
                ): Pair<Boolean, String> = runBlocking {
                    val allowLan = prefsRepo.settings.value.allowLocalLanHttp
                    val twId = ExternalSearchParsers.extractTwLiveDeviceIdFromUrl(url)
                    if (twId != null) {
                        val resolved = ExternalCamSearchClient.resolveTwLiveCamera(twId, allowPrivateLanHttp = allowLan)
                            ?: return@runBlocking false to "無法從 tw.live 解析串流"
                        val finalItem = resolved.copy(
                            title = title.ifBlank { resolved.title },
                            isFavorite = favorite,
                            sortOrder = activeCameras.value.size + 1,
                        )
                        dao.upsert(CameraEntity.fromDomain(finalItem))
                        _selectedCameraId.value = finalItem.id
                        return@runBlocking true to "已新增並播放：${finalItem.title}"
                    }
                    val check = StreamSecuritySanitizer.sanitizeStreamUrl(url, allowLan)
                    if (!check.isAllowed) {
                        return@runBlocking false to (check.blockReason ?: "資安檢查未通過")
                    }
                    var resolvedTitle = title
                    var thumb = if (check.extractedYouTubeId != null) {
                        "https://i.ytimg.com/vi/${check.extractedYouTubeId}/hqdefault.jpg"
                    } else ""
                    if (resolvedTitle.isBlank() && check.extractedYouTubeId != null) {
                        fetchYouTubeOEmbed(check.extractedYouTubeId!!)?.let {
                            resolvedTitle = StreamSecuritySanitizer.sanitizeLabel(it.first)
                            if (it.second.isNotBlank()) thumb = it.second
                        }
                    }
                    if (resolvedTitle.isBlank()) resolvedTitle = "區網鏡頭 ${activeCameras.value.size + 1}"
                    val item = CameraItem(
                        id = "lan_${UUID.randomUUID().toString().take(8)}",
                        title = resolvedTitle,
                        region = region.ifBlank { "區網新增" },
                        city = city.ifBlank { "區網管理" },
                        category = "區網新增",
                        streamUrl = check.normalizedUrl,
                        thumbnailUrl = thumb,
                        latitude = 25.0173,
                        longitude = 121.5397,
                        sourceName = "區網 Web 後台",
                        isFavorite = favorite,
                        sortOrder = activeCameras.value.size + 1,
                        allowLocalHttp = allowLan,
                    )
                    dao.upsert(CameraEntity.fromDomain(item))
                    _selectedCameraId.value = item.id
                    logger.info("LanWeb", "ADD_CAM", "Added ${item.id} (${item.title})")
                    _lastSettingsNotice.value = "🌐 區網新增並播放：${item.title}"
                    showBanner("區網已新增並播放：${item.title}")
                    true to "已新增並播放：${item.title}"
                }

                override fun onLanImportCsv(csv: String): Pair<Int, Int> = runBlocking {
                    val allowLan = prefsRepo.settings.value.allowLocalLanHttp
                    val report = CameraCodec.importFromCsv(csv, allowLan)
                    if (report.accepted.isNotEmpty()) {
                        dao.upsertAll(report.accepted.map { CameraEntity.fromDomain(it) })
                    }
                    logger.info("LanWeb", "CSV_IMPORT", "accepted=${report.accepted.size} rejected=${report.rejected.size}")
                    report.accepted.size to report.rejected.size
                }

                override fun onLanPlayCamera(cameraId: String): Boolean {
                    val target = activeCameras.value.firstOrNull { it.id == cameraId } ?: return false
                    _selectedCameraId.value = target.id
                    logger.info("LanWeb", "PLAY_CAM", "Switched to ${target.id} (${target.title})")
                    return true
                }
            }

            SeeThereLanWebServer.start(port, listener)
                .onSuccess { url ->
                    _lanServerUrl.value = url
                    SeeThereLanService.start(getApplication(), url)
                    logger.info("LanWeb", "START", "LAN Web Server started at $url")
                    _lastSettingsNotice.value = "🌐 區網後台已啟動：$url"
                    showBanner("區網管理已啟動：$url")
                }
                .onFailure { ex ->
                    _lanServerUrl.value = null
                    SeeThereLanService.stop(getApplication())
                    showBanner("啟動失敗 (Port $port 可能被佔用)：${ex.localizedMessage}")
                }
        }
    }

    fun stopLanServer() {
        SeeThereLanWebServer.stop()
        SeeThereLanService.stop(getApplication())
        _lanServerUrl.value = null
        logger.info("LanWeb", "STOP", "LAN Web Server stopped")
        _lastSettingsNotice.value = "區網管理伺服器已停止"
        showBanner("已停止區網管理伺服器")
    }

    fun exportLogsToDocuments() {
        viewModelScope.launch(Dispatchers.IO) {
            logger.exportAllLogsToPublicDocuments()
                .onSuccess { path ->
                    _lastSettingsNotice.value = "✅ 日誌已匯出至：$path"
                    showBanner("日誌已匯出至 $path")
                }
                .onFailure { ex ->
                    _lastSettingsNotice.value = "❌ 匯出日誌失敗：${ex.localizedMessage}"
                    showBanner("匯出日誌失敗：${ex.localizedMessage}")
                }
            refreshLogs(notifyUser = false)
        }
    }

    fun refreshLogs(notifyUser: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val lines = logger.readRecentLines(60)
            _recentLogs.value = lines
            if (notifyUser) {
                _lastSettingsNotice.value = "🔄 已重新載入 ${lines.size} 筆本機日誌"
                showBanner("已重新載入 ${lines.size} 筆本機日誌")
            }
        }
    }

    fun clearLocalLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = logger.clearAllLogs()
            logger.info("Logs", "CLEAR", "Cleared $removed old log files")
            val lines = logger.readRecentLines(60)
            _recentLogs.value = lines
            _lastSettingsNotice.value = "🧹 已清除 $removed 個舊日誌檔，並重新建立今日紀錄"
            showBanner("已清除本機舊日誌（$removed 檔）")
        }
    }

    override fun onCleared() {
        SeeThereLanWebServer.stop()
        SeeThereLanService.stop(getApplication())
        super.onCleared()
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun getInstalledVersionInfo(): Pair<String, Long> {
        val ctx = getApplication<Application>()
        return runCatching {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val vName = info.versionName ?: "0.5.0"
            val vCode = PackageInfoCompat.getLongVersionCode(info)
            vName to vCode
        }.getOrElse { "0.5.0" to 9L }
    }

    fun checkOnlineUpdate() {
        val (currentVersion, _) = getInstalledVersionInfo()
        _updateState.value = _updateState.value.copy(
            isChecking = true,
            statusText = "正在連線 GitHub 檢查最新版本...",
        )
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val url = URL("https://api.github.com/repos/cs22study/seethere/releases?per_page=5")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000
                    readTimeout = 6_000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "SeeThere-Android/$currentVersion")
                }
                val code = conn.responseCode
                if (code !in 200..299) error("HTTP $code")
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                AppUpdateChecker.parseLatestReleaseFromArray(json, currentVersion)
                    ?: error("尚未找到公開 Release 版本資訊")
            }.onSuccess { info ->
                val summary = if (info.hasUpdate) {
                    "🎉 發現新版本 ${info.latestTag}（目前安裝：v$currentVersion）"
                } else {
                    "✅ 目前已是最新版本（安裝 v$currentVersion，線上最新 ${info.latestTag}）"
                }
                _updateState.value = UpdateCheckUiState(
                    isChecking = false,
                    statusText = summary,
                    latestInfo = info,
                )
                logger.info("Update", "CHECK_OK", "current=$currentVersion latest=${info.latestTag} hasUpdate=${info.hasUpdate}")
                showBanner(summary)
            }.onFailure { ex ->
                val errText = "⚠️ 無法取得線上版本（請確認網路連線）：${ex.localizedMessage ?: "未知錯誤"}"
                _updateState.value = _updateState.value.copy(
                    isChecking = false,
                    statusText = errText,
                )
                logger.warn("Update", "CHECK_FAIL", ex.localizedMessage ?: "error")
                showBanner(errText)
            }
        }
    }

    private suspend fun fetchYouTubeOEmbed(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 4_000
                readTimeout = 4_000
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
