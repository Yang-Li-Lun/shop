# CSC 數字區域辨識穩定率與速度：工程分析與實作交接

日期：2026-09-05，Asia/Taipei。此輪為唯讀分析；未修改產品程式碼、測試原始碼、設定、版本或 APK。只新增本文件與 analysis/number-ocr-2026-09-05 診斷附件，並重新產生本機 Gradle 測試產物。

## 1. 結論與建議順序

目前最值得做的不是降低 OCR 門檻或縮短三幀確認，而是先修正區域座標一致性及數字證據品質，再減少完整視覺辨識對數字確認的阻塞。

1. **先修浮層框與截圖 ROI 的座標不一致**：LG G7 實測浮層為 720×1410、起點 (0,54)，截圖基準為 720×1560。相同比例在兩者畫出的區域不同。這是明確的幾何錯誤，修正時不可改寫使用者儲存的比例座標。
2. **修小數 token 組合與候選拒絕語意**：實際呼叫現有類別已重現小數點被拆開、鄰近獨立數字被誤合併。混合字串被直接拒絕、OCR 失敗被當成缺失也已由程式碼及既有測試確認。
3. **加入可觀察、禁止手勢的診斷方式，再做數字專用 ROI／Latin OCR 的 A/B**：目前「快速輪」仍使用中文字目標與數字區域的聯集，且因「領取」採用中文模型，並非純數字專用輪。
4. **處理返回箭頭計算及確認排程**：本次完整輪平均 1648.1 ms，其中返回箭頭平均 1151 ms，約占 69.8%；單純換 OCR 無法消除這段阻塞。
5. **最後改善 ROI 穩定判定、證據有效期限、有限度預處理與顏色品質判定**，均以錄製樣本及真機資料決定參數。

若新對話只做一項最小修改：**修正 ScreenAutomationService 浮層座標映射，保留全部既有 ROI 數值，驗證顯示框與實際 crop 邊界一致。** 下一項才是小數 token 與 Invalid 語意。

這些是待實作建議，不是本輪已完成的修復；目前無完整標註樣本，不能聲稱穩定率已提升到任何百分比。

## 2. 接手基線與禁止覆蓋事項

- Repository：D:\codee\shop
- HEAD：280d434b7d642341390e8b3bf28eab3ffd70fce1
- 提交：Restore CSC 1.12 recognition with current UI and deliver 1.14
- 版本：1.14 / versionCode 15；手機安裝版本相同。
- 依賴：bundled ML Kit Latin 與 Chinese text recognition，均為 16.0.1。
- 本輪開始已有未提交變更：
  - M AGENTS.md
  - D ENGINEERING_HANDOFF_CSC_RELIABILITY_2026-09-04.md
- 上述兩項完全保留；不得 checkout、restore、重建被刪除文件或覆蓋 AGENTS.md。
- 本輪新增：本文件、analysis/number-ocr-2026-09-05/。
- 未 commit、tag、push；未安裝或交付 APK，沒有觸發版本遞增需求。

**版本史是重要限制：** ENGINEERING_HANDOFF_CSC_1.14.md 與 docs/verification/CSC-1.14.md 明確記錄，1.14 是依先前需求回復 1.12 數字辨識語意，保留新版 UI、session、gesture watchdog 及 API 29 擷取保護。不要整批搬回 1.13 或把這次回復誤判成意外漏改。後續每項行為改變須獨立比較資料、說明取捨；目前沒有量化證據證明整套 1.13 優於 1.14。

檔案行號以下均以本輪 HEAD 為準；新對話應先確認 git status、HEAD 與檔案 hash，再使用行號。

## 3. 本輪實機觀察

### 3.1 裝置與設定

| 項目 | 本輪讀值 |
|---|---|
| 裝置 | LG LM-G710 / LG G7 |
| ADB serial | LMG710AWMff88f3c6 |
| Android | API 29 / Android 10 |
| Package / PID | com.example.csc / 9101 |
| 版本 | 1.14 / code 15 |
| Accessibility | ScreenAutomationService 已啟用且 bound |
| MediaProjection | com.example.csc，TYPE_SCREEN_CAPTURE active |
| 目前前景 | com.shopee.tw / HomeActivity_ |
| 螢幕 | physical 1440×3120；override 720×1560 |
| 自動化／數字監控 | enabled=true / number_monitor_enabled=true |
| 數字 ROI | left=0.68, top=0.22600001, right=0.795, bottom=0.35 |
| 合格範圍 | 0.19 至 3.0，含邊界 |
| 顏色 | #CABC37，tolerance=60，filter enabled |
| scan_interval | 900 ms；實際受 AdaptiveScanController 覆寫 |
| 無數字等待 | 6000 ms |
| 領取後延遲 | zone-1，6000 ms |
| 其他 | cooldown=2000 ms；random_click_max=1500 ms |

