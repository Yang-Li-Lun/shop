# CSC Repository Guide

產品功能、使用方式與平台限制見 [README.md](README.md)。本檔只記錄開發導覽與不易從單一檔案看出的約束。

## 工作方式

- 以 `rg` / `rg --files` targeted search；先找呼叫端、資料模型與對應測試，不要無必要掃描全 repository。
- 不重讀未修改的檔案。採最小修改，不做無關 refactor、重新命名或全檔格式化。
- 先跑受影響的單一測試類別；跨核心流程或交付 APK 前才擴大至完整測試、Lint 與 build。

## 架構與關鍵元件

- `MainActivity.kt`：programmatic View 設定 UI、權限與區域編輯。
- `automation/AutomationConfig.kt`：持久設定、區域／目標模型、正規化與座標安全。
- `automation/ScreenAutomationService.kt`：核心 orchestrator；排程、截圖、辨識、二次確認及手勢。
- `automation/ActionStateMachine.kt`：非同步辨識／手勢的唯一狀態來源；`AdaptiveScanController.kt`：掃描節奏。
- `capture/MediaProjectionCaptureService.kt`：API 29 擷取相容層；API 30+ 擷取由 accessibility service 負責。
- `vision/TemplateMatcher.kt`：模板比對；`CircleXDetector.kt`：Circle-X 幾何偵測；`BackArrowDetector.kt`：白色向左箭頭幾何偵測；`CircleXAutoCalibrator.kt`：runtime-only 分區校正。
- `ui/RegionSelectorView.kt`：比例座標區域編輯。對應純邏輯與視覺測試位於 `app/src/test/`。

## 修改限制

- `RecognitionRegion` 是 `0f..1f` 比例座標；截圖、裁切與 gesture 座標系必須明確轉換。所有點擊須再次通過目標安全邊界及設定區域檢查。
- SharedPreferences keys 與 zones JSON 是持久資料契約；新增欄位須有安全預設值並能讀取舊設定，保留 bundled profile seed 行為。
- `targetPackage` 是自動化安全邊界；截圖、非同步結果及所有延遲後 gesture 都須再次確認前景 package 完全相符。
- 非同步結果須確認仍屬目前設定與前景 package。手勢優先序統一經 `ActionStateMachine`，不要增加平行狀態來源。
- 所有 callback 路徑都要釋放 Bitmap／HardwareBuffer；UI／gesture 留在 main thread，影像工作留在既有 executor。
- Circle-X 誤判應修正方向幾何、negative space、外圈 isolation 或邊界完整性，不得只提高門檻；保留 bundled 正負樣本測試。

## Build 與測試

Windows PowerShell 從 repository root 執行：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'

.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.vision.CircleXDetectorTest"
.\gradlew.bat --no-daemon testDebugUnitTest --tests "com.example.csc.automation.RecognitionZoneTest"
.\gradlew.bat --no-daemon testDebugUnitTest
.\gradlew.bat --no-daemon lintDebug assembleDebug
```

依修改內容替換單一測試類別。

## Android 與效能邊界

- 保持 API 29 MediaProjection 與 API 30+ accessibility screenshot 兩條路徑；service／權限變更同步檢查 Manifest 與 API guard。
- API 29 的 MediaProjection 授權在前景服務啟動期間必須維持等待狀態；不得因 `onResume()` 早於 service `running` 而重複開啟系統授權。
- 不得繞過使用者同意、區域外點擊保護、停止入口或前景 app 檢查。
- 沿用區域裁切、prepared screen、reference cache、frame fingerprint、adaptive cadence 與連續幀確認；避免全畫面 OCR、每幀 decode／大型配置及主執行緒影像運算。

## Gradle IPC 執行規則

- 每次執行 Gradle 前，先建立 `D:\codee\shop\app\build\uds`。
- 執行 Gradle 前設定 PowerShell 環境變數：

  ```powershell
  New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
  $env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
  ```

- Gradle 指令優先使用 `--no-daemon`。
- 不得為處理此 IPC 錯誤而修改 `TEMP`／`TMP`、Android source、dependencies、hosts 或 JDK。

## APK 交付規則

- 交付 APK 一律使用 `arm64-v8a`（ARM v8a）架構；若設定 ABI，僅保留 `arm64-v8a`，不得交付其他 ABI 作為預設或替代版本。
- 版本號從 `1.0` 開始（對應 Android `versionName`；初始 `versionCode` 使用 `1`）。每次交付 APK 都必須更新版本號；除非使用者明確提出，否則不得修改大版本號，只遞增小版本號與對應的 `versionCode`。
- 版本號有更新時，必須將新建置的 APK 安裝到目前授權且連接中的 Android 手機，並完成啟動與基本功能／崩潰檢查後才算交付完成。
- 專案根目錄的 `APK` 資料夾是 APK 專用保存位置。每次產生新 APK 都必須將新版本複製／保存到 `APK` 資料夾，檔名須包含版本號，不得覆蓋既有 APK。
