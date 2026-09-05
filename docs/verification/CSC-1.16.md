# CSC 1.16 驗證紀錄

日期：2026-09-06（Asia/Taipei）。工程說明：[CSC 1.15 工程交接](../../ENGINEERING_HANDOFF_CSC_1.15_AUDIT_2026-09-05.md)。

## 交付物

- APK：[CSC-1.16-arm64-v8a.apk](../../APK/CSC-1.16-arm64-v8a.apk)
- 套件／版本：`com.example.csc`／`1.16`／versionCode `17`
- native-code：僅 `arm64-v8a`
- APK SHA-256：`cf9b64065edd5bd019c4f8cb95782b9553b42804fac4f122c5bb9373ed0e1d59`
- Git tag：`v1.16`（tag 應指向包含上述 APK、原始碼、測試與文件的提交）

## 自動驗證

| 項目 | 結果 |
| --- | --- |
| 受影響測試：GestureTerminalCoordinator、NumberMonitorTracker、RecognitionZone | 通過 |
| 完整 `testDebugUnitTest` | 100 tests，0 failures，0 errors，0 skipped |
| `lintDebug` | 0 errors、47 warnings |
| `assembleDebug -PtargetAbi=arm64-v8a` | BUILD SUCCESSFUL |
| `git diff --check` | 通過 |
| APK 套件／版本／ABI | 通過 |

Gradle 執行前使用 repository-local Unix-domain socket 目錄：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
```

## 實機

- 裝置：LG LM-G710（serial `LMG710AWMff88f3c6`），Android 10／API 29。
- 更新前：`com.example.csc` 1.15／versionCode 16。
- 使用 `adb install -r -t` 安裝後回報 `Success`；更新後為 1.16／versionCode 17。
- 啟動 `com.example.csc/.MainActivity` 回報 `Status: ok`，前景 Activity 為 CSC MainActivity，檢查時 PID 為 `9226`。
- 更新後 AccessibilityService `com.example.csc/...ScreenAutomationService` 仍在 Enabled services 並已 bound。
- MediaProjection 狀態仍顯示 `(com.example.csc, uid=10461): TYPE_SCREEN_CAPTURE`；沒有透過 ADB 繞過使用者同意。
- 裝置 `/data/app/.../base.apk` SHA-256 與交付 APK 相同。
- 未清除既有 logcat；最近 500 行檢查到的 CSC 啟動記錄沒有 `FATAL EXCEPTION`、`Fatal signal`、`ANR in` 或 CSC process crash pattern。

## 驗證界線

本次只啟動與檢查 CSC 本身，未開啟 Shopee、未執行真實點擊／上滑或購物流程。只觀察診斷模式預設關閉；它可在設定頁啟用，保留擷取／辨識但將所有預定手勢記錄為本機 `would-act`，不送出手勢、不計入成功統計。真實目標 App 的低值／高值／缺失及手勢驗收、API 30+ 裝置驗證仍待另行執行。
