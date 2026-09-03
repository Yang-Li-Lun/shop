# CSC 1.12 大型風險與數字辨識可靠性工程交接文件

文件日期：2026-09-04  
專案路徑：`D:\codee\shop`  
目前 Android 套件：`com.example.csc`  
目前版本：`versionName 1.12`、`versionCode 13`  
文件用途：供新 Codex 對話在不遺失既有設定、安全邊界與驗證證據的前提下，直接開始後續程式碼修正。

---

## 1. 執行摘要

目前 CSC 1.12 沒有發現明顯的遠端入侵型重大漏洞，但仍有一項高風險自動化安全缺口，以及數項可能造成誤上滑、辨識狀態跳動或服務永久卡住的可靠性問題。

最重要的結論如下：

1. 第一優先不是再調整 OCR、顏色容差或門檻，而是完成跨截圖、辨識、延遲動作與 callback 的 `AutomationSession/generation`、過期結果拒絕及 timeout。
2. 目前 `NumberMonitorTracker` 已比 1.11 安全：低值／高值需要三次且跨至少 500 ms；無數字期限到達後要求 fresh observation；優先上滑期間抑制一般數字動作。
3. 但 OCR `Invalid` 目前仍被當成 `Missing`，可能把辨識引擎失敗誤當成真正無數字。
4. ML Kit 若把數字和符號／文字放在同一個 element，例如 `S0.2`、`幣0.2`，目前候選重建可能直接丟棄該 element，形成「辨識到／辨識不到」跳動。
5. 數字 ROI fingerprint 現在是 32×32 稀疏取樣後的單一 FNV hash，只能判斷完全相等，不能穩健判斷畫面是否「實質相同」。
6. Android 10 `MediaProjectionCaptureService` 只有單一 pending callback，沒有 request ID、projection generation 或 timeout，直接影響目前測試手機。
7. 本輪分析沒有修改任何程式碼，也沒有在購物 App 執行真實點擊或上滑。

目前版本不宜宣告為「可長時間無人看管穩定執行」。

---

## 2. 本文件涵蓋範圍

### 2.1 已完成

- 檢查目前工作樹、核心辨識流程、數字 tracker、fingerprint、手勢排程、Android 10 擷取服務及 Manifest。
- 強制重新執行完整 JVM 單元測試。
- 執行 Lint 與 arm64 Debug build。
- 以 ADB 唯讀檢查手機版本、安裝 APK 雜湊、現有設定、Accessibility、MediaProjection、服務、記憶體與 crash log。
- 檢查既有實機畫面證據。

### 2.2 刻意未做

- 未修改 Kotlin、Gradle、Manifest、測試或設定。
- 未安裝或替換 APK；查詢時手機已安裝 1.12。
- 未停用或改寫使用者現有設定。
- 未切換回 `com.shopee.tw` 執行自動化。
- 未建立線上 CVE／依賴漏洞資料庫掃描結論。
- 未以單張截圖臆測調整顏色容差或 OCR 門檻。

---

## 3. Repository 與工作樹基線

Git HEAD：

```text
26c6888 Remove root APK copies
```

分析開始及結束時，原始碼工作樹已有下列使用者既有修改／新增內容：

```text
 M README.md
 M app/build.gradle.kts
 M app/src/main/java/com/example/csc/MainActivity.kt
 M app/src/main/java/com/example/csc/automation/AutomationConfig.kt
 M app/src/main/java/com/example/csc/automation/DailyTriggerStats.kt
 M app/src/main/java/com/example/csc/automation/ScreenAutomationService.kt
 M app/src/test/java/com/example/csc/automation/DailyTriggerStatsTest.kt
 M app/src/test/java/com/example/csc/automation/RecognitionZoneTest.kt
?? APK/CSC-1.12-arm64-v8a.apk
?? app/src/main/java/com/example/csc/automation/NumberMonitorTracker.kt
?? app/src/main/java/com/example/csc/automation/NumberRegionFingerprint.kt
?? app/src/test/java/com/example/csc/automation/NumberMonitorTrackerTest.kt
?? app/src/test/java/com/example/csc/automation/NumberRegionFingerprintTest.kt
```

重要：這些變更不是本輪建立的。新對話必須將它們視為目前 1.12 基線，不可用 `git checkout --`、`git reset --hard` 或其他方式刪除。

本文件本身是分析後新增的唯一交接文件。

---

## 4. 裝置與 APK 基線

### 4.1 裝置

- 製造商：LGE
- 型號：LG G7 / `LM-G710`
- Android：10 / API 29
- ADB 狀態：已授權
- Accessibility：`ScreenAutomationService` 已 enabled 且 bound
- MediaProjection：有效，`MediaProjectionCaptureService` 為前景服務
- 查詢時前景：CSC `MainActivity`

### 4.2 已安裝 APK

