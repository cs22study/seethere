# Spoke 6.8：區網後台、四格分組翻頁、文件夾匯出與實機體驗修復紀錄 (`v0.4.0`)

> **版本編號**：`v0.4.0`（`versionCode = 7`）  
> **實作日期**：2026-11-27  
> **測試裝置**：OPPO R11s Plus（`CPH1721` / Android 7.1.1 / API 25 / ColorOS 3.2 / Serial `7637c621`）

---

## 一、 實機回饋與修復／新增項目

1. **附近搜尋軟鍵盤強制收合修復（ColorOS / Android 7.1.1）**
   - 問題：OPPO 實機按下畫面上的「搜尋」按鈕或鍵盤 IME 搜尋鍵後，Compose `SoftwareKeyboardController.hide()` 仍偶發將鍵盤留在畫面下方，必須再按返回鍵才能收起。
   - 解法：於 `HomeScreen.kt` 結合 `InputMethodManager.hideSoftInputFromWindow(localView.windowToken, 0)`、`localView.clearFocus()`、`focusManager.clearFocus(force = true)` 與 `LaunchedEffect(isSearching)` 雙重強制收合。

2. **2×2 四宮格「一次 4 個鏡頭」分組翻頁與左右滑動切換**
   - 問題：先前四宮格畫面未依分頁取出對應切片（僅固定取前 4 筆），造成按下左右切換後畫面內容未改變。
   - 解法：於 `:core-stream` `CameraNavigator.kt` 新增 `orderedQuadPool()`、`totalQuadPages()`、`getQuadPage()`；於 `SeeThereViewModel.kt` 加入 `_quadPageIndex` 與 `stepQuadPage()`；於 `HomeScreen.kt` `QuadWallGrid` 補上 `◀ / ▶` 四格分組切換列與 `horizontalCameraSwipe` 左右滑動手勢，支援循環切換下一組（最多 4 格）即時影像。

3. **CSV 匯出儲存至公開 `Documents/SeeThere/` 資料夾**
   - 新增 `PublicDocumentsStorage.kt`：支援 Android 10+ `MediaStore.Files` 與 Android 7–9 `Environment.DIRECTORY_DOCUMENTS/SeeThere/`（含 `WRITE_EXTERNAL_STORAGE` 動態權限授權與 App 私有目錄備援）。
   - 於 `LocalRotatingLogger.kt` 統一重用 `PublicDocumentsStorage`，並於 `SeeThereViewModel.exportCsvToDocuments()`、`MainActivity.kt` 與 `HomeScreen.kt` 新增匯入／匯出分頁「匯出至 Documents」按鈕。

4. **同區網指定 PORT 網頁管理後台（參考 `fork620` `LocalWebServer.kt`）**
   - 新增 `SeeThereLanWebServer.kt`：採用零第三方依賴之 `ServerSocket` 輕量 HTTP 伺服器，可自訂 Port（預設 `8080`，合法範圍 `1024..65535`，設定持久化於 `PreferencesRepository.kt`）。
   - 資安防線：`CameraNavigator.isPrivateOrLoopbackIp()` 嚴格限定僅允許區域網路私有網段（RFC1918：`10.x`、`172.16-31.x`、`192.168.x`、`127.0.0.1` 等）存取，公網 IP 直接回應 `403 Forbidden`；HTML 輸出全數經 `escapeHtml()` 跳脫；網址列與 CSV 輸入仍經 `StreamSecuritySanitizer` 清洗。
   - 提供端點：`GET /`、`GET /api/status`、`GET /api/cameras`、`GET /api/export.csv`、`POST /api/camera/add`、`POST /api/import.csv`、`POST /api/camera/play`。

5. **設定與本機日誌頁狀態提示改良**
   - 於 `SeeThereViewModel.kt` 新增 `_lastSettingsNotice`、`_lanServerUrl`、`refreshLogs(notifyUser)`、`clearLocalLogs()`。
   - 於 `HomeScreen.kt` `SettingsLogsTabContent` 頂部新增診斷與狀態卡片，即時顯示最近操作結果、日誌筆數、日誌儲存路徑提示、區網管理網址，並提供「重新整理」、「匯出全部日誌到 Documents」與「清除本機日誌」按鈕。

---

## 二、 異動檔案清單

- `core-stream/src/main/kotlin/com/cs22study/seethere/core/CameraNavigator.kt`
- `core-stream/src/test/kotlin/com/cs22study/seethere/core/CameraNavigatorTest.kt`
- `app/src/main/kotlin/com/cs22study/seethere/data/PublicDocumentsStorage.kt`
- `app/src/main/kotlin/com/cs22study/seethere/data/LocalRotatingLogger.kt`
- `app/src/main/kotlin/com/cs22study/seethere/server/SeeThereLanWebServer.kt`
- `app/src/main/kotlin/com/cs22study/seethere/data/PreferencesRepository.kt`
- `app/src/main/kotlin/com/cs22study/seethere/ui/SeeThereViewModel.kt`
- `app/src/main/kotlin/com/cs22study/seethere/MainActivity.kt`
- `app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt`
- `app/build.gradle.kts`

---

## 三、 自動化測試與實機部署驗證結果

- **Gradle 建置與測試**：`powershell -ExecutionPolicy Bypass -File scripts/gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleRelease` 通過（`BUILD SUCCESSFUL`）。
  - `:core-stream:test`：**26 項全數通過**（0 失敗、0 錯誤、0 略過）：`CameraCodecAndLogTest` (4)、`CameraNavigatorTest` (3)、`ExternalSearchParsersTest` (3)、`GeoSpatialEngineTest` (5)、`StreamSecuritySanitizerTest` (9)、`WraDisasterParserTest` (2)。
  - `:app:testDebugUnitTest`：**1 項通過**（`SeeThereAppSmokeTest`，0 失敗、0 錯誤、0 略過）。
  - 總計 **27 項自動化單元測試 100% 通過**。
- **Release APK（持久化 `v1 + v2` 簽章）**：
  - `app-arm64-v8a-release.apk`：2.22 MB（2,332,078 bytes）
  - `app-armeabi-v7a-release.apk`：2.22 MB（2,331,773 bytes）
  - `app-universal-release.apk`：2.24 MB（2,346,283 bytes）
- **OPPO R11s Plus（`7637c621`）實機推送**：`adb -s 7637c621 install -r` 成功安裝並啟動 `.MainActivity`，同步推送 `/sdcard/Download/SeeThere-v0.4.0-arm64-v8a.apk`。
