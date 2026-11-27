# Spoke 7A：左側選單「關於此 APP」、線上更新、GPL-3.0 致謝與 iPhone 免安裝網頁版設計 (`v0.5.0`)

> **版本編號**：`v0.5.0`（`versionCode = 9`）  
> **實作日期**：2026-11-27  
> **涵蓋範圍**：Android 左側抽屜「關於此 APP」／GitHub 線上更新／GPL-3.0／致謝頁面 ＋ iPhone 13 / 14 / 17 零花費跨平台免安裝網頁版（GitHub Pages PWA）設計與實作

---

## 一、 左側選單「關於此 APP」（`HomeTab.ABOUT`）

1. **動態讀取本機安裝版本**：
   - 於 `SeeThereViewModel.getInstalledVersionInfo()` 透過 `PackageManager` + `PackageInfoCompat.getLongVersionCode()` 動態讀取手機／電視盒實際安裝之 `versionName` 與 `versionCode`。
2. **零成本 GitHub Releases 線上更新檢查（`:core-stream` `AppUpdateChecker`）**：
   - 連線免費公開端點 `https://api.github.com/repos/cs22study/seethere/releases?per_page=5`（可同時涵蓋 `--prerelease` 標籤版本）。
   - 透過純 Kotlin `AppUpdateChecker.parseLatestReleaseFromArray()` 與 `isNewerVersion()` 進行語意化版本比對；所有跳轉或 APK 下載連結皆受 `TRUSTED_RELEASE_HOSTS`（`github.com` / `githubusercontent.com` / `cs22study.github.io`）白名單鎖定，杜絕非授權來源釣魚連結。
3. **GPL-3.0 開源授權與五大開發原則呈現**：
   - 明確宣告採 **GNU General Public License v3.0**（對應根目錄 `LICENSE`），無廣告、無追蹤、不附帶商業擔保，並提供瀏覽器一鍵查閱完整授權條文與專案原始碼首頁。
4. **參考成果與開源貢獻致謝**：
   - `tw.live`：台灣即時影像整合架構、多來源分類與附近距離定位啟發。
   - `mini-taiwan-pulse` (`github.com/ianlkl11234s/mini-taiwan-pulse`)：台灣防災空間架構、水利署動態序號 API（`wraDisasterCctv`）、SSRF/XSS 白名單防護與離屏媒體釋放機制。
   - `新聞直播 fork620` (`github.com/cs22study/androidtv-news-fork620`)：Android TV YouTube 播放器參數調整（錯誤 153 修復）與同區網指定 Port 管理伺服器模式。
   - 公開示範影像與開放資料：`twipcam.com`、桃園國際機場即時影像（遊桃園）、Tokyo Kabukicho Live Channel、台北市交通管制工程處、交通部 TDX、經濟部水利署開放資料（API v2），以及 Kotlin / Jetpack Compose / Media3 ExoPlayer / Room / FSF (GPL-3.0)。

---

## 二、 iPhone 13 / 14 / 17 免安裝 APP 透過網頁觀看與查詢之系統分析與設計

針對手上持有 **iPhone 13 / 14 / 17（iOS）**、無法直接安裝 `.apk` 且不支出 Apple Developer 年費（\$99/yr）上架 App Store 的場景，結合本專案「原則 2（資安防 XSS/SSRF）」與「原則 4（零花費：GitHub Pages）」，採 **「GitHub Pages Web PWA 雙棲互補架構」**：

### 2.1 iOS Safari 技術可行性與協定對照