- `versionName=1.12`
- `versionCode=13`
- `targetSdk=36`
- APK 為 Debug build，套件 flags 包含 `DEBUGGABLE` 與 `ALLOW_BACKUP`

下列三者 SHA-256 相同：

1. 手機 `/data/app/.../com.example.csc.../base.apk`
2. `app/build/outputs/apk/debug/app-debug.apk`
3. `APK/CSC-1.12-arm64-v8a.apk`

完整 SHA-256：

```text
3d0e250ebf49a996da807bf81254940c79526d15e7a94703539b0a254e93d0b7
```

### 4.3 使用者設定快照

設定仍完整保留：

```text
enabled=true
targetPackage=com.shopee.tw
numberMonitorEnabled=true
numberMonitorThreshold=0.19
numberMonitorUpperLimit=3.0
numberColorFilterEnabled=true
numberColorHex=#CABC37
numberColorTolerance=60
numberAbsenceTimeoutMs=6000
numberTriggerZoneId=zone-1
numberTriggerDelayMs=6000
scanIntervalMs=900
clickCooldownMs=2000
randomClickMaxMs=1500
```

數字監控區域：

```text
left=0.68
top=0.22600001
right=0.795
bottom=0.35
```

三個既有辨識區域仍存在：

- `zone-1`：領取文字
- `zone-1787586919928-4`：圓圈＋X
- `zone-1787654151600-1`：返回箭頭

後續 replacement install 必須再次比較這些設定，且不得重新 seed 或覆寫。

---

## 5. 已完成驗證與結果

### 5.1 Gradle IPC 前置條件

所有 Gradle 命令都必須先使用 repository-local 短 socket path：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
```

不可為處理 IPC 問題修改 Windows `TEMP`／`TMP`、Android source、dependency、hosts 或 JDK。

### 5.2 強制重跑單元測試

命令：

```powershell
.\gradlew.bat --no-daemon --rerun-tasks testDebugUnitTest
```

結果：`BUILD SUCCESSFUL`。

共 77 個測試，0 failure、0 error：

| Test suite | Tests |
|---|---:|
| `ActionStateMachineTest` | 5 |
| `AdaptiveScanControllerTest` | 3 |
| `DailyTriggerStatsTest` | 4 |
| `NumberMonitorTrackerTest` | 10 |
| `NumberRegionFingerprintTest` | 2 |
| `RecognitionRegionTest` | 4 |
| `RecognitionZoneTest` | 27 |
| `MediaProjectionAuthorizationGateTest` | 4 |
| `BackArrowDetectorTest` | 2 |
| `CircleXAutoCalibratorTest` | 4 |
| `CircleXDetectorTest` | 4 |
| `GrayTemplateMatcherTest` | 8 |

### 5.3 Lint 與 build

命令：

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug -PtargetAbi=arm64-v8a
```

結果：`BUILD SUCCESSFUL`。

- Lint error：0
- Lint warning：45
- Debug APK：成功產生
- 主要 warning 為 API／KTX／硬編碼文字等；沒有阻擋 build 的錯誤

報告位置：

```text
app/build/reports/lint-results-debug.html
app/build/reports/tests/testDebugUnitTest/index.html
```

### 5.4 手機 runtime 證據

- 目前 CSC process PSS 約 76.6 MB。
- 此數值只是短時間快照，不能證明沒有長時間 Bitmap／callback 洩漏。
- 最近 logcat 未發現 `Process: com.example.csc` 的 `FATAL EXCEPTION`。
- logcat 中看到的歷史 OOM 屬於 `com.shopee.tw`，不可歸因於 CSC。
- 1.12 安裝後目前只在 CSC 主畫面，沒有足夠資料宣稱 1.12 已通過真實數字頁長時間驗收。

---

## 6. 現有架構與資料流

目前核心流程大致如下：

```text
scanRunnable
  -> scanOnce()
  -> foreground/settings/action-state gate
  -> captureAndRecognize()
       API 29: MediaProjectionCaptureService.requestFrame()
       API 30+: AccessibilityService.takeScreenshot()
  -> whole-frame fingerprint gate
  -> Circle-X priority safety scan（週期性）
  -> OCR crop
  -> text target recognition
  -> observeNumbers()
       candidate rebuild
       region gate
       color gate
       candidate selection
       NumberMonitorTracker
  -> image/back-arrow recognition
  -> tap() / scheduleSwipeUp()
  -> dispatchGesture callback
```

安全邊界目前分散在：

- `foregroundPackage`
- `targetPackage`
- `RecognitionRegion`
- `AutomationSettings`
- `ActionStateMachine`
- `processing`
- `clickPending`
- `swipePending`
- `prioritySwipePending`
- `numberTrackerGeneration`

問題不是完全沒有檢查，而是缺少一個可以跨越所有非同步邊界的統一 session/action token。

---