手機已由使用者停留在蝦皮直播且 CSC 正在執行。本輪只被動讀取當前状態、截圖和日誌，未發出 tap、swipe、keyevent、am start、stop、install 或修改設定，也未清除 logcat。不能把「我沒發送手勢」解讀成既有 CSC 自動化沒有自行動作。

設定快照 automation-before.xml 與 automation-after.xml 的 SHA-256 相同：
59CC288A6D57775313B94EB826797F232886E54BB0E45510156617F3F8B0977C

截圖 device-screen.png 顯示目標金幣值 0.3，CSC 浮層也顯示 0.3；這只證明該快照符合，不代表連續正確率、OCR 原始輸出或變值延遲已量測。浮層可能保留前次正常值。

### 3.2 效能樣本

取樣指令使用裝置時間 09-05 00:15:39.000 作下限，約於 00:16:07 完成讀取；保存的 20 筆 profile 第一筆 00:15:40.618，最後一筆 00:16:06.741。這是約 28 秒被動觀察窗口，20 筆資料涵蓋 26.123 秒。原始紀錄 device-profile.log；解析表 frame-metrics.csv。

分類依 profile 是否有 backArrow timing；本次每個完整輪都有返回箭頭辨識，每個快速輪皆無。此分類不能直接推廣到任意設定或無圖形目標的裝置。

| 指標 | 樣本數 | 最小 ms | 平均 ms | 最大 / nearest-rank P95 ms |
|---|---:|---:|---:|---:|
| 快速輪 total | 10 | 207 | 243.0 | 270 |
| 快速輪 OCR | 10 | 162 | 197.4 | 217 |
| 快速輪 capture | 10 | 17 | 27.6 | 35 |
| 完整輪 total | 10 | 1569 | 1648.1 | 1758 |
| 完整輪 OCR | 10 | 201 | 223.6 | 264 |
| 完整輪 Circle-X | 10 | 216 | 224.1 | 250 |
| 完整輪返回箭頭 | 10 | 1080 | 1151.0 | 1236 |
| 完整輪 capture | 10 | 15 | 23.1 | 37 |

每組只有 10 筆，所以 nearest-rank P95 等於最大值；不是長時間基準。

主要解讀：
- 返回箭頭是此場景最大的計算成本，Circle-X 次之；OCR 約 0.2 秒。
- 完整輪順序是 Circle-X → OCR → 圖片／箭頭。數字在 OCR callback 已可觀察，**不能把完整 total 全部稱為當次數字顯示延遲**；但 processing 在後段完成前仍為 true，下一次截圖／OCR 會等待。
- log 只量測各輪處理耗時，缺少 frameId、擷取影像 timestamp、數字回報時間和真值變化時間，不能直接得到端到端反應延遲。
- API 29 的 bitmapConversionMs 顯示 0，不等於沒有轉 Bitmap 成本；API 29 的轉換包含在 capture 時段，該獨立欄位主要由 API 30+ 路徑填寫。
- 初讀 PSS=117282 kB，後讀=107007 kB；短觀察不構成記憶體洩漏測試。
- 觀察區间中，PID 9101 未讀到 AndroidRuntime error。不是所有錯誤類型或長時間無崩潰證明。

## 4. 現行數字處理流程與定位

原始碼基底：app/src/main/java/com/example/csc/

