# CSC 1.17 驗證紀錄

日期：2026-09-06（Asia/Taipei）。工程交接：[CSC 1.16 無數字分析與修復交接](../../ENGINEERING_HANDOFF_CSC_1.16_ABSENCE_2026-09-06.md)。

## 交付物

- APK：[CSC-1.17-arm64-v8a.apk](../../APK/CSC-1.17-arm64-v8a.apk)
- 套件／版本：`com.example.csc`／`1.17`／versionCode `18`
- native-code：僅 `arm64-v8a`
- APK SHA-256：`A0CAE427C5E4BA56F25A492966B24564DA52EEF9A54E8F141C986E00D0D02221`
- Git tag：`v1.17`

## 修改範圍

- `AutomationConfig.kt`：新增 line／element／symbol 的純 ROI 證據彙整與 `INSIDE`、`OUTSIDE`、`CROSSING`、`UNKNOWN` 位置分類；可靠區外 evidence 不再污染數字缺失分類，跨界／未知仍保守拒絕。
- `ScreenAutomationService.kt`：實際 OCR 路徑改用上述 helper；獨立標點需數字上下文；缺失 UI／Handler 共用 tracker absolute deadline；補上 decision、排程、dispatch 與手勢終態的同 id 紀錄；只有排程接手後才消費 swipe decision。
- `NumberMonitorTracker.kt`：提供缺失 started/deadline/count/confirmationDue 快照。
- `RecognitionZoneTest.kt`、`NumberMonitorTrackerTest.kt`：新增區外 line／element／symbol、跨界、未知框、標點、30 秒外部干擾與 deadline 回歸案例。
- 版本更新為 1.17／versionCode 18；未修改使用者 zones、ROI、threshold、色碼、target package 或診斷附件。

## 自動驗證

| 項目 | 結果 |
| --- | --- |
| 受影響 ROI／tracker／狀態機測試 | 通過 |
| 完整 `testDebugUnitTest --rerun-tasks` | 105 tests，0 failures、0 errors、0 skipped |
| `lintDebug` | 0 errors、47 warnings |
| `assembleDebug -PtargetAbi=arm64-v8a` | BUILD SUCCESSFUL |
| aapt2 套件／版本／ABI | `com.example.csc`／1.17／18／`arm64-v8a`，通過 |
| `git diff --check` | 通過 |

Gradle 執行前使用 repository-local Unix-domain socket 目錄：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
```

## 實機

- 裝置：LG LM-G710（serial `LMG710AWMff88f3c6`），Android 10／API 29。
- 使用 `adb install -r -t` 安裝回報 `Success`。
- 啟動 `com.example.csc/.MainActivity` 回報 `Status: ok`、`LaunchState: WARM`；安裝後 CSC PID 為 `17883`。
- 安裝後套件為 versionCode 18／versionName 1.17。
- AccessibilityService `com.example.csc/...ScreenAutomationService` 仍 enabled 且 bound。
- MediaProjection 仍顯示 `(com.example.csc, uid=10461): TYPE_SCREEN_CAPTURE`；未透過 ADB 繞過使用者同意。
- 裝置 `/data/app/.../base.apk` SHA-256 與交付 APK 相同。
- `automation.xml` 以 `adb exec-out` 與交接基線逐字比對相同，SHA-256 為 `84C2FC8DFE3F3EBFB71135D32F4F1718BA4B89325BBD81929E44066B4AD83328`。
- 啟動後最近 logcat 未見 `FATAL EXCEPTION`、`Fatal signal`、`ANR in` 或以 CSC process 為對象的 crash；另有 system_server 對舊 `/data/app/.../base.apk` 路徑的既有 I/O warning，非 CSC crash 證據。

## 驗證界線

本次未開啟 Shopee、未送出 tap／swipe／keyevent，也未改動 `observation_only`；因此尚未宣稱真實目標 App 的低值／高值／無數字辨識率、實際 dispatch 後換頁或使用者回報的直播間跳轉問題已完成 E2E 驗收。1.17 已修正並測試交接確認的「區外 OCR 干擾污染缺失倒數」路徑，新增的 action trace 可供下一次在授權安全場景追查 `decision → schedule → dispatch → terminal → page change`。