## 7. 風險分級總表

| ID | 等級 | 問題 | 主要後果 |
|---|---|---|---|
| SAF-01 | P0 | 延遲點擊／上滑未攜帶完整 session token | 過期辨識結果仍可能執行手勢 |
| CAP-01 | P1 | API 29 capture callback 無 request ID／timeout | `processing` 永久卡住 |
| CAP-02 | P1 | callback 在 lock 內被呼叫 | deadlock／re-entry 風險 |
| GST-01 | P1 | gesture callback 無 watchdog | `clickPending`／`swipePending` 卡住 |
| NUM-01 | P1 | `Invalid` 被當成 `Missing` | OCR 失敗可能推進誤上滑 |
| NUM-02 | P1 | 混合 element 被丟棄 | 數字有時出現、有時消失 |
| NUM-03 | P1 | fingerprint 只比較 exact hash | 不能穩健判斷同一畫面 |
| NUM-04 | P2 | UI 顯示單幀原始結果 | 視覺上持續跳動 |
| NUM-05 | P1 | 沒有真實 OCR fixture／androidTest | JVM 測試無法證明實機穩定 |
| SEC-01 | P2 | Debuggable + allowBackup | 不適合作為正式對外 release |

---

## 8. SAF-01：過期辨識結果仍可能執行手勢

### 8.1 證據位置

檔案：

```text
app/src/main/java/com/example/csc/automation/ScreenAutomationService.kt
```

重點區段：

- `tap()` 約第 1793 行起
- 延遲點擊 callback 約第 1846 行起
- `scheduleSwipeUp()` 約第 1675 行起
- 延遲上滑 callback 約第 1708 行起

### 8.2 現況

點擊進入最多 1.5 秒的隨機延遲後，只重新檢查：

- current enabled
- foreground package 是否仍等於原 package
- current target package 是否在前景
- 點擊座標是否落在目前任一允許區域

沒有確認：

- 原始 zone 是否仍存在
- 原始 target 是否仍存在
- zone bounds 是否完全相同
- threshold／reference／number settings 是否相同
- 頁面內容在同一 package 內是否已切換
- service/session generation 是否相同

一般上滑的延遲 callback 也只檢查 enabled、number monitor 與 foreground package；沒有檢查 tracker generation、原始 observation、threshold、region 或 trigger settings。

### 8.3 風險

使用者在隨機等待中修改設定，或目標 App 在同一 package 內切換頁面，舊辨識可能仍送出點擊／上滑。對購物 App 而言這是高風險錯誤動作。

### 8.4 必要修正

新增統一 `AutomationSession`／`ActionToken`，至少包含：

```kotlin
data class AutomationSession(
    val generation: Long,
    val targetPackage: String,
    val foregroundPackage: String?,
    val configSignature: Int,
    val projectionGeneration: Long,
)

data class ActionToken(
    val session: AutomationSession,
    val zoneId: String?,
    val targetId: String?,
    val actionId: Long,
)
```

實際命名可調整，但語意不可省略。

Token 必須從 capture request 一路帶到：

- OCR success/failure
- vision executor callback
- image second-frame verification
- delayed tap runnable
- delayed swipe runnable
- `dispatchGesture()` callback

在任何動作 dispatch 前必須集中呼叫單一 `isStillCurrent(token)`，拒絕過期 token。

---

## 9. CAP-01／CAP-02：Android 10 MediaProjection callback 生命週期

### 9.1 證據位置

```text
app/src/main/java/com/example/csc/capture/MediaProjectionCaptureService.kt
```

重要區段：

- `pendingFrameCallback`：約第 43 行
- `onDestroy()`：約第 67 行
- `onImageAvailable()`：約第 131 行
- `requestFrameInternal()`：約第 193 行

### 9.2 現況

- 同時只有一個 `pendingFrameCallback`。
- 第二個 request 直接 callback `null`，沒有 Busy 狀態。
- 沒有 request ID。
- 沒有 projection generation。
- 沒有 frame timeout。
- projection stop/restart 沒有明確完成所有 pending request。
- `onDestroy()` 在 `callbackLock` 內呼叫外部 callback。

### 9.3 直接影響

測試手機為 Android 10，因此實際使用的正是此路徑。

若 ImageReader 不再產生 frame，`ScreenAutomationService` 的 `processing` 可能一直保持 `true`，後續掃描全部被擋住。若 projection restart 後舊 request 被新 projection 的 frame 完成，也會出現 generation 混用。

### 9.4 必要修正

建議模型：

```kotlin
sealed interface CaptureResult {
    data class Success(val bitmap: Bitmap) : CaptureResult
    data object Busy : CaptureResult
    data object Stopped : CaptureResult
    data object TimedOut : CaptureResult
    data class Failed(val reason: String) : CaptureResult
}

data class PendingCaptureRequest(
    val requestId: Long,
    val projectionGeneration: Long,
    val deadlineMs: Long,
    val callback: (CaptureResult) -> Unit,
)
```

