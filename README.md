# SeeThere 到處看看 APP

支援 **Android 7.0+ 手機／平板** 與 **小米電視盒子 S 2／3 代（Google TV / Android TV）** 的個人化、零廣告、高資安即時影像觀看與截圖系統。

## 核心特色

- **無廣告・高資安**：零廣告 SDK、強制 HTTPS（區網 HTTP 需手動授權）、防 XSS / SSRF / CSV 公式注入與日誌偽造。
- **雙模介面（手機觸控 ＋ 電視遙控器 D-Pad）**：支援 `LAUNCHER` 與 `LEANBACK_LAUNCHER`，首頁預設進入「我的最愛清單」，功能表最下方配置「退出 APP」按鍵。
- **多協定播放**：Media3 ExoPlayer（HLS `.m3u8`）、隔離沙箱 YouTube（`youtube-nocookie.com`）、MJPEG 流式解碼、JPG 快照輪詢與水利署防災動態影像（`opendata.wra.gov.tw`）。
- **多元看圖模式**：單鏡模式、**2×2 四宮格電視牆**、**電子風景窗（30 秒自動輪播）**。
- **附近與沿途搜尋**：Bounding Box + Haversine 球面距離搜尋（手機 GPS / 電視盒地點錨點），並支援**起迄路線沿途影像搜尋**。
- **首頁一鍵截圖（雙通道）**：原生串流走 `PixelCopy` 免彈窗瞬拍，YouTube／全螢幕走 `MediaProjection`，可開關左下角「攝影機名稱＋時間」標註，截圖通知 2 秒自動收合。
- **隨時可復原與本機日誌**：Room SQLite 內建軟刪除回收桶；JSONL 本機滾動日誌（`2MB × 14 天`）可一鍵匯出至 `Documents/SeeThere/logs/`。

## 開發文件

- [docs/README.md](./docs/README.md) — Hub-and-Spoke 開發總覽與路線圖
- [docs/01_開發內容分析與設計原理.md](./docs/01_開發內容分析與設計原理.md) — 參考網站分析、資安架構與數學計算原理
- [docs/02_Spoke1_5_核心引擎與APP實作紀錄.md](./docs/02_Spoke1_5_核心引擎與APP實作紀錄.md) — 核心引擎與 MVP 實作紀錄 (`v0.1.0`)
- [docs/03_Spoke6_四宮格輪播與路線搜尋實作.md](./docs/03_Spoke6_四宮格輪播與路線搜尋實作.md) — Spoke 6 進階看圖模式與動態資料源 (`v0.2.0`)
- [docs/TODO_LIST.md](./docs/TODO_LIST.md) — 專案待辦與排程清單

## 本地自動化測試與建置

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\gradle-win.ps1 :core-stream:test :app:testDebugUnitTest :app:assembleDebug
```