| 位置 | 職責 |
|---|---|
| automation/ScreenAutomationService.kt:199 | scanOnce；前景／設定／狀態檢查、掃描排程 |
| 同檔:258 | 領取 accessibility 節點優先，命中可直接進點擊，該輪不進 OCR |
| 同檔:290 | API 29 MediaProjection 或 API 30+ accessibility screenshot |
| 同檔:377 | 全畫面 24×24 fingerprint gate；完整輪／快速輪選擇 |
| 同檔:430 | OCR 裁切、模型選擇、非同步 success/failure |
| 同檔:556 | 優先 Circle-X |
| 同檔:685 | 圖形區域與返回箭頭辨識 |
| 同檔:1205 | OCR 元素 → tokens → ROI／顏色 → 候選選取 → tracker |
| 同檔:1396 | OCR failure 轉 Observation.Invalid |
| 同檔:1441 | tracker action → 顯示、重辨、等待或滑動 |
| 同檔:1493 | 所有文字 ROI 與數字 ROI 聯集，各軸加 0.02 padding |
| 同檔:1512 | 數字候選框顏色覆蓋採樣 |
| 同檔:1599 | 250 ms 後要求新數字觀察 |
| 同檔:1758 | 排程上滑、foreground/session guard、gesture |
| 同檔:2252 | crop 與 offset；以完整 Bitmap 尺寸換算比例 |
| 同檔:2332、2678 | 浮層視窗與比例框繪製 |
| 同檔:2498 | FrameProfile；現有時序可觀察性不足 |
| automation/AutomationConfig.kt:150、198、258 | 小數擷取、token 重建、候選選取 |
| automation/NumberMonitorTracker.kt:47、141 | 判定狀態、三幀風險確認 |
| automation/NumberRegionFingerprint.kt:7 | 32×32 量化亮度 hash |
| automation/AdaptiveScanController.kt:12、57 | 穩定畫面跳幀及 250/500/600/800 ms 節奏 |
| capture/MediaProjectionCaptureService.kt:133、151、199 | ImageReader、ARGB 轉換、request／timeout |
| vision/BackArrowDetector.kt:89、120 | 粗搜尋及局部精搜迴圈 |

流程：

~~~text
scanOnce / 前景與 ActionStateMachine
  → capture（API 29 / API 30+）
  → 全畫面 fingerprint gate
  → 完整輪：Circle-X → 中文或 Latin OCR → 圖形／返回箭頭 → finish
    快速輪：           中文或 Latin OCR                   → finish
                           ↓
                  elements → rebuildNumberTokens
                           ↓
                  完整包含 ROI + 顏色覆蓋
                           ↓
                  最靠近 ROI 中心的一個候選
                           ↓
             Value / Missing / Invalid → tracker
                           ↓
                   顯示／新觀察／手勢仲裁
~~~

注意：selectNumberMonitorValues 最終只回傳一個值；後續 maxOrNull 不表示目前會取所有候選最大數字。NumberSwipeConfirmation 雖另有預設兩幀 helper，service 真正使用 NumberMonitorTracker 的三幀且至少 500 ms。

## 5. 問題、證據及實作設計

### P1-A：浮層框不等於實際辨識範圍【實機＋程式確認】

證據：
- overlay-window.txt：Requested w=720 h=1410，mFrame=[0,54][720,1464]。
- MediaProjectionCaptureService:92–97 以 getRealMetrics 建立 720×1560 capture。
- ScreenAutomationService:2684–2687 直接以 overlay View 的 width／height 乘上 region。
- crop:2257–2262 則以完整 Bitmap 的 width／height 乘上相同比例。

數字 ROI 的未取整幾何：
- 實際 crop：x=489.6…572.4；y=352.56…546。
- 浮層顯示：x=489.6…572.4；y=54 + 1410×0.226…54 + 1410×0.35，即 372.66…547.5。
- 上緣偏低約 20.1 px，框高也由 193.44 變為 174.84 px。
- crop 實作取整後為 [489,352] 到 [573,546)，84×194 px。

影響：使用者依浮層框調 ROI，可能誤以為標籤或邊界不在辨識區。此快照 0.3 同時在兩個範圍內，不能把當時正確辨識解讀成座標沒有問題；也不能宣稱已證明所有漏讀都由此造成。

設計：
- 定義唯一 capture/screen 座標基準，再用 view.getLocationOnScreen 與實際 View 尺寸作座標轉換。
- 或使 overlay 真正涵蓋完整螢幕，但需實測 system bars／cutout／旋轉；不可只加某個 flag 後假設已完成。
- 建議先只修顯示映射，保留 numberMonitorRegion、zones JSON 與 gesture 安全檢查。
- 同步檢查 click marker、區域 label；不得以修改 ROI 預設值「補償」54 px。

驗收：
- API 29 三鍵導覽下 crop 四邊與 overlay 四邊相差 ≤1–2 px。
- API 30+、手勢導覽、方向或尺寸變更另外驗證；此輪只有 API 29。
- 四角、邊界數字與靠近系統欄的區域都要測試。

### P1-B：小數點拆散與相鄰數字誤合併【實際類別探針重現】