必要規則：

1. lock 內只讀寫／摘除 pending request。
2. callback 一律在 lock 外呼叫。
3. stop、restart、destroy 都必須完成 pending request。
4. timeout 必須解除 `processing`。
5. 遲到的舊 frame 不得完成新 request。
6. Bitmap ownership 必須明確；未交給 caller 的 Bitmap 由 capture service recycle。

---

## 10. GST-01：Gesture callback 無 watchdog

### 10.1 現況

`dispatchGesture()` 已處理：

- API 立即回傳 false
- `onCompleted`
- `onCancelled`

但沒有處理系統接受手勢後 callback 永遠不回的情況。

### 10.2 影響

- 點擊可能永久停在 `CLICKING`。
- 上滑可能永久停在 `SWIPING`。
- `clickPending`／`swipePending` 不清除。
- 後續辨識被 `ActionStateMachine.blocksRecognition()` 阻擋。

### 10.3 必要修正

建立統一 `ActionResult`：

```kotlin
sealed interface ActionResult {
    data object Completed : ActionResult
    data object Rejected : ActionResult
    data object Cancelled : ActionResult
    data object TimedOut : ActionResult
    data object Stale : ActionResult
}
```

每個 gesture 必須：

- 有唯一 `actionId`
- dispatch 後啟動 watchdog
- callback 只允許完成一次
- timeout 後清除相符 action 的 pending 狀態
- 遲到 callback 不得改變新 action 狀態
- completed 才可增加統計

---

## 11. NUM-01：`Invalid` 不可計入 `Missing`

### 11.1 證據位置

```text
app/src/main/java/com/example/csc/automation/NumberMonitorTracker.kt
```

約第 63–83 行：

- `Observation.Value` 先取出 value。
- `Missing` 與 `Invalid` 都因 value 為 null 進入 `observeMissing()`。

Service OCR failure 路徑：

```text
ScreenAutomationService.kt 約第 506–515 行
```

### 11.2 風險

OCR task failure、recognizer internal failure 或 Bitmap/input 問題不是「畫面沒有數字」。目前這些錯誤卻可能：

1. 開始 absence timer。
2. 增加 missing observation 次數。
3. 在期限點 fresh failure 後回傳 `SWIPE_ABSENT`。

### 11.3 正確語意

`Invalid` 必須：

- 不改變 last-known-good。
- 不開始 absence timer。
- 不增加 missing observations。
- 不累積 low/high candidate。
- 回傳 `REQUEST_FRESH_OBSERVATION` 或明確 `RETRY_INVALID`。
- 連續 Invalid 達上限時暫停自動化並顯示錯誤，不得上滑。

必加測試：

```text
正常值 -> Invalid x N -> 永不 SWIPE_ABSENT
初始 Invalid -> 永不啟動 absence deadline
absence 等待中出現 Invalid -> 保留或暫停 deadline，但不得把 Invalid 當 fresh Missing
期限點 fresh OCR 為 Invalid -> 不上滑，要求再次取得有效 observation
```

---

## 12. NUM-02：混合 OCR element 被丟棄

### 12.1 證據位置

```text
app/src/main/java/com/example/csc/automation/AutomationConfig.kt
```

- `rebuildNumberTokens()` 約第 190 行起
- `isNumericElement()` 約第 220 行起

```text
app/src/main/java/com/example/csc/automation/ScreenAutomationService.kt
```

- OCR candidate 建立約第 1174–1222 行

### 12.2 現況

只有完全由以下字元構成的 element 才會進入 token rebuild：

- 數字
- `.`／`,`
- `+`／`-`

如果 element 是：

```text
S0.2
幣0.2
$0.2
0.2元
```

整個 element 會被視為非數字。因為 line 仍有 elements，`elements.isEmpty()` fallback 不會執行，最後成為 Missing。

ML Kit 的 line／element segmentation 可能因動畫、反鋸齒或單幀畫質改變，因此同一個可見數字可能在相鄰幀得到不同結構，直接造成跳動。

### 12.3 必要修正

不可直接回到寬鬆的整行第一個數字。建議：

1. 允許從混合 element 內提取數字 substring。
2. 保存原 element bounds 與提取結果。
3. 若無字元級 bounds，只能把 element bounds 當保守候選，不可假裝取得精確 glyph bounds。
4. 仍依監控框中心距、bounds 面積與顏色證據排序。
5. 對常見貨幣／圖示誤辨字元建立 fixture，不以猜測加入替換規則。

必加測試：

```text
S0.2 -> 0.2
$0.2 -> 0.2
幣0.2 -> 0.2
0.2元 -> 0.2
倒數 05:59 與中心 0.2 同時存在 -> 選中心 0.2
價格 99 與中心 0.2 同行 -> 不得被 99 取代
沒有任何數字的文字 -> 不建立候選
```

