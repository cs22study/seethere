# SeeThere 到處看看 APP — Spoke 6 進階看圖模式與動態資料源實作紀錄 (`v0.2.0`)

> **版本**：`v0.2.0` (`versionCode = 2`)  
> **前置測試回饋**：已於 BlueStacks 完成 `v0.1.0` 初步實測（架構、截圖、退出 APP 運作正常），本版依回饋優化截圖通知體驗並推進 Spoke 6。

---

## 1. BlueStacks 實測回饋修正

1. **截圖後提醒縮短為 2 秒自動消失**：
   - 於 [HomeScreen.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt) 新增 `LaunchedEffect(latestScreenshot)`，顯示截圖預覽橫幅 **2,000 ms** 後自動清除 `ScreenCaptureBus.clearPreview()`。
   - 同步將 `ScreenshotPreviewBanner` 精簡為單行低高度懸浮列，避免遮擋下方攝影機清單或播放區。
2. **修正 `Icons.AutoMirrored.Filled.ExitToApp` 棄用警告**，達成零 Kotlin 編譯警告。
3. **Android 7.1.1 相容補強**：[AndroidManifest.xml](../app/src/main/AndroidManifest.xml) 加入 `android:extractNativeLibs="true"`，並於 [app/build.gradle.kts](../app/build.gradle.kts) 加入持久化 Release 簽章支援。

---

## 2. Spoke 6 新增功能與演算法實作

### 2.1 電視牆「2×2 四宮格」與「電子風景窗 (30 秒輪播)」
- 於 [SeeThereViewModel.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/SeeThereViewModel.kt) 新增 `PlayerViewMode`（`SINGLE` / `QUAD_2X2` / `CAROUSEL_30S`）與 `stepCarouselNext()`。
- **2×2 四宮格**：同時展示前 4 支精選／我的最愛即時影像，點擊任一格子即切換為單鏡放大檢視；離開四宮格時 Compose `DisposableEffect` 自動釋放全部播放器資源。
- **電子風景窗**：每 30 秒自動輪播收藏清單中的下一支即時影像，適合小米電視盒子作為客廳風景牆。

### 2.2 水利署防災影像動態 API 解析 (`wraDisasterCctv`)
- `:core-stream` 新增 [WraDisasterParser.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/WraDisasterParser.kt)，嚴格白名單鎖定 `https://fmg.wra.gov.tw` 與 `https://web.wra.gov.tw`。
- `:app` 新增 [WraDisasterClient.kt](../app/src/main/kotlin/com/cs22study/seethere/data/WraDisasterClient.kt)（10 分鐘記憶體快取），示範鏡頭 `wra_17457`（花蓮光復羅莫溪匯流口）於播放時動態向水利署開放資料 API 換取當日有效 `CCTV_SN` 影像網址。

### 2.3 起迄路線沿途影像搜尋 (`Corridor Search`)
- 於 [GeoSpatialEngine.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/GeoSpatialEngine.kt) 新增 `findAlongCorridor()`：
  - 將起點 $S$ 到終點 $E$ 依距離等距採樣（每 2 公里 1 點，上限 40 點），搭配路線矩形 Bounding Box 粗篩與 Haversine 最短距離檢核，並依路線進度 $t \in [0, 1]$ 由起點到終點排序。
- 「附近影像」頁籤新增 `附近半徑搜尋` / `起迄路線沿途搜尋` 切換，支援選取起終點錨點（例：`臺灣大學 (公館) → 桃園國際機場`）。

### 2.4 `PixelCopy` 免彈窗快速截圖通道
- 新增 [ScreenshotStorageHelper.kt](../app/src/main/kotlin/com/cs22study/seethere/service/ScreenshotStorageHelper.kt)，當正在播放原生非 YouTube 畫面（HLS / MJPEG / 快照）且為 Android 8.0+ 時，優先走 `PixelCopy.request(window, ...)` **免系統彈窗瞬拍**；若為 YouTube 跨網域畫面或 Android 7.x，則自動切換為 `MediaProjection` 全螢幕擷取。

---

## 3. 自動化測試與建置驗證結果

執行 `scripts/gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleDebug` 結果：
- `:core-stream:test`：**19 項 JUnit 5 測試全數通過**（`StreamSecuritySanitizerTest` 8 項、`GeoSpatialEngineTest` 5 項、`CameraCodecAndLogTest` 4 項、`WraDisasterParserTest` 2 項）。
- `:app:testDebugUnitTest`：**1 項測試通過**（`SeeThereAppSmokeTest`）。
- `:app:assembleDebug`：`v0.2.0` 三架構 Debug APK 建置成功。
