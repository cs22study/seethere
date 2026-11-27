# SeeThere 到處看看 APP — 專案待辦清單 (TODO List)

> 最後更新：2026-11-27  
> 開發模式：Hub-and-Spoke 模組化開發（嚴格遵守 5 大原則：自動化測試、資安防外洩、軟刪除復原、零花費、回寫 `docs/`）

---

## 一、 已完成項目 (Completed)

- [x] **Phase 0：三方參考網站分析、資安/雙端設計與原理定案**（`docs/01_開發內容分析與設計原理.md`）
- [x] **Spoke 1–5 MVP：核心引擎、雙模 APP 與自動化測試 (`v0.1.0`)**（`docs/02_Spoke1_5_核心引擎與APP實作紀錄.md`）
  - 經 BlueStacks 實機測試確認架構、截圖、退出 APP 正常
- [x] **Spoke 6：進階看圖模式、動態資料源與體驗優化 (`v0.2.0`)**（`docs/03_Spoke6_四宮格輪播與路線搜尋實作.md`）
  - [x] 截圖預覽橫幅縮短為 **2 秒自動收合**，精簡為單行通知
  - [x] `PixelCopy` 免彈窗快速截圖通道（YouTube / Android 7.x 自動轉 `MediaProjection`）
  - [x] 電視牆 **2×2 四宮格同時監看** ＋ **電子風景窗 30 秒自動輪播模式**
  - [x] 水利署防災影像（`opendata.wra.gov.tw` API v2）動態加密序號即時解析與 10 分鐘快取（示範：`wra_17457`）
  - [x] **起迄路線沿途影像搜尋**（`Corridor Search`）
  - [x] **YouTube 錯誤 153 修復 (`v0.2.1`)**：`loadDataWithBaseURL` + `strict-origin-when-cross-origin` + `fork620` 直連 `<video>` 置頂切換備援
  - [x] **橫屏分欄、四宮格全螢幕與雙來源關鍵字搜尋 (`v0.3.0`)**（`docs/04_Spoke6_5_橫屏四宮格與雙來源關鍵字搜尋實作.md`）
    - [x] 修正橫屏模擬器左右分欄（左頁籤內容不再被擠壓為 0dp）
    - [x] 修復 2×2 四宮格 YouTube 畫面裁剪為上半部問題，新增點選任一格放大全螢幕與退出全螢幕
    - [x] 附近查詢加入關鍵字＋`tw.live` / `YouTube` 來源搜尋與一鍵播放/收藏
    - [x] 24 項自動化單元測試全數通過（`:core-stream:test` 23 項 + `:app:testDebugUnitTest` 1 項）並產出 `v0.3.0` Debug APK
  - [x] **OPPO R11s Plus（Android 7.1.1 / ColorOS 3.2）實機相容與 `v1+v2` 永久簽章 (`v0.3.1`)**（`docs/05_OPPO實機相容與v1v2簽章修復紀錄.md`）
    - [x] 本機建立持久化簽章金鑰並強制開啟 `v1 + v2` 雙重簽章（Release APK 約 2.3 MB）
    - [x] 新增 HLS 失敗自動切換即時快照備援與窄螢幕標題適配，已於 `CPH1721` 實機 `adb` 部署驗證通過
  - [x] **手機操作體驗優化 (`v0.3.2`)**：單鏡／全螢幕左右滑動換台、最愛上方 `◀ / ▶` 切換列、搜尋自動收合鍵盤（25 項單元測試全數通過 + 持久化簽章 Release APK 建置完成）
  - [x] **區網後台、四格分組翻頁與實機體驗修復 (`v0.4.0`)**（`docs/06_Spoke6_8_區網後台四格翻頁與體驗修復.md`）
    - [x] `InputMethodManager` + `clearFocus` 雙重強制收合軟鍵盤（修正 OPPO 實機鍵盤卡住問題）
    - [x] 2×2 四宮格實作 4 鏡頭分組 `◀ / ▶` 翻頁與左右滑動切換
    - [x] CSV 匯出至公開 `Documents/SeeThere/` 資料夾（支援 Android 7–9 權限授權與 Android 10+ `MediaStore`）
    - [x] 同區網指定 PORT 輕量網頁管理後台（僅限私網 IP 存取，支援瀏覽器新增、CSV 匯入／匯出與切台）
    - [x] 設定與本機日誌分頁新增即時診斷狀態卡（操作通知、日誌筆數／路徑、區網網址與清除日誌鈕）
    - [x] 27 項自動化單元測試全數通過（`:core-stream:test` 26 項 + `:app:testDebugUnitTest` 1 項），已 `adb install -r` 推送至 OPPO R11s Plus（`7637c621`）

---

## 二、 後續排程 (Next Spokes)

- [ ] **Spoke 7：零花費雲端同步、手機傳電視交換碼與 GitHub Pages 發布**
  - [ ] Google Apps Script + Google Sheets + Google Drive 個人備份與「手機傳送至電視盒 6 碼暫存交換」（`clasp` 雙向版控、白名單鎖定 ID、雲端 SHA-256 + Salt 密碼驗證）
  - [ ] GitHub Pages 靜態公開目錄 JSON（擴充全台/全球精選鏡頭）與 APK 下載引導頁
  - [ ] 永久簽章 Keystore（`keystore/` 本機隔離 + GitHub Secrets）與 GitHub Release 正式打包發布
  - [ ] 單鏡定時本機縮時抓圖（含張數與空間上限控制）