AutomationConfig.kt:238–248：
- 所有元素都要求最大／最小高度比 ≤1.8，包含小數點。
- horizontalGap 可達 maximumHeight×1.5，兩個已完整的數字仍可能拼接。
- 現有 splitDecimalElements 測試把小數點框設成與数字同高，沒覆蓋真正低矮點號。

logic-probes-verified.txt 是剛重編譯現有 class 的實際呼叫結果：
1. 0 框 (10,10)-(20,30)，點號框 (21,26)-(24,30)，2 框 (25,10)-(35,30)：
   輸出 tokens=[0, ., 2]，未得到 0.2。
2. 0.2 框 (10,10)-(30,30)，3 框 (40,10)-(50,30)：
   輸出 token=0.23，兩個獨立候選被合併。
   
第二種可能把原本應超上限或屬其他 UI 的數字變成正常值。這是合成幾何案例，尚未取得手機實際 ML Kit element boxes，不能宣稱本次直播已出現同樣誤讀。

設計：
- 對小數點採獨立幾何規則：位於相鄰數字間、靠近 baseline、水平間距以字寬比例限制；不能套用一般字元高度比。
- 加入有限語法狀態，只允許一個小數分隔符、合法符號位置。
- 謹慎處理「0.2」＋「3」：既可能是被拆開的 0.23，也可能是兩個獨立數字。靠緊密字距／symbol 框／既有候選一致性判斷；無法確定時回 Ambiguous，不猜。
- 不可全 line 移除空格或任意串接。
- 組合後重新檢查數值及 bounds，不以閾值期望反推 OCR 答案。

### P1-C：失敗、格式不明、顏色拒絕都可能變成缺失【程式與測試確認】

NumberMonitorTracker:63–83 把 Invalid 及不合法 Value 取成 null，除非與 lastKnownGood hash 完全相同，便進 observeMissing。
既有 invalidOnChangedRoiUsesVersion112AbsenceConfirmation 測試明確固定：Invalid → 等待 → deadline → Invalid → SWIPE_ABSENT。

observeNumbers:1259、1284–1323：
- ML Kit 成功，但混合字串被丟掉、候選跨邊界、顏色不符，最後同樣 values.empty → Missing。
- 所以「無數字」「OCR 看見字但解析不了」「框／顏色品質不足」目前不能區分。

設計：
- 至少分 Value、Missing、Invalid；建議將 Ambiguous／FilteredOut 記為 Invalid 的原因碼。
- OCR Task failure、解析歧義、框缺失／裁切、不充分顏色證據：不增加缺失計數，取消原缺失動作證據並要求新觀察。
- Missing 應來自成功且可用的 ROI 觀察，確認沒有合格數字證據。顏色不符是否代表合法缺失，要用資料制定政策，不能把所有色彩拒絕一律當永久 Invalid，否則沒有符合顏色數字的頁面可能永遠停住。
- 連續 Invalid 須有有界退避、狀態提示，避免 250 ms 無限忙迴圈；保留停止入口，不自動猜值。
- Invalid 插入 Missing 序列、deadline 後 Invalid、Invalid 後 recovery 都要重新測試。

這會改變 1.14 刻意保留的舊版行為。應在新實作中明確記錄「辨識失敗不再等於缺失」，不能只改測試名稱掩蓋行為變更。

### P1-D：混合文字直接被拒絕【程式與測試確認】

AutomationConfig:200、216、231–235 只接受整個 element 都是數字／符號。
ScreenAutomationService:1259 僅在 elements 完全不存在才回退 line；有 elements 但全部被拒絕時不使用 line。
既有 mixedOcrElementsAreRejectedLikeVersion112 確認 S0.2、幣0.2、0.2元 都被排除。

設計：
- 限定數字子片段擷取；優先用 ML Kit symbols 的數字實際 bounding box。
- 若僅有整個混合 element 框，標記低信心且禁止跨 ROI 放寬，不可把整行顏色冒充數字字形顏色。
- 不做 S→5、O→0 等無依據替換。
- 只容許有明確数字語法的片段；多值混合與字母相鄰案例加入負樣本。
- 這是對既有行為的待驗證變更，應與其他修正分批 A/B；不整套恢復 1.13。

### P1-E：完整視覺輪阻塞下一次數字確認【實機計時＋程式確認】

- scanOnce:243：processing 為 true 就不開始新辨識。
- 完整輪先 Circle-X，再 OCR，再返回箭頭等工作。
- WAIT_FOR_CONFIRMATION 雖排 250 ms，無法中斷已在執行的視覺工作。
- shouldRunVisualSafetyScan 以 2500 ms 間隔及 numberPriorityPassPending 保證完整轮後有快速輪；不保證每 250 ms 都能獲得新數字。
- 本次返回箭頭每輪約 1.08–1.236 秒；ROI 內直播背景持續变化，exact fingerprint cache 未呈現可見節省。

