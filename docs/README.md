# SeeThere 到處看看 APP — Hub-and-Spoke 開發總覽

> **專案路徑**：`D:\志松資料\GAS程式AI運用小松資料區cs22study\SeeThere到處看看APP`  
> **GitHub Repo**：`https://github.com/cs22study/seethere`  
> **目標平台**：Android 7.0+ 手機／平板 ＋ 小米電視盒子 S 2 代／3 代（Google TV / Android TV）  
> **核心定位**：個人化、零廣告、高資安、可搜尋附近與沿途影像、支援自訂新增/匯入與首頁一鍵截圖的即時影像系統

---

## 五大系統開發原則落地對應

1. **自動化測試驗證**：空間搜尋（Haversine 距離、Bounding Box 與起迄路線走廊搜尋）、URL 清洗與資安白名單（XSS/SSRF 防護）、水利署動態 API 解析、CSV 編解碼、串流重連狀態機等核心邏輯抽離為純 Kotlin / JVM `:core-stream` 模組，執行 JUnit 5 單元測試；Android 模組透過 Gradle 與 GitHub Actions CI 驗證。
2. **資安與防外洩**：
   - 完全無廣告（Zero Ads），不接入任何廣告或第三方追蹤 SDK，`usesCleartextTraffic="false"`。
   - 嚴格 URL 與 Host 白名單防護，阻擋 `javascript:`、`file://`、`data:`、`wss://` 與私網 IP（`192.168.*`、`10.*`、`172.16-31.*`）SSRF 風險，並清洗 HTML `<script>` 標籤與 CSV/Log 注入字元。
   - 雲端同步與管理密碼驗證（Spoke 7）採 Google Apps Script 端 SHA-256 + Salt 比對，並以白名單鎖定 Google Sheet / Drive ID。
3. **隨時可復原**：本機 Room SQLite 資料庫與 CSV 匯出檔內建軟刪除欄位（`isDeleted`, `deletedAtIso`）與回收桶還原機制；本地與發布頁採 Git 版控，GAS 後端採 `clasp` 雙向版控。
4. **零花費架構**：Android 原生 APK 透過免費開源工具鏈（Gradle / GitHub Actions CI）建置；公開攝影機索引與靜態發布頁使用 **GitHub Pages**；選用個人收藏同步與手機傳電視交換碼使用 **Google Apps Script + Google Sheets + Google Drive** 免費額度。
5. **節省 Token（Hub-and-Spoke 文件化）**：每個 Spoke 模組完成後，規格、變更與測試結果均回寫至 `docs/`。

---

## 文件索引

| 文件 | 說明 | 狀態 |
| :--- | :--- | :--- |
| [01_開發內容分析與設計原理.md](./01_開發內容分析與設計原理.md) | 參考網站技術分析（`tw.live` / `mini-taiwan-pulse` / YouTube）、雙端架構、資安防線與計算原理 | 已完成 |
| [02_Spoke1_5_核心引擎與APP實作紀錄.md](./02_Spoke1_5_核心引擎與APP實作紀錄.md) | `:core-stream` 核心／資安引擎、Room 軟刪除 DB、雙模首頁、多協定播放、首頁截圖與自動化測試紀錄 (`v0.1.0`) | 已完成 |
| [03_Spoke6_四宮格輪播與路線搜尋實作.md](./03_Spoke6_四宮格輪播與路線搜尋實作.md) | BlueStacks 2 秒截圖提醒修正、2×2 四宮格、電子風景窗輪播、水利署動態 API、`PixelCopy` 與路線沿途搜尋 (`v0.2.0`–`v0.2.1`) | 已完成 |
| [04_Spoke6_5_橫屏四宮格與雙來源關鍵字搜尋實作.md](./04_Spoke6_5_橫屏四宮格與雙來源關鍵字搜尋實作.md) | 橫屏分欄修復、四宮格 YouTube 縮放與點選全螢幕、附近關鍵字＋`tw.live`/`YouTube` 搜尋 (`v0.3.0`) | 已完成 |
| [05_OPPO實機相容與v1v2簽章修復紀錄.md](./05_OPPO實機相容與v1v2簽章修復紀錄.md) | OPPO R11s Plus 實機排查、`v1+v2` 永久簽章、HLS 自動快照備援與窄屏 UI 修復 (`v0.3.1`) | 已完成 |
| [06_Spoke6_8_區網後台四格翻頁與體驗修復.md](./06_Spoke6_8_區網後台四格翻頁與體驗修復.md) | 軟鍵盤強制收合、2×2 四格分組翻頁、Documents 匯出、同區網指定 PORT 網頁後台與日誌診斷狀態卡 (`v0.4.0`) | 已完成 |
| [TODO_LIST.md](./TODO_LIST.md) | Hub-and-Spoke 專案待辦清單與各 Spoke 驗收里程碑 | 持續更新 |

---

## Hub-and-Spoke 模組開發路線圖 (Spoke Roadmap)

| 階段 | Spoke 模組名稱 | 核心交付項目 | 狀態 |
| :--- | :--- | :--- | :--- |
| **Phase 0** | 參考網站分析與系統設計規格 | 三方網站分析、架構設計、數學/資安原理、加值點子確認 | ✅ 已完成 |
| **Spoke 1–5** | 核心引擎與 MVP 雙模 APP (`v0.1.0`) | `:core-stream` + 17 項單元測試、Room 軟刪除、HLS/YouTube/MJPEG/Snapshot 播放、預設最愛首頁、附近搜尋、首頁一鍵截圖（可選角落標註）、CSV 匯入/匯出、本機日誌與首頁退出 APP 鈕 | ✅ 已完成 |
| **Spoke 6** | 進階看圖模式、動態資料源與體驗優化 (`v0.2.0`) | 2 秒截圖通知自動收合、`PixelCopy` 免彈窗快截、2×2 TV 四宮格、電子風景窗 30s 輪播、水利署動態 API (`wraDisasterCctv`)、起迄路線沿途影像搜尋、GitHub Push | ✅ 已完成 |
| **Spoke 7** | 零花費雲端同步、永久簽章與 GitHub Pages | GAS + Sheets + Drive（`clasp` 雙向版控、雲端密碼驗證、手機傳電視 6 碼交換碼）、GitHub Pages 公開清單 JSON 與下載頁、永久簽章 Release APK | ⏳ 待啟動 |
