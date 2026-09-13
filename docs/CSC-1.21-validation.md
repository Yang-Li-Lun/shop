# CSC 1.21 今日統計交付

依 2026-09-13 工程文檔實作，使用者補充優先：主畫面及辨識浮窗只顯示今日，昨日與前日不顯示，持久資料仍沿用既有三日保留政策。

- versionName 1.21 / versionCode 22；APK/CSC-1.21-arm64-v8a.apk；只含 arm64-v8a。
- Release/R8 建置，沿用 1.20 相容簽章；Git tag v1.21 對應此版本，未推送遠端。
- APK SHA-256: `e3cbce781a163a7d788967eda34d2ba01fcddc7dad564c754f632d98df25a655`
- 計數入口仍只有有效優先上滑完成分支；顯示更新沒有新增計數或手勢。畫面顯示不是伺服器確認領獎成功的證據。
- 有效啟用及目標前景下共用既有覆蓋視窗；首次顯示、計數完成與跨日刷新快照，單一 30 秒工作檢查日期，讀取錯誤顯示暫不可用並限頻重試。
- 使用 WindowInsets、實際 View 大小與局部座標定位；OCR 聯集的 2% 邊距、數字回退 8 像素邊距、視覺與二次確認區域皆納入避讓。重疊時隱藏新增面板，既有狀態標籤位置保持原樣。

## 已執行

- 126 項 JVM 測試，0 失敗、0 錯誤；包含今日/跨日資料、最大整數文字、尺寸限制與碰撞幾何，以及既有手勢/狀態機測試。
- lintDebug、lintRelease、assembleDebug、assembleRelease 通過。正式 APK 簽章與 ABI 驗證通過。
- LG G7 (LM_G710)，Android 10 / API 29，720×1560；替換安裝成功並冷啟動 CSC。
- DailyStatsOverlayDeviceCheck：在 CSC 內掛載實際 RecognitionRegionOverlayView，27 項數值更新、空辨識框、字型倍率 1/1.5/2、全畫面取樣重疊隱藏測試通過。測試不寫入正式統計、不送手勢。
- 浮窗測試前後 automation.xml 與 daily_trigger_stats.xml 位元組完全相同。
- 安裝前主畫面今日 66 次／昨日 100 次／前日 101 次；最終 Release 主畫面只顯示今日 66 次，目標套件仍為 com.shopee.tw。
- 最終 minified Release 通過三個 ML Kit registrar 建構子與中英文 OCR client 初始化檢查。
- CSC MainActivity 為前景，啟動檢查無 AndroidRuntime 錯誤。測試套件已移除。
- 本機圖片：app/build/daily-overlay-verification/before.png、after.png、overlay.png（最後一張為隔離繪製、示意 1000 次，未寫入正式統計）。

## 驗證範圍與使用限制

- 更新後 Android 清除了 CSC 無障礙綁定與 MediaProjection 授權，已確認 Bound services/Enabled services 為空、Media Projection 為 null。需使用者重新開啟無障礙、同意螢幕擷取並手動啟用自動辨識。
- 未執行蝦皮實際領取/上滑、跨午夜長時間觀察或 API 30+ 截圖路徑實機驗證；27 項測試是 CSC 內的真實 View 繪製測試，不等同在蝦皮 Accessibility overlay 的完整端到端驗收。
- 面板與辨識取樣或原有狀態標籤重疊時暫停顯示；不提供截圖排除或重疊仍常駐顯示保證。
- 日期回撥仍適用舊有資料裁剪政策。

## 回退

歷版 APK 與 v1.20 保留。必要時以 v1.20 原始碼建立較高 versionCode 的相容簽章回退版，保留既有 SharedPreferences；Android 不一定允許直接安裝低 versionCode 的正式 APK，不應以解除安裝清資料作為回退步驟。