實作分段：
1. 先增加排程／decision 時間欄位，量測確認被阻塞的比例。
2. 新確認需求應在本輪安全結束後優先處理；合併重複需求，最多保留一個最新需求。
3. 返回箭頭先做低成本候選定位，再对有限候選做原幾何評分，或用分塊工作讓數字確認可以在安全邊界先執行。
4. 保留 bounded visual scan 與最新 visual hold 證據；不能把「這輪沒跑圖形」直接當成「圖形沒有命中」。
5. 不新增第二套手勢狀態。若允許 OCR 與視覺交錯，所有 action 仍由 ActionStateMachine 與 session 統一裁決，Bitmap ownership 必須明確。

BackArrowDetector:89–107 粗搜、120–142 精搜及 MAX_FULL_EVALUATIONS=512 是效能實驗入口。粗搜每個頂點／跨度／斜率均有成本；需保留方向、negative space、外圈 isolation 與邊界測試。不能只提高 threshold、縮減使用者 ROI 或關閉箭頭來當效能修復。

### P2-A：快速輪仍是聯集 ROI＋中文 OCR【程式＋設定推導】

recognizeBitmapAfterPriority:438–459 即使 skipVisualSafetyScan=true，仍收集所有 TEXT 目標；因有「領取」，選 chineseRecognizer。

本次設定推得：
- OCR 聯集含 padding：left≈0.642037, top≈0.206, right=1, bottom≈0.388519。
- 在 720×1560 上 crop 約 [462,321] 到 [720,607)，258×286=73788 px。
- 原數字 ROI 為84×194=16296 px。
- 若數字専用路徑同樣每軸加0.02 padding，約 [475,321] 到 [587,578)，112×257=28784 px。
- 因此專用 padded ROI 較目前聯集少約61%像素；這是幾何推導，不能推導相同比例的速度提升。

設計：
- 明確區分 Number OCR 與文字 target OCR。
- 數字快速觀察使用緊 ROI／Latin 模型；文字依原中文字模型和安全掃描需求執行。
- 先順序 A/B，不立即每輪雙模型平行跑；避免總 CPU／記憶體和排程反而增加。
- model 實例維持重用。
- OCR padding 可超出數字 ROI 取得上下文，但接受數字的 bounds 仍須滿足安全策略。
- 需測試 symbol 合併與 ROI offset，若 resize：screenX=cropLeft+ocrX/scale，screenY=cropTop+ocrY/scale。

Google 官方指出 Latin script 通常較快，小圖可降低延遲，但需保留足夠字元像素；建議字元至少約16×16，通常超過24×24不再增加準確度。本案字高尚未由原始 OCR 框量測，不能直接決定縮放倍率。
來源：[Google ML Kit Android 官方指南](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)（本輪查閱）

### P2-B：ROI exact hash 與全畫面 gate 不能代表數字新鮮度【程式＋探針確認】

- numberRegionFingerprint 是32×32量化亮度取樣後雜湊，只有相等／不等，不能衡量相似程度。
- 本機探針：100×100全黑 ROI 加入 (50,50) 一像素白點，兩個 hash 仍相同，因該點沒被取樣。這是「未取樣」，不是密碼學碰撞。
- 動態背景／輕微位移會讓 hash 不同，即使字值不變；稀疏小數點變化也可能剛好未取樣。
- tracker:75 只要 hash 與先前正常值相同，就拒絕後續風險值／缺失；LastKnownGood.observedAtMs 未用於期限判斷，可能長時間保留。
- 全畫面24×24 gate 先於ROI；小區域变化可能沒打到取樣，穩定時直到每第三次 unchanged 才重辨。直播背景又可能讓每輪都 changed，節流不起作用。

設計：
- 數字 freshness gate 先看數字 ROI；全畫面 gate 只影響非數字工作。
- 使用字形／前景 mask、亮度變化或 edge 的可解釋相似度；不能僅把整個 ROI 壓成過粗平均特徵，以免 0.2 / 0.02 等差異被視為不變。
- lastKnownGood 的保護須有短期有效期限，並要求當前仍有可靠字形證據；過期後回到 fresh observation，不強行觸發。
- 相似度只用於短期抗抖與重辨策略，不直接授權手勢，也不能用重用 OCR 結果充當新幀確認。
- 先補小數點正負樣本，再決定 signature；不要直接回復舊1.13參數。

