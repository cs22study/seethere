# Spoke 6.8：區網後台、四格分組翻頁、文件夾匯出與實機體驗修復紀錄 (`v0.4.0`)

> **版本編號**：`v0.4.0`（`versionCode = 7`）→ **`v0.4.1`（`versionCode = 8`）**  
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
- `app/src/main/kotlin/com/cs22study/seethere/service/SeeThereLanService.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/kotlin/com/cs22study/seethere/data/PreferencesRepository.kt`
- `app/src/main/kotlin/com/cs22study/seethere/ui/SeeThereViewModel.kt`
- `app/src/main/kotlin/com/cs22study/seethere/MainActivity.kt`
- `app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt`
- `app/src/test/kotlin/com/cs22study/seethere/SeeThereAppSmokeTest.kt`
- `app/build.gradle.kts`

---

## 三、 `v0.4.1` 實機補充修正重點

1. **OPPO 窄螢幕 2×2 四宮格右箭頭被擠出畫面修復**
   - 原因：`QuadWallGrid` 頂部翻頁列中間標題 `Text` 未設定 `Modifier.weight(1f)`，在 OPPO 窄螢幕直立時將右側 `▶` `IconButton` 推擠至畫面外。
   - 解法：為中間標題加上 `Modifier.weight(1f).padding(horizontal = 4.dp)` 與置中對齊，並調整左右按鈕為 `36.dp`，確保左右切換箭頭於手機窄螢幕皆完整顯示且可點擊。
2. **區網後台「資安檢查並新增播放」無反應修復與保活加強**
   - 新增前景保活服務 `SeeThereLanService`（常駐通知顯示區網網址），避免 ColorOS 休眠／鎖屏凍結 `ServerSocket` 導致瀏覽器 `fetch` 斷線。
   - 將 `SeeThereLanWebServer` JSON 編解碼改為純 Kotlin 實作（移除 JVM 測試易受 Android Stub 影響的 `org.json` 依賴，並安全捕捉 `Throwable` 與 `OPTIONS`），避免伺服器異常關閉連線。
   - 前端管理頁加入「檢查與傳送中...」按鈕鎖定、10 秒 `AbortController` 逾時保護、`try / catch` 錯誤捕捉，以及按鈕下方緊鄰顯示的 `#addMsg` / `#importMsg` 結果框，確保任何成功、資安攔截或手機斷線都能即時提示。

---

## 四、 自動化測試與實機部署驗證結果 (`v0.4.1`)

- **Gradle 建置與測試**：`powershell -ExecutionPolicy Bypass -File scripts/gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleRelease` 通過（`BUILD SUCCESSFUL`）。
  - `:core-stream:test`：**26 項全數通過**（0 失敗、0 錯誤、0 略過）：`CameraCodecAndLogTest` (4)、`CameraNavigatorTest` (3)、`ExternalSearchParsersTest` (3)、`GeoSpatialEngineTest` (5)、`StreamSecuritySanitizerTest` (9)、`WraDisasterParserTest` (2)。
  - `:app:testDebugUnitTest`：**2 項全數通過**（0 失敗、0 錯誤、0 略過），含 `SeeThereAppSmokeTest.lanWebServerAddsYouTubeCameraOverHttpPost`（真實 HTTP POST 新增 YouTube `https://www.youtube.com/watch?v=ipbHUjTeUIw`＋中文標題「富士山急行線河口湖駅前」＋`javascript:alert(1)` 攔截 400 驗證）。
  - 總計 **28 項自動化單元測試 100% 通過**。
- **Release APK（持久化 `v1 + v2` 簽章）**：
  - `app-arm64-v8a-release.apk`：2.23 MB（2,334,616 bytes）
  - `app-armeabi-v7a-release.apk`：2.23 MB（2,334,299 bytes）
  - `app-universal-release.apk`：2.24 MB（2,348,818 bytes）
- **OPPO R11s Plus（`7637c621`）實機推送**：`adb -s 7637c621 install -r` 成功升級至 `versionCode=8` / `versionName=0.4.1` 並啟動 `.MainActivity`，同步推送 `/sdcard/Download/SeeThere-v0.4.1-arm64-v8a.apk`。