---

## 13. NUM-03：ROI fingerprint 不足以判斷畫面相同

### 13.1 證據位置

```text
app/src/main/java/com/example/csc/automation/NumberRegionFingerprint.kt
```

### 13.2 現況

- ROI 內固定取 32×32 共 1024 個點。
- 每點只保留粗量化亮度。
- 最終用 FNV 形式折疊成單一 `Long`。
- tracker 只做 `lastKnownGood.roiFingerprint == roiFingerprint`。

### 13.3 兩種失敗模式

1. 假變化：背景動畫、反鋸齒或一個採樣點亮度變化，就會得到完全不同的 hash；tracker 誤以為 ROI 已改變。
2. 漏變化：小數點、細字或小型 glyph 剛好落在採樣點之間，真實數字變化卻可能得到相同輸入樣本。

現有測試只有一個剛好覆蓋取樣點的合成小方塊，沒有測採樣相位、實際字體、抗鋸齒與背景動畫。

### 13.4 建議替代

保留無額外 Bitmap allocation 的原則，但回傳可比較 signature，例如：

```kotlin
data class NumberRegionSignature(
    val quantizedLuminance: ByteArray,
    val edgeBits: LongArray,
)

data class SignatureDistance(
    val luminanceMae: Float,
    val changedCellRatio: Float,
    val edgeHammingRatio: Float,
)
```

實際資料結構可更精簡，但必須能判斷「近似相同」，不能只比較 avalanche hash。

必加測試：

- ROI 外動畫不影響結果。
- ROI 內背景微亮度變化仍視為近似相同。
- 小數點出現／消失可被偵測。
- `0.2 -> 0.1` 可被偵測。
- 數字水平移動 1–2 px 不應被誤認為完全不同頁面。
- 多個不同採樣相位都能偵測 glyph。

所有相似度門檻必須從實機 fixture 決定，不可直接猜數字。

---

## 14. NUM-04：顯示層不應直接呈現單幀結果

### 14.1 現況

`observeNumbers()` 在 tracker 完成確認前就依本幀 `values` 建立：

```text
0.2
無數字
無符合顏色數字
```

即使 tracker 回傳 `REQUEST_FRESH_OBSERVATION`，overlay 仍顯示本幀 Missing，因此使用者看到數值與無數字快速跳動。

### 14.2 建議顯示模型

顯示應與 confirmed state 分離：

```kotlin
data class NumberMonitorDisplayState(
    val confirmedValue: Double?,
    val rawObservation: Observation,
    val status: Status,
)

enum class Status {
    STABLE,
    RECHECKING,
    ABSENCE_COUNTDOWN,
    ABSENCE_CONFIRMATION,
    INVALID,
    ACTION_PENDING,
}
```

建議文案：

- 穩定：`0.2`
- 單幀漏讀：`0.2 · 重新確認`
- 真正開始 absence：`未確認到數字 · 5.5 秒`
- OCR failure：`辨識失敗 · 重試中`
- priority swipe：`優先上滑等待中`

只有 confirmed absence 才顯示「無數字」。

---

## 15. 實機證據與目前限制

既有截圖：

```text
captures/current-swipe-issue.png
```

該畫面可看到監控區附近存在 `0.2`，CSC overlay 卻顯示「無符合顏色數字」並進入上滑倒數。

這可以證明候選／顏色／OCR 鏈路存在漏判，但單張截圖無法判定失敗發生在：

- ML Kit 未輸出數字
- element segmentation 改變
- token rebuild 丟棄候選
- bounds 超出監控框
- token bounds 顏色覆蓋不足
- overlay 顯示的是前一幀狀態

因此下一輪不得直接調高 `numberColorTolerance`。必須先取得 raw OCR 與 bounds 證據。

---

## 16. 必須先建立的安全診斷模式

在購物 App 上驗收前，建議加入僅 Debug build 可用的 observation-only 診斷模式。

必要特性：

- 完全禁止 `dispatchGesture()`。
- 不修改正式使用者設定契約。
- 明確顯示「診斷模式：不會點擊／上滑」。
- 每幀只記錄數字 ROI 相關資料。
- 去識別化保存僅限數字監控 crop，不保存完整畫面。
- 可設定最多樣本數與自動停止，避免無界增長。

每個 observation 至少記錄：

```text
timestamp
sessionGeneration
captureRequestId
foregroundPackage
ROI bounds
raw block/line/element text
每個 element bounds
重建後 token 與 bounds
region gate 結果
color matches / samples / coverage
selected value
signature distance
tracker input/action/state
```

若不希望寫入裝置檔案，可先以結構化 logcat tag 輸出；但不得包含完整購物畫面或個人資訊。

---

## 17. 建議實作順序

