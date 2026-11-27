package com.cs22study.seethere.ui

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cs22study.seethere.core.CameraItem
import com.cs22study.seethere.core.CorridorCameraResult
import com.cs22study.seethere.core.GeoSpatialEngine
import com.cs22study.seethere.core.LocationAnchor
import com.cs22study.seethere.core.NearbyCameraResult
import com.cs22study.seethere.data.ExternalSearchCandidate
import com.cs22study.seethere.data.UserSettings
import com.cs22study.seethere.service.CapturedScreenshot
import com.cs22study.seethere.service.ScreenCaptureBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeeThereHomeScreen(
    vm: SeeThereViewModel,
    isTvMode: Boolean,
    hasLocationPermission: Boolean,
    onRequestLocation: () -> Unit,
    onTriggerScreenshot: (CameraItem?, PlayerViewMode) -> Unit,
    onExitApp: () -> Unit,
) {
    val activeCameras by vm.activeCameras.collectAsState()
    val deletedCameras by vm.deletedCameras.collectAsState()
    val anchors by vm.anchors.collectAsState()
    val nearbyCameras by vm.nearbyCameras.collectAsState()
    val corridorCameras by vm.corridorCameras.collectAsState()
    val corridorStartId by vm.corridorStartAnchorId.collectAsState()
    val corridorEndId by vm.corridorEndAnchorId.collectAsState()
    val selectedCamera by vm.selectedCamera.collectAsState()
    val settings by vm.prefsRepo.settings.collectAsState()
    val bannerMessage by vm.bannerMessage.collectAsState()
    val recentLogs by vm.recentLogs.collectAsState()
    val latestScreenshot by ScreenCaptureBus.latest.collectAsState()
    val captureError by ScreenCaptureBus.error.collectAsState()

    var currentTab by remember { mutableStateOf(HomeTab.FAVORITES) }
    var playerViewMode by remember { mutableStateOf(PlayerViewMode.SINGLE) }
    var isFullscreen by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val useSideBySideLayout = isTvMode ||
        configuration.screenWidthDp >= 680 ||
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // Auto-dismiss screenshot toast after 2 seconds so it does not cover the viewport
    LaunchedEffect(latestScreenshot) {
        if (latestScreenshot != null) {
            delay(2_000L)
            ScreenCaptureBus.clearPreview()
        }
    }

    // 30-second Scenic Carousel auto-cycle
    LaunchedEffect(playerViewMode) {
        if (playerViewMode == PlayerViewMode.CAROUSEL_30S) {
            while (isActive) {
                delay(30_000L)
                vm.stepCarouselNext()
            }
        }
    }

    LaunchedEffect(bannerMessage, captureError) {
        val msg = captureError ?: bannerMessage
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            vm.clearBanner()
            ScreenCaptureBus.clearError()
        }
    }

    val drawerMenuContent: @Composable () -> Unit = {
        ModalDrawerSheet(
            drawerContainerColor = Color(0xFF0F172A),
            modifier = Modifier.width(280.dp),
        ) {
            Column(modifier = Modifier.fillMaxHeight().padding(12.dp)) {
                Text(
                    "SeeThere 到處看看",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(12.dp),
                )
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(Modifier.height(8.dp))

                HomeMenuItem("我的最愛清單", Icons.Default.Star, currentTab == HomeTab.FAVORITES) {
                    currentTab = HomeTab.FAVORITES
                    scope.launch { drawerState.close() }
                }
                HomeMenuItem("附近地點影像", Icons.Default.LocationOn, currentTab == HomeTab.NEARBY) {
                    currentTab = HomeTab.NEARBY
                    scope.launch { drawerState.close() }
                }
                HomeMenuItem("新增與匯入影像", Icons.Default.AddCircleOutline, currentTab == HomeTab.ADD_IMPORT) {
                    currentTab = HomeTab.ADD_IMPORT
                    scope.launch { drawerState.close() }
                }
                HomeMenuItem("設定與本機日誌", Icons.Default.Settings, currentTab == HomeTab.SETTINGS_LOGS) {
                    vm.refreshLogs()
                    currentTab = HomeTab.SETTINGS_LOGS
                    scope.launch { drawerState.close() }
                }
                HomeMenuItem("首頁畫面截圖", Icons.Default.CameraAlt, false) {
                    scope.launch { drawerState.close() }
                    onTriggerScreenshot(selectedCamera, playerViewMode)
                }

                Spacer(Modifier.weight(1f))
                HorizontalDivider(color = Color(0xFF334155))
                Spacer(Modifier.height(8.dp))

                // Exit App button pinned at the very bottom of the home menu
                Button(
                    onClick = {
                        vm.logger.info("App", "EXIT", "User tapped Exit App button")
                        onExitApp()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFDC2626),
                        contentColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出 APP")
                    Spacer(Modifier.width(8.dp))
                    Text("退出 APP", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !isFullscreen,
        drawerContent = drawerMenuContent,
    ) {
        Scaffold(
            containerColor = Color(0xFF090D16),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (!isFullscreen) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFF0F172A),
                            titleContentColor = Color.White,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "功能表", tint = Color.White)
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    "SeeThere 到處看看",
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    selectedCamera?.title ?: "無廣告高資安即時影像",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        actions = {
                            // Home-screen one-tap Screenshot button (accessible by touch & TV D-Pad)
                            Button(
                                onClick = { onTriggerScreenshot(selectedCamera, playerViewMode) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFACC15),
                                    contentColor = Color(0xFF0F172A),
                                ),
                                modifier = Modifier.padding(end = 8.dp),
                            ) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "截圖", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("截圖", fontWeight = FontWeight.Bold)
                            }
                        },
                    )
                }
            },
            bottomBar = {
                if (!isTvMode && !isFullscreen) {
                    NavigationBar(containerColor = Color(0xFF0F172A)) {
                        NavigationBarItem(
                            selected = currentTab == HomeTab.FAVORITES,
                            onClick = { currentTab = HomeTab.FAVORITES },
                            icon = { Icon(Icons.Default.Star, contentDescription = "最愛") },
                            label = { Text("最愛") },
                        )
                        NavigationBarItem(
                            selected = currentTab == HomeTab.NEARBY,
                            onClick = { currentTab = HomeTab.NEARBY },
                            icon = { Icon(Icons.Default.LocationOn, contentDescription = "附近") },
                            label = { Text("附近") },
                        )
                        NavigationBarItem(
                            selected = currentTab == HomeTab.ADD_IMPORT,
                            onClick = { currentTab = HomeTab.ADD_IMPORT },
                            icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = "匯入") },
                            label = { Text("新增匯入") },
                        )
                        NavigationBarItem(
                            selected = currentTab == HomeTab.SETTINGS_LOGS,
                            onClick = {
                                vm.refreshLogs()
                                currentTab = HomeTab.SETTINGS_LOGS
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                            label = { Text("設定日誌") },
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                if (isFullscreen) {
                    FullscreenPlayerOverlay(
                        camera = selectedCamera,
                        trafficSaverMode = settings.trafficSaverMode,
                        onExitFullscreen = { isFullscreen = false },
                        onScreenshot = { onTriggerScreenshot(selectedCamera, PlayerViewMode.SINGLE) },
                        onSwitchBackup = { vm.selectBackupFor(it) },
                        onLogEvent = { evt, msg -> vm.logger.warn("Player", evt, msg) },
                    )
                } else if (useSideBySideLayout) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (isTvMode) {
                            NavigationRail(
                                containerColor = Color(0xFF0F172A),
                                modifier = Modifier.fillMaxHeight(),
                            ) {
                                NavigationRailItem(
                                    selected = currentTab == HomeTab.FAVORITES,
                                    onClick = { currentTab = HomeTab.FAVORITES },
                                    icon = { Icon(Icons.Default.Star, contentDescription = "最愛") },
                                    label = { Text("最愛") },
                                )
                                NavigationRailItem(
                                    selected = currentTab == HomeTab.NEARBY,
                                    onClick = { currentTab = HomeTab.NEARBY },
                                    icon = { Icon(Icons.Default.LocationOn, contentDescription = "附近") },
                                    label = { Text("附近") },
                                )
                                NavigationRailItem(
                                    selected = currentTab == HomeTab.ADD_IMPORT,
                                    onClick = { currentTab = HomeTab.ADD_IMPORT },
                                    icon = { Icon(Icons.Default.AddCircleOutline, contentDescription = "匯入") },
                                    label = { Text("匯入") },
                                )
                                NavigationRailItem(
                                    selected = currentTab == HomeTab.SETTINGS_LOGS,
                                    onClick = {
                                        vm.refreshLogs()
                                        currentTab = HomeTab.SETTINGS_LOGS
                                    },
                                    icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                                    label = { Text("設定") },
                                )
                                Spacer(Modifier.weight(1f))
                                NavigationRailItem(
                                    selected = false,
                                    onClick = {
                                        vm.logger.info("App", "EXIT", "TV Rail Exit App clicked")
                                        onExitApp()
                                    },
                                    icon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出", tint = Color(0xFFF87171)) },
                                    label = { Text("退出", color = Color(0xFFF87171)) },
                                )
                            }
                        }

                        Row(modifier = Modifier.fillMaxSize().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                TabContentBody(
                                    tab = currentTab,
                                    activeCameras = activeCameras,
                                    deletedCameras = deletedCameras,
                                    nearbyCameras = nearbyCameras,
                                    corridorCameras = corridorCameras,
                                    corridorStartId = corridorStartId,
                                    corridorEndId = corridorEndId,
                                    anchors = anchors,
                                    selectedCamera = selectedCamera,
                                    settings = settings,
                                    recentLogs = recentLogs,
                                    hasLocationPermission = hasLocationPermission,
                                    vm = vm,
                                    onRequestLocation = onRequestLocation,
                                    onExitApp = onExitApp,
                                )
                            }
                            Column(modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                                PlayerModeBar(
                                    viewMode = playerViewMode,
                                    onSelectMode = { playerViewMode = it },
                                )
                                Spacer(Modifier.height(6.dp))
                                PlayerViewportSection(
                                    viewMode = playerViewMode,
                                    selectedCamera = selectedCamera,
                                    activeCameras = activeCameras,
                                    trafficSaverMode = settings.trafficSaverMode,
                                    onSelectAndFullscreen = {
                                        vm.selectCamera(it)
                                        isFullscreen = true
                                    },
                                    onToggleFullscreen = { isFullscreen = true },
                                    onSwitchBackup = { vm.selectBackupFor(it) },
                                    onLogEvent = { evt, msg -> vm.logger.warn("Player", evt, msg) },
                                )
                            }
                        }
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 6.dp)) {
                        PlayerModeBar(
                            viewMode = playerViewMode,
                            onSelectMode = { playerViewMode = it },
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                            PlayerViewportSection(
                                viewMode = playerViewMode,
                                selectedCamera = selectedCamera,
                                activeCameras = activeCameras,
                                trafficSaverMode = settings.trafficSaverMode,
                                onSelectAndFullscreen = {
                                    vm.selectCamera(it)
                                    isFullscreen = true
                                },
                                onToggleFullscreen = { isFullscreen = true },
                                onSwitchBackup = { vm.selectBackupFor(it) },
                                onLogEvent = { evt, msg -> vm.logger.warn("Player", evt, msg) },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            TabContentBody(
                                tab = currentTab,
                                activeCameras = activeCameras,
                                deletedCameras = deletedCameras,
                                nearbyCameras = nearbyCameras,
                                corridorCameras = corridorCameras,
                                corridorStartId = corridorStartId,
                                corridorEndId = corridorEndId,
                                anchors = anchors,
                                selectedCamera = selectedCamera,
                                settings = settings,
                                recentLogs = recentLogs,
                                hasLocationPermission = hasLocationPermission,
                                vm = vm,
                                onRequestLocation = onRequestLocation,
                                onExitApp = onExitApp,
                            )
                        }
                    }
                }

                latestScreenshot?.let { shot ->
                    ScreenshotPreviewBanner(
                        screenshot = shot,
                        onDismiss = { ScreenCaptureBus.clearPreview() },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMenuItem(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = { Text(label, color = if (selected) Color(0xFF0F172A) else Color.White) },
        icon = { Icon(icon, contentDescription = label, tint = if (selected) Color(0xFF0F172A) else Color(0xFF38BDF8)) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun PlayerModeBar(
    viewMode: PlayerViewMode,
    onSelectMode: (PlayerViewMode) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = viewMode == PlayerViewMode.SINGLE,
                onClick = { onSelectMode(PlayerViewMode.SINGLE) },
                label = { Text("單鏡模式") },
            )
        }
        item {
            FilterChip(
                selected = viewMode == PlayerViewMode.QUAD_2X2,
                onClick = { onSelectMode(PlayerViewMode.QUAD_2X2) },
                label = { Text("2×2 四宮格") },
            )
        }
        item {
            FilterChip(
                selected = viewMode == PlayerViewMode.CAROUSEL_30S,
                onClick = { onSelectMode(PlayerViewMode.CAROUSEL_30S) },
                label = { Text("電子風景窗 (30秒輪播)") },
            )
        }
    }
}

@Composable
private fun PlayerViewportSection(
    viewMode: PlayerViewMode,
    selectedCamera: CameraItem?,
    activeCameras: List<CameraItem>,
    trafficSaverMode: Boolean,
    onSelectAndFullscreen: (CameraItem) -> Unit,
    onToggleFullscreen: () -> Unit,
    onSwitchBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
) {
    if (viewMode == PlayerViewMode.QUAD_2X2) {
        val quadList = remember(activeCameras) {
            val favs = activeCameras.filter { it.isFavorite }
            (favs + activeCameras.filterNot { it.isFavorite }).take(4)
        }
        QuadWallGrid(
            cameras = quadList,
            trafficSaverMode = trafficSaverMode,
            onFullscreenCamera = onSelectAndFullscreen,
            onSwitchBackup = onSwitchBackup,
            onLogEvent = onLogEvent,
        )
    } else {
        CompactPlayerCard(
            camera = selectedCamera,
            trafficSaverMode = trafficSaverMode,
            onToggleFullscreen = onToggleFullscreen,
            onSwitchBackup = onSwitchBackup,
            onLogEvent = onLogEvent,
        )
    }
}

@Composable
private fun QuadWallGrid(
    cameras: List<CameraItem>,
    trafficSaverMode: Boolean,
    onFullscreenCamera: (CameraItem) -> Unit,
    onSwitchBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(4.dp)) {
            for (rowIdx in 0..1) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (colIdx in 0..1) {
                        val cam = cameras.getOrNull(rowIdx * 2 + colIdx)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(16f / 9f)
                                .clipToBounds()
                                .background(Color.Black),
                        ) {
                            StreamPlayerBox(
                                camera = cam,
                                trafficSaverMode = trafficSaverMode,
                                onSwitchToBackup = onSwitchBackup,
                                onLogEvent = onLogEvent,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (cam != null) {
                                // Clickable header + expand button above AndroidView so touch/D-Pad triggers fullscreen reliably
                                Row(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .fillMaxWidth()
                                        .zIndex(2f)
                                        .background(Color(0xAA0F172A))
                                        .clickable { onFullscreenCamera(cam) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(
                                        text = cam.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.Default.Fullscreen,
                                        contentDescription = "全螢幕",
                                        tint = Color(0xFFFACC15),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPlayerCard(
    camera: CameraItem?,
    trafficSaverMode: Boolean,
    onToggleFullscreen: () -> Unit,
    onSwitchBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = BorderStroke(1.dp, Color(0xFF1E293B)),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
            ) {
                StreamPlayerBox(
                    camera = camera,
                    trafficSaverMode = trafficSaverMode,
                    onSwitchToBackup = onSwitchBackup,
                    onLogEvent = onLogEvent,
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = onToggleFullscreen,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x990F172A), RoundedCornerShape(8.dp)),
                ) {
                    Icon(Icons.Default.Fullscreen, contentDescription = "全螢幕", tint = Color.White)
                }
            }
            if (camera != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${camera.region} · ${camera.title}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        camera.sourceName,
                        color = Color(0xFF38BDF8),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenPlayerOverlay(
    camera: CameraItem?,
    trafficSaverMode: Boolean,
    onExitFullscreen: () -> Unit,
    onScreenshot: () -> Unit,
    onSwitchBackup: (CameraItem) -> Unit,
    onLogEvent: (String, String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        StreamPlayerBox(
            camera = camera,
            trafficSaverMode = trafficSaverMode,
            onSwitchToBackup = onSwitchBackup,
            onLogEvent = onLogEvent,
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onScreenshot,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFACC15), contentColor = Color(0xFF0F172A)),
            ) {
                Icon(Icons.Default.CameraAlt, contentDescription = "截圖")
                Spacer(Modifier.width(4.dp))
                Text("截圖")
            }
            Button(onClick = onExitFullscreen) {
                Icon(Icons.Default.FullscreenExit, contentDescription = "退出全螢幕")
                Spacer(Modifier.width(4.dp))
                Text("退出全螢幕")
            }
        }
    }
}

@Composable
private fun TabContentBody(
    tab: HomeTab,
    activeCameras: List<CameraItem>,
    deletedCameras: List<CameraItem>,
    nearbyCameras: List<NearbyCameraResult>,
    corridorCameras: List<CorridorCameraResult>,
    corridorStartId: String,
    corridorEndId: String,
    anchors: List<LocationAnchor>,
    selectedCamera: CameraItem?,
    settings: UserSettings,
    recentLogs: List<String>,
    hasLocationPermission: Boolean,
    vm: SeeThereViewModel,
    onRequestLocation: () -> Unit,
    onExitApp: () -> Unit,
) {
    when (tab) {
        HomeTab.FAVORITES -> FavoritesTabContent(
            activeCameras = activeCameras,
            selectedCameraId = selectedCamera?.id,
            onSelectCamera = { vm.selectCamera(it) },
            onToggleFavorite = { vm.toggleFavorite(it) },
            onSoftDelete = { vm.softDeleteCamera(it) },
            onExitApp = onExitApp,
        )
        HomeTab.NEARBY -> NearbyTabContent(
            nearbyCameras = nearbyCameras,
            corridorCameras = corridorCameras,
            corridorStartId = corridorStartId,
            corridorEndId = corridorEndId,
            anchors = anchors,
            settings = settings,
            selectedCameraId = selectedCamera?.id,
            hasLocationPermission = hasLocationPermission,
            vm = vm,
            onUseGps = {
                if (hasLocationPermission) {
                    vm.refreshGpsLocation(true)
                } else {
                    onRequestLocation()
                }
            },
            onSelectAnchor = { vm.clearGpsOverrideAndUseAnchor(it) },
            onSelectRadius = { vm.prefsRepo.setSearchRadiusMeters(it) },
            onSelectCorridorStart = { vm.setCorridorStartAnchor(it) },
            onSelectCorridorEnd = { vm.setCorridorEndAnchor(it) },
            onSelectCamera = { vm.selectCamera(it) },
            onToggleFavorite = { vm.toggleFavorite(it) },
        )
        HomeTab.ADD_IMPORT -> AddImportTabContent(
            deletedCameras = deletedCameras,
            onAddSingle = { title, url, region, city, lat, lon, fav ->
                vm.addSingleCamera(title, url, region, city, lat, lon, fav)
            },
            onImportCsv = { vm.importCsvText(it) },
            onExportCsv = { vm.buildExportCsv() },
            onRestore = { vm.restoreCamera(it) },
            onPurge = { vm.purgeCamera(it) },
        )
        HomeTab.SETTINGS_LOGS -> SettingsLogsTabContent(
            settings = settings,
            recentLogs = recentLogs,
            onToggleCornerLabel = { vm.prefsRepo.setScreenshotCornerLabelEnabled(it) },
            onToggleLanHttp = { vm.prefsRepo.setAllowLocalLanHttp(it) },
            onToggleTrafficSaver = { vm.prefsRepo.setTrafficSaverMode(it) },
            onExportLogs = { vm.exportLogsToDocuments() },
            onRefreshLogs = { vm.refreshLogs() },
            onExitApp = onExitApp,
        )
    }
}

@Composable
private fun FavoritesTabContent(
    activeCameras: List<CameraItem>,
    selectedCameraId: String?,
    onSelectCamera: (CameraItem) -> Unit,
    onToggleFavorite: (CameraItem) -> Unit,
    onSoftDelete: (CameraItem) -> Unit,
    onExitApp: () -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val favorites = remember(activeCameras) { activeCameras.filter { it.isFavorite } }
    val displayed = if (showAll || favorites.isEmpty()) activeCameras else favorites

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = !showAll,
                    onClick = { showAll = false },
                    label = { Text("我的最愛 (${favorites.size})") },
                )
                FilterChip(
                    selected = showAll,
                    onClick = { showAll = true },
                    label = { Text("全部示範清單 (${activeCameras.size})") },
                )
            }
        }
        items(displayed, key = { it.id }) { cam ->
            CameraListCard(
                camera = cam,
                subtitle = "${cam.region} · ${cam.city} · ${cam.category}",
                isSelected = cam.id == selectedCameraId,
                onClick = { onSelectCamera(cam) },
                onToggleFavorite = { onToggleFavorite(cam) },
                onSoftDelete = { onSoftDelete(cam) },
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onExitApp,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF87171)),
                border = BorderStroke(1.dp, Color(0xFFDC2626)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出 APP")
                Spacer(Modifier.width(8.dp))
                Text("退出 APP（停止串流並關閉）")
            }
        }
    }
}

