# CSC 1.18 驗證紀錄

日期：2026-09-08。由 1.17 / versionCode 18 更新為 1.18 / versionCode 19。

## 交付

- 套件：`com.example.csc`，ABI：`arm64-v8a`。
- APK：`APK/CSC-1.18-arm64-v8a.apk`；Git tag：`v1.18`（本地）。
- APK SHA-256：`36e6cc1055d6b48762c84cd9ad9db0cc94ad76c6cdc6dfe752696c7b4677cc16`。
- apksigner 驗證通過，沿用原 debug 簽章，覆蓋安裝成功。
- 保留既有 AGENTS.md 修改、交接文件與 analysis 附件，未將它們納入本次提交。

## 修正

1. 數字監控使用獨立 ROI 與 Latin OCR；保留 8 像素上下文以識別跨界字形，小區域放大三倍，座標依比例還原。影像預處理使用既有 vision executor，文字目標仍使用原始影像。
2. 指定色遮罩加入色相方向檢查，排除灰白色／異色；候選驗證檢查字形覆蓋、高度及框內外背景。避免只靠少量符合色像素放行。
3. 保留小數點像素；OCR 漏點時只根據相鄰數字間確實存在的緊密、低位指定色像素補點。拒絕 0.、02 等疑似截斷／漏點結果，不直接當整數；允許 0. + 2 正確合併。
4. 不再讓領取區本身的文字命中遮蔽缺失／錯誤數字判斷；其他辨識區安全攔截仍保留。
5. ActionStateMachine 記錄新頁觀看時間與最近合格數字。正常模式先上滑開始新頁，成功手勢後加上畫面穩定時間再起算；至少六分鐘、最近三秒有合格數字、且辨識到可領取文字才可點擊。七、八分鐘或更久才出現按鈕時持續等待，沒有固定六分鐘強制領取。
6. 排除倒數／已領取文字；點擊延遲後重新檢查領取資格，OCR 失敗取消資格；換頁清除數字與觀看證據。沿用既有 target package、session、generation、區域與手勢保護。

## 驗證

- 最終 `testDebugUnitTest lintDebug assembleDebug -PtargetAbi=arm64-v8a` 成功。
- 116 項 JVM 測試，0 failures / 0 errors。涵蓋小數解析與像素補點、字色、背景、觀看下限、七／八分鐘、數字過期與換頁重設。
- Lint：0 errors / 50 warnings；並非零警告交付。
- LG LM-G710 / Android 10 (API 29) 覆蓋安裝成功。
- 最終 APK 上執行自訂 instrumentation：14 項合成影像 OCR 案例全部通過；16px／32px 的 0.2、1.2、0.3、12、白字、紅字與指定色背景上的白字。測試直接使用正式影像預處理與候選字色驗證；測試套件已移除。
- 早期裝置測試實際重現「指定色背景上的白字」被接受，補上框外背景證據後通過；不是僅以編譯結果判定修復。
- MainActivity 冷啟動成功（TotalTime 1369 ms），最終前景為 CSC。UI dump 無法取得 idle state，改以截圖檢查主畫面、設定區與系統列間距。
- 最終啟動後 CSC PID 日誌無 FATAL／Exception／ANR；crash buffer 僅有 23:36 的舊 am/instrument 啟動階段 native 記錄，早於 23:45 最終啟動。
- automation.xml 前後 SHA-256 相同：`84c2fc8dfe3f3ebfb71135d32f4f1718ba4b89325bbd81929e44066b4ad83328`。未改 zones、色碼、容差、門檻、目標套件等設定。

## 限制與重新授權

最終無障礙 Enabled/Bound services 為空，MediaProjection 為 null；主畫面顯示「請開啟無障礙」。需使用者在手機重新開啟 CSC 無障礙並同意螢幕擷取。本輪未代替同意，也未操作 Shopee 實際領取或換頁。

裝置 OCR 是合成影像回歸，不能代表所有直播背景、字型與壓縮情境的準確率。真實直播七／八分鐘觀看、按鈕狀態與換頁完整 E2E 尚未驗收；目前觀看計時依 CSC 成功上滑及穩定延遲起算，無法直接證明伺服器實際累計觀看時間。可領取文字、時間與數字條件共同限制點擊。

本次僅建立本地 commit/tag，未推送或上傳 GitHub。