### 階段 A：先建立會失敗的測試

1. `Invalid` 不得推進 absence。
2. 混合 OCR element 可提取數字。
3. signature 可容忍微小背景變化並偵測小 glyph。
4. 設定改變使延遲點擊失效。
5. foreground package 改變使延遲上滑失效。
6. service destroy 使舊 callback 失效。
7. gesture callback 不返回時 watchdog 解除 pending。
8. projection stop/restart 完成舊 capture request。
9. 遲到 callback 不得完成新 generation 的 action。

### 階段 B：AutomationSession 與 ActionResult

先只處理正確性，不拆大類別：

- 建立 session/action model。
- 將 generation 帶過現有 callback。
- 建立集中 `isStillCurrent()`。
- 建立 gesture watchdog。
- 保留現有 foreground、region、target package 與停止入口。

### 階段 C：MediaProjection request model

- request ID
- projection generation
- timeout
- lock 外 callback
- stop/restart/destroy completion
- Bitmap ownership tests

### 階段 D：NumberMonitorTracker 語意修正

- `Invalid` 與 `Missing` 分流
- confirmed display state
- priority ownership不變
- action consumed 後 reset/rebase
- 不加入新的持久化 preference

### 階段 E：OCR candidate 與 signature

- 先取得 fixture
- 支援 mixed element
- 使用緊密且可解釋的候選 bounds
- 以 signature distance 取代 exact hash gate
- 顏色門檻只依 fixture 調整

### 階段 F：實機驗收與交付

- 先在診斷模式執行
- 通過 fixture 與 observation-only 驗收後才允許手勢
- 更新版本至 1.13/code 14
- arm64-v8a only
- 保存新 APK，不覆蓋 1.12
- replacement install 後重新核對 Accessibility 與 MediaProjection

---

## 18. 建議修改檔案範圍

### 必要

```text
app/src/main/java/com/example/csc/automation/ScreenAutomationService.kt
app/src/main/java/com/example/csc/automation/ActionStateMachine.kt
app/src/main/java/com/example/csc/automation/NumberMonitorTracker.kt
app/src/main/java/com/example/csc/automation/NumberRegionFingerprint.kt
app/src/main/java/com/example/csc/capture/MediaProjectionCaptureService.kt
app/src/test/java/com/example/csc/automation/ActionStateMachineTest.kt
app/src/test/java/com/example/csc/automation/NumberMonitorTrackerTest.kt
app/src/test/java/com/example/csc/automation/NumberRegionFingerprintTest.kt
```

### 可能新增

```text
app/src/main/java/com/example/csc/automation/AutomationSession.kt
app/src/main/java/com/example/csc/automation/ActionResult.kt
app/src/test/java/com/example/csc/automation/AutomationSessionTest.kt
app/src/test/java/com/example/csc/capture/MediaProjectionRequestStateTest.kt
app/src/androidTest/java/com/example/csc/automation/NumberOcrFixtureTest.kt
app/src/androidTest/assets/number-fixtures/...
```

### 僅在診斷入口需要時修改

```text
app/src/main/java/com/example/csc/MainActivity.kt
app/src/main/java/com/example/csc/automation/AutomationConfig.kt
```

診斷模式最好只屬於 Debug build，不新增正式持久設定。如果必須加入設定欄位，需有安全預設值、舊設定相容性與 migration test。

### 文件同步

```text
README.md
```

只更新與最終行為直接相關的段落，不做無關 UI 或文件重寫。

---

## 19. 測試矩陣

### 19.1 Session／動作安全

| Case | 預期 |
|---|---|
| 設定修改後舊 OCR callback 返回 | 拒絕，不點擊 |
| zone 刪除後舊 delayed tap 到期 | 拒絕 |
| target ID 改變但座標仍在其他 zone | 拒絕 |
| 同 package 內頁面改變 | 舊 action token 失效或需要重新驗證 |
| foreground package 改變 | 所有 pending action 取消 |
| service destroy | 舊 callback 不得重啟掃描或手勢 |
| gesture callback 不返回 | timeout 後清除 pending |
| timeout 後遲到 completed callback | 不得覆蓋新狀態或重複統計 |

### 19.2 Capture

| Case | 預期 |
|---|---|
| request 正常取得 frame | Success，Bitmap ownership 明確 |
| 同時第二 request | Busy，不以模糊 null 表示 |
| projection stop | pending -> Stopped |
| projection restart | 舊 generation request 不接受新 frame |
| ImageReader 不回 frame | TimedOut，`processing=false` |
| destroy | callback 在 lock 外完成一次 |

### 19.3 Number tracker

