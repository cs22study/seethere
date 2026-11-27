# SeeThere 到處看看 APP — Spoke 1~5 MVP 核心引擎與 APP 實作紀錄

> **建置版本**：`v0.1.0` (`versionCode = 1`)  
> **目標平台**：Android 7.0+ (`minSdk = 24`, `targetSdk = 35`) 手機 ＋ 小米電視盒子 S 2/3 代 Google TV

---

## 1. 本階段完成項目總覽

### 1.1 `:core-stream` 純 Kotlin 核心計算與資安模組
- [CameraModels.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/CameraModels.kt)：定義 `StreamProtocol`、`CameraItem`（含 `isDeleted`, `deletedAtIso` 軟刪除欄位）、`LocationAnchor`、`NearbyCameraResult`、`CameraImportReport`。
- [StreamSecuritySanitizer.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/StreamSecuritySanitizer.kt)：
  - 清洗 TDX 常見前置單引號 `'` 與空白，阻擋 `javascript:`、`data:`、`file:`、`content:`、`wss:` 與控制字元。
  - 封鎖 `localhost`、`127.0.0.1`、`169.254.*` 雲端中繼資料與預設封鎖內網 RFC1918 IP（防 SSRF）；支援明確勾選區網攝影機才放行。
  - YouTube 網址萃取嚴格 11 碼 ID 並改寫為無追蹤 `youtube-nocookie.com/embed/{id}`；隔離 WebView 僅放行白名單網域。
  - `sanitizeLabel()` 完整移除 `<script>` / `<style>` 區塊與 HTML 標籤；`escapeCsvCell()` 防護 `= + - @` 公式注入。
- [GeoSpatialEngine.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/GeoSpatialEngine.kt)：Bounding Box 粗篩 ＋ Haversine 球面大圓距離精算排序，並提供 `findNearestBackup()` 離線自動備援查找。
- [StreamRouter.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/StreamRouter.kt)：多協定自動分派與指數退避重連計算（上限 60 秒、最多重試 5 次）。
- [CameraCodec.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/CameraCodec.kt)：RFC 4180 CSV 匯入/匯出與資安攔截報告。
- [LogFormatter.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/LogFormatter.kt)：JSONL 格式化、移除換行防 Log Forging、敏感參數自動遮罩 `***`。
- [CuratedDemoCatalog.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/CuratedDemoCatalog.kt)：內建 **台大附近**（`BOT079`、`BOT169`、`BOT322`、`BOT342`、`BOT026`）、**桃園機場**（`taoyuanairport`）與 **東京**（新宿歌舞伎町 `DjdUEyjx8GM`）示範清單與地點錨點。

### 1.2 `:app` Android 7+ 與 Google TV 雙模應用程式
- **Manifest 相容性**（[AndroidManifest.xml](../app/src/main/AndroidManifest.xml)）：設定 `usesCleartextTraffic="false"`、`touchscreen` 與 `location.gps` 為 `required="false"`，同時宣告 `LAUNCHER` 與 `LEANBACK_LAUNCHER`（含 320×180 `tv_banner.xml`）。
- **本機資料與日誌**：Room SQLite（[AppDatabase.kt](../app/src/main/kotlin/com/cs22study/seethere/data/db/AppDatabase.kt)）支援軟刪除與回收桶復原；[LocalRotatingLogger.kt](../app/src/main/kotlin/com/cs22study/seethere/data/LocalRotatingLogger.kt) 提供 2MB 輪替、14 天保留與匯出至 `Documents/SeeThere/logs/`。
- **多協定播放器**（[PlayerComponents.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/PlayerComponents.kt)）：Media3 ExoPlayer 播放 HLS `.m3u8`、隔離沙箱 WebView 播放 YouTube、背景協程解碼 MJPEG 與定時快照輪詢，斷線可一鍵切換最近備援鏡頭。
- **首頁雙模 UI 與截圖**（[HomeScreen.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt)、[ScreenCaptureService.kt](../app/src/main/kotlin/com/cs22study/seethere/service/ScreenCaptureService.kt)）：
  - 預設開啟為**我的最愛清單**。
  - 首頁頂部列直接放置「截圖」按鈕（存至 `Pictures/SeeThere/`），設定頁可開關「截圖角落標註攝影機名稱＋時間」。
  - 左側功能抽屜與 TV 側邊欄最下方皆固定放置紅色高對比**「退出 APP」**按鍵（停止串流並呼叫 `finishAndRemoveTask()`）。

---

## 2. 自動化測試與編譯驗證結果

透過 `scripts/gradle-win.ps1` 執行 `:core-stream:test :app:testDebugUnitTest :app:assembleDebug` 全數通過：
- `:core-stream:test`：16 項 JUnit 5 單元測試全數通過（`StreamSecuritySanitizerTest` 8 項、`GeoSpatialEngineTest` 4 項、`CameraCodecAndLogTest` 4 項）。
- `:app:testDebugUnitTest`：1 項單元測試通過（`SeeThereAppSmokeTest`）。
- `:app:assembleDebug`：成功產出 `arm64-v8a`、`armeabi-v7a`、`universal` 三種 Debug APK。
