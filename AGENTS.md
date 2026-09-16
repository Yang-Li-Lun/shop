# CSC Repository Guide

產品功能與使用限制見 `README.md`。本檔只保留會影響實作、測試與交付的專案規則。

## 工作方式

- 使用 `rg`／`rg --files` 做 targeted search，先查呼叫端、資料模型與相關測試；避免無目的掃描或重讀大型、無關檔案。
- 採最小修改，不做無關重構、重新命名或全檔格式化。
- 先跑受影響的單一測試；跨核心流程或正式交付前，再擴大到完整測試、Lint 與 build。

## 架構與安全邊界

- `ScreenAutomationService.kt` 是排程、截圖、辨識、二次確認與手勢的主要 orchestrator；`ActionStateMachine.kt` 是非同步辨識／手勢的唯一狀態來源。
- `MainActivity.kt` 管理 UI、權限與區域編輯；`AutomationConfig.kt` 管理持久設定、zones、正規化與座標安全。
- 維持 API 29 MediaProjection 與 API 30+ accessibility screenshot 兩條路徑，並同步檢查 Manifest 與 API guard。
- `RecognitionRegion` 使用 `0f..1f` 比例座標；截圖、裁切與 gesture 座標轉換必須明確。所有點擊須再次通過目標安全邊界與設定區域檢查。
- `targetPackage` 是自動化安全邊界；所有截圖、非同步結果、延遲回呼與 gesture 都須重新確認目標 App 仍在前景，且 session／generation 仍有效。
- 不得繞過使用者同意、區域外點擊保護、停止入口或前景 App 檢查。UI／gesture 留在 main thread，影像工作使用既有 executor；所有 callback 路徑釋放 Bitmap／HardwareBuffer。
- SharedPreferences keys 與 zones JSON 是持久資料契約；新增欄位須有安全預設值並相容舊設定，保留 bundled profile seed 行為。
- Circle-X 誤判應修正方向幾何、negative space、外圈 isolation 或邊界完整性，並保留 bundled 正負樣本測試；不可只提高門檻。

## Gradle、測試與 IPC

若出現 `Unable to establish loopback connection`、`PipeImpl`、`UnixDomainSockets` 或 `Invalid argument: connect`，先測試 repository-local 短 socket 路徑，不得先修改 `TEMP`／`TMP`、Android source、dependencies、hosts 或 JDK。

Windows PowerShell 從 repository root 執行：

```powershell
$repoRoot = (Get-Location).Path
$udsPath = Join-Path $repoRoot 'app\build\uds'
New-Item -ItemType Directory -Force -Path $udsPath | Out-Null
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=$udsPath"

.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.vision.CircleXDetectorTest"
.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.automation.RecognitionZoneTest"
```

依修改內容替換單一測試類別；需要擴大驗證時再執行 `testDebugUnitTest`、`lintDebug` 或 `assembleDebug`。

## APK 正式交付

- APK 一律只交付 `arm64-v8a`。版本從 `versionName 1.0`／`versionCode 1` 開始；正式交付新功能或修正版時遞增小版本與 `versionCode`，大版本除非使用者明確要求不得變更。
- 測試 build、同版本重建或重新簽名不算新版本；不得因此任意遞增版本號。
- 正式交付且版本更新時，將 APK 保存至 repository root 的 `APK` 資料夾，檔名包含版本號且不得覆蓋既有檔案；安裝到目前授權連接的 Android 手機，啟動並完成基本功能與崩潰檢查。
- 正式交付前建立與版本一致的 Git commit 與 tag，例如 `shop-v1.1.apk` 對應 `v1.1`。若 repository 尚未納入 Git，先處理版本控制初始化，不得略過回退對應；歷版 APK、commit 與 tag 必須保留。
- 交付前核對 APK、`versionName`、`versionCode`、ABI、Git tag 與保存位置一致。

## 直播獎勵流程

- 每輪以上滑切換到新的直播頁面開始；新頁面須累計觀看至少 6 分鐘後才可嘗試領取，載入或辨識延遲可使實際等待延長至 7～9 分鐘，但不得提前領取。
- 達到可領取時間後點擊領取；成功點擊後再上滑到下一個新直播頁面並重設該輪計時。
- 新頁面持續偵測設定的指定數字獎勵：偵測到指定數字時停止換頁並等待領取時間；在安全的重新確認流程後仍未偵測到指定數字，才上滑到下一頁。
- 觀看計時、領取點擊、上滑、數字辨識與等待狀態都必須納入 `ActionStateMachine`，不得建立平行手勢狀態；每個延遲動作都須重新確認設定、前景 App、session 與 generation。