| Sequence | 預期 |
|---|---|
| 正常 0.20 -> 相同畫面 Missing | 保留可靠值，要求重查，不上滑 |
| 正常 0.20 -> Invalid x 10 | 不啟動 absence，不上滑 |
| 初始 Missing -> timeout -> fresh Missing | 只觸發一次 absence swipe |
| timeout -> fresh Invalid | 不上滑，繼續安全重試／暫停 |
| ROI 改變 -> 低值三次但不足 500 ms | 不上滑 |
| ROI 改變 -> 低值三次且跨 500 ms | SWIPE_LOW |
| 低值／高值交錯 | 重設候選，不上滑 |
| priority pending | 所有一般 number action 被抑制 |
| generation 改變 | 舊 risk／absence evidence 全失效 |

### 19.4 OCR fixture

至少準備：

- 清晰 `0.2`
- 輕微模糊 `0.2`
- 小數點被拆 element
- `S0.2`／`幣0.2`
- 同行含倒數與價格
- 顏色接近容差邊界
- 相同畫面 ML Kit 曾回空的失敗幀
- 真正無數字頁
- 背景動畫但數字不變
- `0.2 -> 0.1`／`0.2 -> 3.1`

每個 fixture 至少重跑 20 次。

安全通過條件不是單次辨識成功，而是：

- 已知正常值 fixture 不得產生任何 swipe action。
- 已知 Missing fixture 必須經 timeout + fresh valid Missing。
- 同一 fixture 的 candidate 選擇必須一致且可解釋。

---

## 20. 實機驗收清單

### 20.1 安裝前

- 保存 `shared_prefs/automation.xml` 唯讀快照。
- 記錄現有 zones、threshold、upper limit、color、timeout、trigger zone、trigger delay。
- 確認裝置序號與型號。
- 確認 APK 為 arm64-v8a。
- 確認新版本檔名不覆蓋 1.12。

### 20.2 Replacement install 後

- `dumpsys package com.example.csc`：版本正確。
- 比對設定完整保留。
- `dumpsys accessibility`：CSC enabled 且 bound。
- `dumpsys media_projection`：使用者重新授權後有效。
- 啟動 MainActivity 無 crash。
- 先以診斷模式測試，不送出手勢。

### 20.3 診斷模式場景

1. 穩定正常數字頁 5 分鐘。
2. 監控框稍窄，製造偶發 OCR miss。
3. 背景持續動畫但數字不變。
4. 真正切換到低值。
5. 真正切換到高於上限。
6. 真正切換到無數字。
7. 優先上滑等待期間製造數字 miss。

### 20.4 手勢模式場景

只有診斷模式通過後才執行：

- 低值只上滑一次。
- 高值只上滑一次。
- 無數字在 timeout + fresh confirmation 後只上滑一次。
- priority pending 期間一般數字規則不能提早上滑。
- 其他區域持續命中時優先倒數暫停。
- 其他區域降低後完整重跑等待時間。
- gesture rejected/cancelled/timed out 不增加統計。
- completed priority swipe 只增加一次統計。

### 20.5 結束檢查

- 搜尋 `AndroidRuntime` / `Process: com.example.csc`。
- 彙總 `CSC_FRAME_PROFILE`。
- 檢查 process PSS 及長時間趨勢。
- 確認沒有無界 fixture/log 檔案增長。
- 再次比對設定。

---

## 21. APK 交付規則

下一個交付版本應為：

```text
versionName 1.13
versionCode 14
ABI arm64-v8a only
APK/CSC-1.13-arm64-v8a.apk
```

規則：

- 不覆蓋 `APK/CSC-1.12-arm64-v8a.apk`。
- 新 APK 必須存在且 hash 可讀後才安裝。
- 安裝不等於完成；必須啟動、核對 UI、設定、Accessibility、MediaProjection、實際行為與 crash log。
- 若 Accessibility binding 或 MediaProjection authorization 因 replacement install 消失，需明確記錄並只恢復 CSC。

---

## 22. 外部安全面檢查

### 正面項目

- 合併後 Manifest 沒有 `INTERNET`。
- 合併後 Manifest 沒有 `ACCESS_NETWORK_STATE`。
- `MediaProjectionCaptureService`：`exported=false`。
- `AutomationActionReceiver`：`exported=false`。
- `ScreenAutomationService` 雖為 exported，但受 `android.permission.BIND_ACCESSIBILITY_SERVICE` 保護。
- 截圖與 ML Kit OCR 在裝置端處理。

### 剩餘項目

- 目前手機安裝的是 Debug APK，`DEBUGGABLE`。
- Manifest `allowBackup=true`。
- Android 10 仍宣告 `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`）。
- 專案未見 dependency verification metadata／lockfile。
- 本輪未查線上 CVE database，因此不可宣稱 dependency 漏洞為零。

正式公開發行前應另外建立 release security checklist；這不應和本輪數字可靠性修正混成一次大型 refactor。

---

## 23. 明確非目標

後續修改不得藉此任務進行：