### P2-C：三次觀察沒有最大時間間隔與候選身分【實際類別探針＋程式確認】

NumberMonitorTracker:149–162 只检查方向、數值容忍和最短500ms；不檢查前後觀察最大間隔、同一候選位置、影像 timestamp。
探針在0、30000、60000ms提交同樣0.10，得到 WAIT、WAIT、SWIPE_LOW。

設計：
- 證據包含 frameId、captureTimestamp、candidate bounds／identity、generation。
- 設定可測量的 maxObservationGap／maxEvidenceAge，過期重置，不把任意長時間的三次讀值稱為連續確認。
- 相同影像不能累加幀數；可在不同新 frame 上讀取相同值以累計。
- deadline 到後的新 OCR 不等於影像在 deadline 後：API29 request 未傳出 Image.timestamp。需定義並驗證時間基準，拒絕舊影像，不只是用 callback 到達時間。
- 保留三次且至少500ms為初始基線，先消除阻塞與錯字，再評估是否需要改確認政策。

### P2-D：顏色、候選選取與顯示品質【程式確認；實際影響待量測】

- numberBoundsContainColor 在主執行緒 success listener 做 bitmap.getPixel 雙迴圈；ROI hash與候選建立也在該 callback。
- 顏色規則是候選 bbox 內 matches≥max(8,floor(samples×0.015))，不是「數字筆畫確定為指定顏色」；背景色或圖示可能通過。
- 候選只按 ROI 中心距離、同距離最小面積，沒有 glyph confidence、上一候選位置或多值歧義判斷。
- 正常範圍 Value 單幀立即 STAY 並更新 confirmedNumberDisplay；名稱含 confirmed 不代表正常數字已經多幀確認。
- 浮層本身出現在 API29 螢幕截圖，且數字 label在本次 padded OCR區域附近。存在自我文字／線框干擾可能；尚未取得 CSC 實際OCR Bitmap對照，不能斷言已回讀自己的0.3。

設計：
- 在現有影像executor執行crop、批次getPixels、ROI特徵、候選解析與顏色統計，main僅套用不可變結果、UI與action。
- 先量測再優化，因本次主因是箭頭，不是這些幾毫秒的局部工作。
- 顏色逐步轉為字形區域品質分數，保留既有hex與tolerance；不擅自改成寬鬆HSV或提高容忍。
- 候選追蹤先幾何對應再比較數字；數字閾值不可用來選出「想要的答案」。
- UI區分當前讀值、穩定顯示值、重辨狀態與資料年齡；不要永久顯示過期數字讓使用者誤以為持續讀取成功。
- 先固定座標，再將診斷label避開所有OCR讀取區域；對擷取中的overlay做實機驗證，不假設API29會排除。
- 有條件fallback可先試原圖，僅遇低品質時再試小範圍放大／對比調整一次；不得每幀全畫面多倍率、多模型或暴力二值化。小數點必須保留。

## 6. 後續實作分批與檔案範圍

| 批次 | 工作與可能檔案 | 完成條件 |
|---|---|---|
| A | ScreenAutomationService 浮層映射；必要時獨立純座標 helper及測試 | 真機框/crop一致；設定不變 |
| B | 只觀察模式、FrameProfile/數字結構日誌；最小必要 UI入口 | 沒有任何gesture dispatch、統計遞增或延遲手勢；可停止 |
| C | AutomationConfig token/parser；NumberMonitorTracker Invalid及歧義政策；相應測試 | 小數/混合/缺失/恢復正負案例通過 |
| D | 數字专用OCR與排程、AdaptiveScanController；保留文字圖形仲裁 | 同樣本A/B證明延遲降低且辨識無倒退 |
| E | BackArrowDetector候選搜尋／分塊；原正負樣本 | 箭頭耗時降低且安全樣本無退步 |
| F | ROI特徵與證據期限、顏色品質、必要fallback | 真實樣本量化達標 |

B應是runtime-only或安全預設的新診斷設定，不能把targetPackage改成其他套件來繞過原安全邊界。只觀察模式也要覆蓋accessibility文字快捷點擊、OCR點擊、圖形點擊、一般滑動、領取後滑動及延遲callback。應記錄would-act，但不消費真實手勢成功統計。不得僅阻擋某一個OCR分支就稱為只觀察。

