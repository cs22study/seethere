# SeeThere 到處看看 APP — OPPO R11s Plus 實機相容與 `v1+v2` 永久簽章修復 (`v0.3.1`)

> **版本**：`v0.3.1` (`versionCode = 5`)  
> **實測裝置**：OPPO R11s Plus（CPH1721 / Android 7.1.1 API 25 / ColorOS 3.2 / `arm64-v8a`）

---

## 1. 問題排查與根因分析

1. **GitHub Release `arm64-v8a` APK 無法在 OPPO R11s Plus 直接安裝**
   - 檢驗先前 GitHub CI 產出之 `SeeThere-v0.3.0-arm64-v8a.apk`，發現沒有持久化 Release Keystore（使用每次 CI 隨機產生的 `debug` 簽章）且 ColorOS 3.2 圖形安裝器在聯網狀態下會觸發 `com.coloros.safecenter`／OPPO 軟體商店雲端檢核逾時導致無法安裝。
   - **解法**：建立本機持久化簽章金鑰（`keystore/seethere-release.jks`），於 `app/build.gradle.kts` 同時啟用 `enableV1Signing = true`（產出 `META-INF/CERT.SF` / `CERT.RSA`）與 `enableV2Signing = true`，並產出約 `2.3 MB` 之 R8 精簡 Release APK；電腦端可直接使用 `adb install`、手機端無電腦時則採「開啟飛航模式＋內建檔案管理安裝」。
2. **台北市交工處 HLS 伺服器偶發 404 / 逾時**
   - 實機 `logcat` 捕捉到 `jtmctrafficcctv5.gov.taipei/NVR/.../live.m3u8` 偶發 `Response code: 404` / 連線逾時，而同時段對應之靜態快照 `https://assets.tw.live/images/taipei/BOT079.jpg` 仍可秒開（HTTP 200）。
3. **360dp 窄螢幕手機排版細節**
   - 原頂部標題 `"SeeThere 到處看看"` 在 `360dp` 窄螢幕會被截圖按鈕擠壓顯示 `"SeeThere 到..."`，且 HLS 錯誤遮罩會透出底層 `PlayerView` 中央播放鈕。

---

## 2. 修復項目與驗證結果 (`v0.3.1`)

1. **建立持久化簽章金鑰並強制開啟 `v1 + v2` 雙重簽章**：
   - 於本機隔離目錄建立 `keystore/seethere-release.jks`（`CN=SeeThere, O=cs22study, C=TW`，`.gitignore` 已排除絕不入 Git）。
   - 於 [app/build.gradle.kts](../app/build.gradle.kts) 為 `releasePersistent` 與 `releaseFallbackDebug` 皆強制設定 `enableV1Signing = true`、`enableV2Signing = true`、`enableV3Signing = false`。
2. **HLS 故障自動切換即時快照備援**：
   - 於 [PlayerComponents.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/PlayerComponents.kt) 加入智慧備援：當 HLS 串流重試失敗且鏡頭具備 `thumbnailUrl` 時，自動於 `1.2 秒` 後切換為每 5 秒刷新之「即時快照」模式（並可隨時點「重試影片」）；錯誤遮罩期間自動隱藏底層 `PlayerView` 控制器。
3. **窄螢幕標題與清單間距優化**：
   - 於 [HomeScreen.kt](../app/src/main/kotlin/com/cs22study/seethere/ui/HomeScreen.kt) 在窄屏直立模式顯示簡潔 `"SeeThere"`，並為清單增加底部留白。
4. **實機與自動化驗證**：
   - `apksigner verify` 確認 `v0.3.1` Release APK 簽章驗證通過（`v2 scheme: true`，且封裝內含 `META-INF/CERT.SF` / `CERT.RSA` / `MANIFEST.MF`，體積約 `2.3 MB`）。
   - 已透過 `adb` 成功安裝（`versionCode=5`, `versionName=0.3.1`）並啟動於 **OPPO R11s Plus（CPH1721）**，無閃退且實測當 `BOT079` HLS 異常時自動切換顯示即時路況快照。