- 大型 UI framework 重寫。
- 全 repository 格式化或重新命名。
- 修改 Windows `TEMP`／`TMP`。
- 更換 JDK、Gradle、ML Kit 版本以「碰運氣」修辨識。
- 取消 target package 安全邊界。
- 放寬區域外點擊。
- 取消使用者 MediaProjection 同意。
- 移除停止入口。
- 先提高顏色容差或任意降低門檻。
- 在沒有診斷抑制手勢時直接讓購物 App 自動執行。

---

## 24. 完成定義

只有以下全部成立才可宣告完成：

1. 所有非同步 capture、recognition、verification、delayed gesture 與 callback 攜帶 session/action token。
2. 設定、前景 package、service、projection generation 改變會使舊結果失效。
3. capture 與 gesture 都有 timeout，且遲到 callback 無害。
4. `Invalid` 永遠不被當成 `Missing`。
5. 混合 OCR element 有 fixture 與通過測試。
6. fingerprint 改為可比較 signature，門檻有 fixture 證據。
7. UI 顯示 confirmed state，不再因單幀漏讀直接顯示確定無數字。
8. priority pending 期間不存在一般 number swipe timer。
9. 完整單元測試、Lint、arm64 build 通過。
10. 診斷模式真實頁面 5 分鐘沒有誤動作。
11. 真實 low/high/absence 各只觸發一次預期上滑。
12. 設定完整保留。
13. Accessibility 與 MediaProjection 均實際有效。
14. 無 CSC crash，沒有 callback/pending 卡死。
15. 1.13 arm64 APK 已保存、安裝並完成驗收。

---

## 25. 可直接貼給新 Codex 對話的任務指令

```text
請在 D:\codee\shop 依 ENGINEERING_HANDOFF_CSC_RELIABILITY_2026-09-04.md 執行修正。

先讀 AGENTS.md 與交接文件，保留目前所有未提交的 1.12 變更與使用者設定，不得 reset、覆蓋或做無關 refactor。

第一優先處理 SAF-01、CAP-01/CAP-02、GST-01：建立跨 capture、OCR、vision、delayed tap/swipe、dispatchGesture callback 的 AutomationSession/generation 與 ActionToken；設定、前景 package、service 或 projection 改變時，所有舊結果必須失效。Capture 與 gesture 都要有明確 Completed/Rejected/Cancelled/TimedOut/Stale 結果及 watchdog；MediaProjection callback 必須在 lock 外呼叫。

第二優先處理數字可靠性：Invalid 絕對不能計入 Missing；支援 S0.2、幣0.2 等混合 OCR element；先用真實 fixture 再決定 bounds/顏色策略；以可比較的 ROI signature distance 取代 exact FNV hash；overlay 顯示 confirmed state，不因單幀 miss 立刻顯示確定無數字。prioritySwipePending 期間繼續禁止一般 number action。

先新增會失敗的純 JVM 測試，再改程式碼。實機測試前加入 Debug-only observation 模式，必須完全禁止手勢，只記錄去識別化 ROI、raw OCR elements/bounds、顏色覆蓋、signature distance 與 tracker action。沒有此安全入口時，不得在 com.shopee.tw 執行自動化測試。

Gradle 前建立 D:\codee\shop\app\build\uds，設定 JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds，使用 --no-daemon。先跑受影響單測，再跑完整 testDebugUnitTest、lintDebug、assembleDebug -PtargetAbi=arm64-v8a。

交付版本更新為 1.13/code 14，只產生 arm64-v8a，保存到 APK/CSC-1.13-arm64-v8a.apk，不覆蓋 1.12。安裝到目前授權手機後，完整核對原設定、Accessibility、MediaProjection、5 分鐘正常數字、真實 low/high/absence、priority 上滑、統計與 crash log。完成時報告 diff、測試、裝置證據與剩餘風險。
```

---

## 26. 快速命令參考

ADB：

```powershell
$adb = 'D:\codee\abc\.android-tools\sdk\platform-tools\adb.exe'
& $adb devices -l
& $adb shell dumpsys package com.example.csc
& $adb shell settings get secure enabled_accessibility_services
& $adb shell dumpsys accessibility
& $adb shell dumpsys media_projection
& $adb shell dumpsys meminfo com.example.csc
& $adb logcat -d -v threadtime AndroidRuntime:E CSC_FRAME_PROFILE:I '*:S'
```

Gradle：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'

.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.automation.NumberMonitorTrackerTest"
.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.automation.NumberRegionFingerprintTest"
.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.automation.ActionStateMachineTest"
.\gradlew.bat --no-daemon testDebugUnitTest
.\gradlew.bat --no-daemon lintDebug assembleDebug -PtargetAbi=arm64-v8a
```

工作樹：

```powershell
git status --short
git diff --stat
git diff -- app/src/main/java/com/example/csc/automation/ScreenAutomationService.kt
```