本輪未建立此模式，因此沒有主動製造低值、高值、缺失、點擊領取或換頁場景。新對話應先讓測試可以安全重播，再驗證會引發動作的數值。

## 7. 測試與量化驗收

### 7.1 本輪已完成

執行原有測試，沒有新增／改寫測試原始碼：

~~~powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
.\gradlew.bat --no-daemon --offline --rerun-tasks testDebugUnitTest --tests 'com.example.csc.automation.NumberMonitorTrackerTest' --tests 'com.example.csc.automation.NumberRegionFingerprintTest' --tests 'com.example.csc.automation.RecognitionZoneTest' --tests 'com.example.csc.automation.AdaptiveScanControllerTest'
~~~

結果：BUILD SUCCESSFUL in 41s；24 actionable tasks，24 executed。

| 類別 | tests | failures/errors |
|---|---:|---:|
| NumberMonitorTrackerTest | 12 | 0/0 |
| NumberRegionFingerprintTest | 2 | 0/0 |
| RecognitionZoneTest | 30 | 0/0 |
| AdaptiveScanControllerTest | 3 | 0/0 |
| 合計 | 47 | 0/0 |

XML已另存分析附件，避免後續測試覆寫。通過只表示符合現有行為；其中包含固定1.12錯誤／缺失語意的測試，不能據此聲稱新需求已被滿足。

另外，以JShell explicit URLClassLoader對剛編譯類別執行四個合成邏輯探針，輸出在logic-probes-verified.txt。初次JShell classpath執行失敗的logic-probes.txt只保留診斷紀錄，其所有數值不可作證據；成功檔沒有class loading錯誤。

未跑完整suite、lint或assemble，因本輪沒有產品變更／APK交付；沒有將舊測試報告當本輪結果。

### 7.2 後續需新增的測試

| 類型 | 必須包含 |
|---|---|
| 座標 | 720×1560 vs overlay(0,54,720,1410)、旋轉、insets、resize offset、四邊界 |
| token | 真實矮小數點、逗號、小數點分裂、符號、0.2與旁邊3、0.2元、幣0.2、S0.2、多個数字、無symbol框 |
| 缺失 | Value→Invalid、不可靠ROI Invalid、Missing→Invalid→deadline、deadline後Invalid、Invalid恢復 |
| 候選 | 色彩背景但非字形、跨ROI、中心附近多值、位置切換、額外金幣圖示／倒數計時 |
| fingerprint | 動態背景不變字、字位移、抗鋸齒、0.2/0.02/0.3/3.0/30、小數點變動、ROI外變動 |
| tracker | 3新幀且≥500ms、重複frameId、30秒間隔、過期正常值、generation變更、priorityPending |
| 排程 | 完整視覺工作>2500ms、確認期間、掃描跳幀、晚callback、停止、切app、權限失效 |
| 回收 | 每條success/failure/timeout/stale結果Bitmap／HardwareBuffer只釋放一次 |
| 只觀察 | 所有dispatch入口皆不觸發、延遲callback不漏出、統計不假增、停止可用 |

先單一受影響測試，再完整核心測試。涉及排程／capture／gesture或交付APK時擴大lint、build與實機驗證，遵照AGENTS.md。

### 7.3 數據定義與建議門檻

在可停止且禁止手勢的場景，用相同ROI／設定／畫面序列做baseline與新版本對照。先錄固定樣本與人工真值，再各跑至少5分鐘實機只觀察；避免拿不同直播內容直接比較。

需要紀錄：
- frameId、captureTimestamp、capture尺寸、crop rect、模型、scale、OCR時間。
- 原始text／element或symbol bounds、color sample/match/coverage、拒絕原因。
- 選中值、candidateId、ROI特徵、Observation、tracker action、visual hold與session/generation。
- capture→OCR decision、數字真值變動→正確穩定顯示、確認排隊等待、would-act時間。
- frame/text只存指定ROI與必要樣本；日誌本機保存，不上傳完整畫面作辨識。

分開報告以下指標，不用單一「穩定率」掩蓋錯誤：
1. 可讀ROI的每幀精確值正確率。
2. 漏讀率及Invalid率；按原因分類。
3. 真值固定時顯示跳動次數／分鐘。
4. 真值變更到正確穩定值的P50/P95。
5. 誤觸發would-swipe次數／合法停留樣本；漏觸發另計。
6. Number-only成本、完整視覺成本、confirmation wait、CPU/PSS。