@Composable
private fun NearbyTabContent(
    nearbyCameras: List<NearbyCameraResult>,
    corridorCameras: List<CorridorCameraResult>,
    corridorStartId: String,
    corridorEndId: String,
    anchors: List<LocationAnchor>,
    settings: UserSettings,
    selectedCameraId: String?,
    hasLocationPermission: Boolean,
    vm: SeeThereViewModel,
    onUseGps: () -> Unit,
    onSelectAnchor: (String) -> Unit,
    onSelectRadius: (Int) -> Unit,
    onSelectCorridorStart: (String) -> Unit,
    onSelectCorridorEnd: (String) -> Unit,
    onSelectCamera: (CameraItem) -> Unit,
    onToggleFavorite: (CameraItem) -> Unit,
) {
    var corridorMode by remember { mutableStateOf(false) }
    val searchKeyword by vm.searchKeyword.collectAsState()
    val includeLocal by vm.includeLocalSource.collectAsState()
    val includeTwLive by vm.includeTwLiveSource.collectAsState()
    val includeYouTube by vm.includeYouTubeSource.collectAsState()
    val filteredLocal by vm.filteredLocalCameras.collectAsState()
    val externalResults by vm.externalSearchResults.collectAsState()
    val isSearching by vm.isSearchingExternal.collectAsState()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            // Keyword search + external source toggles (tw.live + YouTube Live)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = searchKeyword,
                    onValueChange = { vm.updateSearchKeyword(it) },
                    label = { Text("關鍵字/地點 (例: 台大、淡水、東京)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { vm.runNearbyAndKeywordSearch() },
                    enabled = !isSearching,
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Search, contentDescription = "搜尋", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("搜尋")
                }
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = includeLocal,
                        onClick = { vm.toggleSearchSource(local = !includeLocal) },
                        label = { Text("本機清單") },
                    )
                }
                item {
                    FilterChip(
                        selected = includeTwLive,
                        onClick = { vm.toggleSearchSource(twLive = !includeTwLive) },
                        label = { Text("tw.live 來源") },
                    )
                }
                item {
                    FilterChip(
                        selected = includeYouTube,
                        onClick = { vm.toggleSearchSource(youtube = !includeYouTube) },
                        label = { Text("YouTube 來源") },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !corridorMode,
                    onClick = { corridorMode = false },
                    label = { Text("附近半徑搜尋") },
                )
                FilterChip(
                    selected = corridorMode,
                    onClick = { corridorMode = true },
                    label = { Text("起迄路線沿途搜尋") },
                )
            }
            Spacer(Modifier.height(6.dp))

            if (!corridorMode) {
                Text("搜尋基準點（手機可用 GPS；電視盒可直接點錨點）", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = false,
                            onClick = onUseGps,
                            label = { Text(if (hasLocationPermission) "目前 GPS 定位" else "授權 GPS") },
                        )
                    }
                    items(anchors, key = { it.id }) { anchor ->
                        FilterChip(
                            selected = settings.selectedAnchorId == anchor.id,
                            onClick = { onSelectAnchor(anchor.id) },
                            label = { Text(anchor.name) },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1_000, 3_000, 10_000, 30_000).forEach { radius ->
                        item {
                            FilterChip(
                                selected = settings.searchRadiusMeters == radius,
                                onClick = { onSelectRadius(radius) },
                                label = { Text("半徑 ${radius / 1000} km") },
                            )
                        }
                    }
                }
            } else {
                Text("起點錨點：", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(anchors, key = { "s_${it.id}" }) { anchor ->
                        FilterChip(
                            selected = corridorStartId == anchor.id,
                            onClick = { onSelectCorridorStart(anchor.id) },
                            label = { Text(anchor.name) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("終點錨點：", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(anchors, key = { "e_${it.id}" }) { anchor ->
                        FilterChip(
                            selected = corridorEndId == anchor.id,
                            onClick = { onSelectCorridorEnd(anchor.id) },
                            label = { Text(anchor.name) },
                        )
                    }
                }
            }
        }

        if (filteredLocal.isNotEmpty()) {
            item {
                Text("本機關鍵字符合 (${filteredLocal.size})", color = Color(0xFF38BDF8), style = MaterialTheme.typography.labelMedium)
            }
            items(filteredLocal, key = { "kw_${it.id}" }) { cam ->
                CameraListCard(
                    camera = cam,
                    subtitle = "${cam.region} · ${cam.city} · ${cam.sourceName}",
                    isSelected = cam.id == selectedCameraId,
                    onClick = { onSelectCamera(cam) },
                    onToggleFavorite = { onToggleFavorite(cam) },
                    onSoftDelete = null,
                )
            }
        }

        if (externalResults.isNotEmpty()) {
            item {
                Text("線上即時搜尋結果：tw.live / YouTube (${externalResults.size})", color = Color(0xFFFACC15), style = MaterialTheme.typography.labelMedium)
            }
            items(externalResults, key = { it.id }) { cand ->
                ExternalCandidateCard(
                    candidate = cand,
                    onPlay = { vm.playOrSaveExternalCandidate(cand, saveAsFavorite = false) },
                    onSaveFavorite = { vm.playOrSaveExternalCandidate(cand, saveAsFavorite = true) },
                )
            }
        }

        if (!corridorMode && includeLocal) {
            if (nearbyCameras.isEmpty() && externalResults.isEmpty()) {
                item {
                    Text(
                        "目前半徑內無本機鏡頭，可點擊上方「搜尋」查詢 tw.live / YouTube，或放大半徑/切換錨點",
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(nearbyCameras, key = { it.camera.id }) { result ->
                val dist = GeoSpatialEngine.formatDistance(result.distanceMeters)
                CameraListCard(
                    camera = result.camera,
                    subtitle = "距離約 $dist · ${result.camera.city}",
                    isSelected = result.camera.id == selectedCameraId,
                    onClick = { onSelectCamera(result.camera) },
                    onToggleFavorite = { onToggleFavorite(result.camera) },
                    onSoftDelete = null,
                )
            }
        } else if (corridorMode) {
            if (corridorCameras.isEmpty()) {
                item {
                    Text(
                        "請選擇不同起終點錨點以搜尋沿線 5 公里內鏡頭",
                        color = Color(0xFFFCA5A5),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            items(corridorCameras, key = { "c_${it.camera.id}" }) { result ->
                val dist = GeoSpatialEngine.formatDistance(result.distanceToRouteMeters)
                val pct = (result.progressFraction * 100).toInt()
                CameraListCard(
                    camera = result.camera,
                    subtitle = "路線進度 $pct% · 距路線 $dist · ${result.camera.city}",
                    isSelected = result.camera.id == selectedCameraId,
                    onClick = { onSelectCamera(result.camera) },
                    onToggleFavorite = { onToggleFavorite(result.camera) },
                    onSoftDelete = null,
                )
            }
        }
    }
}

@Composable
private fun ExternalCandidateCard(
    candidate: ExternalSearchCandidate,
    onPlay: () -> Unit,
    onSaveFavorite: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF162033)),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "[${candidate.sourceBadge}] ${candidate.title}",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(candidate.subtitle, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onPlay) {
                Text("播放", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onSaveFavorite) {
                Icon(Icons.Default.FavoriteBorder, contentDescription = "收藏", tint = Color(0xFFFACC15))
            }
        }
    }
}

@Composable
private fun AddImportTabContent(
    deletedCameras: List<CameraItem>,
    onAddSingle: (String, String, String, String, String, String, Boolean) -> Unit,
    onImportCsv: (String) -> Unit,
    onExportCsv: () -> String,
    onRestore: (CameraItem) -> Unit,
    onPurge: (CameraItem) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("自訂") }
    var city by remember { mutableStateOf("台北市") }
    var lat by remember { mutableStateOf("25.0173") }
    var lon by remember { mutableStateOf("121.5397") }
    var fav by remember { mutableStateOf(true) }
    var csvBox by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("單筆新增即時影像（支援 .m3u8 / MJPEG / YouTube / tw.live/cam/?id=）", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("串流、YouTube 或 tw.live 網址 (HTTPS)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("鏡頭名稱（YouTube 可留空自動抓取）") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = region, onValueChange = { region = it }, label = { Text("區域") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("城市") }, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("緯度") }, modifier = Modifier.weight(1f))
            OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("經度") }, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = fav, onCheckedChange = { fav = it })
            Spacer(Modifier.width(8.dp))
            Text("加入我的最愛", color = Color.White)
        }
        Button(
            onClick = { onAddSingle(title, url, region, city, lat, lon, fav) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("資安檢查並新增")
        }

        HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 6.dp))
        Text("批次 CSV 匯入 / 匯出（含 XSS / SSRF / 公式注入防護）", color = Color(0xFF38BDF8), fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = csvBox,
            onValueChange = { csvBox = it },
            label = { Text("貼上 CSV 內容") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onImportCsv(csvBox) }, modifier = Modifier.weight(1f)) {
                Text("匯入 CSV")
            }
            OutlinedButton(onClick = { csvBox = onExportCsv() }, modifier = Modifier.weight(1f)) {
                Text("產出現有清單 CSV")
            }
        }

        if (deletedCameras.isNotEmpty()) {
            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 6.dp))
            Text("軟刪除回收桶 (${deletedCameras.size})", color = Color(0xFFFACC15), fontWeight = FontWeight.Bold)
            deletedCameras.forEach { cam ->
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(cam.title, color = Color.White)
                            Text("刪除時間：${cam.deletedAtIso ?: "--"}", color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { onRestore(cam) }) {
                            Icon(Icons.Default.Restore, contentDescription = "復原", tint = Color(0xFF4ADE80))
                        }
                        IconButton(onClick = { onPurge(cam) }) {
                            Icon(Icons.Default.Delete, contentDescription = "永久刪除", tint = Color(0xFFF87171))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLogsTabContent(
    settings: UserSettings,
    recentLogs: List<String>,
    onToggleCornerLabel: (Boolean) -> Unit,
    onToggleLanHttp: (Boolean) -> Unit,
    onToggleTrafficSaver: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onRefreshLogs: () -> Unit,
    onExitApp: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingSwitchRow(
            title = "截圖角落標註攝影機名稱＋時間",
            subtitle = "開啟後將於首頁截圖左下角自動標註當前鏡頭與拍攝時刻",
            checked = settings.screenshotCornerLabelEnabled,
            onCheckedChange = onToggleCornerLabel,
        )
        SettingSwitchRow(
            title = "省流量縮圖優先模式",
            subtitle = "先顯示低頻寬 JPG 縮圖，手動點擊後才連線影片串流",
            checked = settings.trafficSaverMode,
            onCheckedChange = onToggleTrafficSaver,
        )
        SettingSwitchRow(
            title = "允許自家區網攝影機 (LAN HTTP)",
            subtitle = "預設封鎖內網 IP 防範 SSRF；僅於串接家用區網攝影機時開啟",
            checked = settings.allowLocalLanHttp,
            onCheckedChange = onToggleLanHttp,
        )

        HorizontalDivider(color = Color(0xFF334155))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onExportLogs, modifier = Modifier.weight(1f)) {
                Text("匯出本機記錄檔 (.jsonl)")
            }
            OutlinedButton(onClick = onRefreshLogs, modifier = Modifier.weight(1f)) {
                Text("重新載入日誌")
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("最近本機紀錄（已過濾敏感字串，14 天滾動留存）", color = Color(0xFF38BDF8), style = MaterialTheme.typography.labelMedium)
                recentLogs.take(20).forEach { line ->
                    Text(line, color = Color(0xFFCBD5E1), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Button(
            onClick = onExitApp,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "退出 APP")
            Spacer(Modifier.width(8.dp))
            Text("退出 APP", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun CameraListCard(
    camera: CameraItem,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSoftDelete: (() -> Unit)?,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) Color(0xFF1E3A5F) else Color(0xFF111827),
        ),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) Color(0xFFFACC15) else Color(0xFF1E293B)),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(camera.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Color(0xFF94A3B8), style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (camera.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "收藏",
                    tint = if (camera.isFavorite) Color(0xFFFACC15) else Color(0xFF64748B),
                )
            }
            if (onSoftDelete != null) {
                IconButton(onClick = onSoftDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "軟刪除", tint = Color(0xFF94A3B8))
                }
            }
        }
    }
}

@Composable
private fun ScreenshotPreviewBanner(
    screenshot: CapturedScreenshot,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xE61E293B)),
        border = BorderStroke(1.dp, Color(0xFFFACC15)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(
                bitmap = screenshot.thumbnail.asImageBitmap(),
                contentDescription = "截圖預覽",
                modifier = Modifier.size(48.dp, 28.dp),
            )
            Text(
                "已截圖：${screenshot.fileName}",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(screenshot.uri, "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(viewIntent) }
            }) {
                Text("檢視", style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onDismiss) {
                Text("關閉", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
