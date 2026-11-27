# SeeThere 到處看看 APP — Spoke 6.5 橫屏分欄、四宮格全螢幕與雙來源關鍵字搜尋實作 (`v0.3.0`)

> **版本**：`v0.3.0` (`versionCode = 4`)  
> **實測來源**：BlueStacks 橫屏模擬器實測截圖與使用回饋

---

## 1. BlueStacks 實測問題診斷與修復

1. **模擬器中「新增與匯入」及「附近查詢」無法顯示內容之根因與修復**
   - **根因**：BlueStacks 預設為橫向平板視窗（`isTvMode = false` 且寬度 > 680dp），先前僅依 `isTvMode` 判斷左右分欄，導致橫向模擬器落入直立手機上下排版，上方 2×2 四宮格佔滿整個垂直高度，將下方 `TabContentBody` 擠壓至 `0dp`。
   - **修復**（[HomeScreen.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt)）：改採響應式斷點 `useSideBySideLayout = isTvMode || screenWidthDp >= 680 || orientation == LANDSCAPE`（左欄清單／表單／搜尋，右欄播放視窗），並對直立手機播放區加上 `heightIn(max = 260.dp)` 上限，徹底消除版面擠壓。

2. **2×2 四宮格中 YouTube 只顯示上半部 ＋ 點選任一格全螢幕切換**
   - **根因**：`IsolatedYouTubeWebView` 先前於內嵌模式開啟 `useWideViewPort = true`，使 WebView 以桌面寬視口排版後在四宮格窄框中只裁出上半部；同時 `AndroidView` 攔截了外層 `Box.clickable` 事件。
   - **修復**：
     - 於 [PlayerComponents.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/PlayerComponents.kt) 在 iframe 內嵌模式停用 `useWideViewPort` / `loadWithOverviewMode`，搭配 [StreamSecuritySanitizer.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/StreamSecuritySanitizer.kt) `position: fixed; width: 100%; height: 100%;` CSS，使 YouTube 畫面 1:1 完整貼合四宮格格子。
     - 於每格頂部加入高置頂（`zIndex(2f)`）可點擊標題列與全螢幕圖示，點選任一格立即切換為該鏡頭並進入全螢幕；全螢幕右上角配置明確「退出全螢幕」鈕並支援實體/遙控器返回鍵（`BackHandler`）退出。

---

## 2. 附近查詢關鍵字 ＋ `tw.live` / `YouTube` 雙來源搜尋

- **純 Kotlin 解析器**（[ExternalSearchParsers.kt](../core-stream/src/main/kotlin/com/cs22study/seethere/core/ExternalSearchParsers.kt)）：
  - 解析 `tw.live/nearby/?ajax=1` HTML 清單與 `tw.live/cam/?id=...` 真實串流網址（HLS `.m3u8` / YouTube / MJPEG），全程經 `StreamSecuritySanitizer` 清洗與防 XSS。
  - 解析 YouTube 直播搜尋頁 `ytInitialData`（參照 `androidtv-news-fork620` 之 `YouTubeSearcher.kt` 策略，免 API Key）。
- **查詢管線**（[ExternalCamSearchClient.kt](../app/src/main/kotlin/com/cs22study/seethere/data/ExternalCamSearchClient.kt)、[SeeThereViewModel.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/SeeThereViewModel.kt)）：
  - 支援輸入地點/關鍵字（搭配 OpenStreetMap Nominatim 免費座標反查定位），勾選「本機清單」、「`tw.live` 來源」、「`YouTube` 來源」同步搜尋，結果支援一鍵「播放」或「＋收藏」。
  - 單筆新增亦支援直接貼上 `https://tw.live/cam/?id=...` 網址自動解析串流。

---

## 3. 自動化測試與建置結果 (`v0.3.0`)

執行 `scripts/gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleDebug` 結果：
- `:core-stream:test`：**23 項 JUnit 5 測試全數通過**（新增 `ExternalSearchParsersTest` 3 項）。
- `:app:testDebugUnitTest`：**1 項測試通過**（`SeeThereAppSmokeTest`）。
- `:app:assembleDebug`：`v0.3.0` 三架構 Debug APK 建置成功。