| 能力面向 | iPhone Safari Web PWA 實況 | 本專案對應設計 (`web-pwa/`) |
| :--- | :--- | :--- |
| **App 化免安裝入口** | 支援標準 Web Manifest + `apple-mobile-web-app-capable`，點選「分享 → 加入主畫面」即全螢幕獨立視窗運行 | 建立 `web-pwa/manifest.webmanifest`、`icon.svg` 與 iPhone Safe Area (`viewport-fit=cover`) 深色 UI |
| **HLS (`.m3u8`) 播放** | iOS Safari **原生內建** HLS 硬體解碼，比桌機瀏覽器支援更佳 | 直接以 `<video src="*.m3u8" autoplay muted playsinline controls>` 播放，異常時支援切換「省流量縮圖備援」 |
| **YouTube Live 直播** | iPhone 須加 `playsinline=1` 避免強制跳出全螢幕 | `sanitizeHttpsUrl()` 強制改寫為 `https://www.youtube-nocookie.com/embed/{id}?autoplay=1&playsinline=1&rel=0&mute=1` |
| **附近定位與搜尋** | 瀏覽器標準 `navigator.geolocation` 支援 iPhone GPS | 結合精選座標錨點（台大／桃園機場／新宿／河口湖）＋ Haversine 距離排序與關鍵字過濾 |
| **與 APK 資料互通** | 本機 `localStorage` 持久化自訂鏡頭與最愛狀態 | 提供與 Android APK 完全相容欄位（`title,streamUrl,region,city,latitude,longitude`）之 CSV 匯入／匯出，也可於同 Wi-Fi 下直接用 iPhone 開啟 Android `SeeThere` 區網後台頁面 |
| **APK 版本與發布查詢** | 靜態網頁即時查詢 GitHub API | 「關於 / APK」頁直接向 GitHub Releases API 讀取最新 `tag_name` 與 `arm64/armv7a/universal` APK 下載鈕 |
| **網頁版邊界說明** | 受限瀏覽器同源政策（CORS），網頁版無法對跨網域 YouTube iframe 截圖，亦無系統級背景服務；`tw.live` 部分 JSON 若無 CORS 標頭則以公開直連清單為主 | 需全畫面截圖（`MediaProjection`）或電視盒遙控操作時，仍由 Android / Google TV APK 主導，兩端互補 |

### 2.2 零花費自動部署架構

- 新增目錄：`web-pwa/`（`index.html`、`cameras.json`、`manifest.webmanifest`、`icon.svg`），與技術文件 `docs/` 完全分離不互相干擾。
- 新增工作流：[`.github/workflows/pages.yml`](../.github/workflows/pages.yml)：推送 `main` 時自動部署至 GitHub Pages（`https://cs22study.github.io/seethere/`）；註：首次啟用需於 GitHub Repo `Settings → Pages → Source` 選擇 **GitHub Actions** 一次即可生效。

---

## 三、 `v0.5.0` 自動化測試與實機部署驗證結果

- **Gradle 測試與建置**：`powershell -ExecutionPolicy Bypass -File scripts/gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleRelease` 通過（`BUILD SUCCESSFUL`）。
  - `:core-stream:test`：**29 項全數通過**（含新增 `AppUpdateCheckerTest` 3 項）。
  - `:app:testDebugUnitTest`：**2 項全數通過**（`SeeThereAppSmokeTest`）。
  - 總計 **31 項自動化單元測試 100% 通過**（0 失敗、0 錯誤、0 略過），且 `web-pwa/cameras.json` 與 `manifest.webmanifest` UTF-8 JSON 解析驗證通過。
- **Release APK（持久化 `v1 + v2` 簽章）**：
  - `app-arm64-v8a-release.apk`：2.23 MB（2,342,675 bytes）
  - `app-armeabi-v7a-release.apk`：2.23 MB（2,342,370 bytes）
  - `app-universal-release.apk`：2.25 MB（2,356,884 bytes）
- **OPPO R11s Plus（`7637c621`）實機部署**：`adb -s 7637c621 install -r` 成功安裝 `versionCode=9` / `versionName=0.5.0` 並啟動 `.MainActivity`，同步推送 `/sdcard/Download/SeeThere-v0.5.0-arm64-v8a.apk`。