初始驗收目標（待實測可調，不是本輪達成值）：
- 座標四邊差≤1–2px，設定快照完全保留。
- Invalid／歧義／重複舊幀均不得作為缺失滑動證據。
- 標註正常值、邊界值與OCR失敗資料集，誤滑動決策為0；必須同時報總樣本數。
- 同資料集精確值正確率不低於1.14 baseline，主要小數／混合字串正樣本不再被規則性丟棄，負樣本不被錯接。
- 同機number-only warmed P95先以≤200ms作實驗目標；目前快速輪P95=270ms。若無法達到，應報實際結果及成本分解，不犧牲正確率。
- 三幀確認完成的端到端P95以≤1.5s作初始目標，並獨立報告真值起始定義及樣本量；目前尚無此指標baseline。
- 返回箭頭完整輪成本先要求明確下降，且原幾何正負樣本無退步；不預設關閉或減少安全掃描。
- 保留3次／500ms、原閾值及原6000ms行為時間，除非新資料支持明確行為變更。

## 8. 手機、建置與交付約束

- ADB：D:\codee\abc\.android-tools\sdk\platform-tools\adb.exe
- SDK由local.properties取得，不複用其他專案設定。
- 每次Gradle先建立app/build/uds並設定jdk.net.unixdomain.tmpdir，使用--no-daemon。
- 不為IPC問題改TEMP/TMP、JDK、hosts、Android程式或依賴。
- 新對話若交付APK：依當時AGENTS.md，只交付arm64-v8a；版本從1.14遞增小版本／versionCode；新APK保存APK/含版號檔名；不得覆蓋舊APK。
- 交付需對應commit與版本tag，安裝目前授權裝置、啟動、基本行為及崩潰檢查後完成；本輪沒交付APK。
- API29 replacement install可能需要重新授權Accessibility／MediaProjection，必須由使用者操作系統授權，不用ADB改secure settings繞過。
- 修改必須保留zone、threshold、number monitor、image references、profile seed與持久keys。
- targetPackage完全匹配、session/generation、延遲後再檢查、停止入口和安全區不可削弱。
- 不推送本地畫面、設定或任何文件到外部，除非使用者另外明確授權目的地與內容。

## 9. 附件清單與可重現性

資料夾：analysis/number-ocr-2026-09-05/

| 檔案 | 用途 |
|---|---|
| device-profile.log | 本輪20筆CSC_FRAME_PROFILE原始資料 |
| frame-metrics.csv | 每筆timing解析，可重新計算分組統計 |
| device-screen.png | 一次實際畫面，0.3與浮層位置視覺佐證 |
| overlay-window.txt | 浮層720×1410與起點54的WindowManager證據 |
| automation-before.xml / automation-after.xml | 前後設定一致證據 |
| TEST-*.xml | 本輪四類47項測試的原始結果 |
| logic-probes-verified.txt | 已成功的四項純邏輯重現 |
| logic-probes.txt | 初次classpath失敗紀錄，禁止引用其中數值 |
| source-hashes.csv | 五個主要分析檔案的SHA-256 |
| verification.txt | 裝置與驗證範圍摘要 |

重現profile读取範例（新對話自行替換裝置時間，不要沿用歷史時間）：

~~~powershell
$adb='D:\codee\abc\.android-tools\sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb -s LMG710AWMff88f3c6 shell date
& $adb -s LMG710AWMff88f3c6 logcat -d -T 'MM-DD HH:MM:SS.000' -v threadtime CSC_FRAME_PROFILE:I AndroidRuntime:E '*:S'
~~~

## 10. 可直接貼給新對話的工作指令

> 請讀取 D:\codee\shop\AGENTS.md、ENGINEERING_HANDOFF_NUMBER_OCR_2026-09-05.md 及 analysis\number-ocr-2026-09-05 的證據。以當前HEAD為準，先核對與280d434b7d642341390e8b3bf28eab3ffd70fce1的差異。保留既有AGENTS.md變更、舊文件刪除狀態、手機zones／閾值／數字監控／圖片參照。先做最小的浮層與截圖ROI座標一致性修正，再按文件分批處理診斷模式、小數token、Invalid／缺失、數字專用OCR與完整視覺阻塞。不要整包恢復1.13。每批先跑對應測試並說明行為改變；以只觀察模式及同一組標註樣本A/B驗證，不將build成功視為準確率提升。若交付APK，遵守arm64、版號、保存、commit/tag、授權手機安裝與實機驗證規則。報告修改檔案、測試結果、實測速度、正確率分母及未驗證範圍。

