# CSC 1.14 驗證紀錄

日期：2026-09-04。工程說明：[CSC 1.14 工程交接](../../ENGINEERING_HANDOFF_CSC_1.14.md)。

## 交付物

- APK：`APK/CSC-1.14-arm64-v8a.apk`
- 套件／版本：`com.example.csc`／`1.14`／versionCode `15`
- native-code：僅 `arm64-v8a`
- APK SHA-256：`e0cbda7e59f93378ab3a1ee888ef2a371db9d89fc6406827ba94253949cdd78e`
- Signer certificate SHA-256：`0730f2a722c245b3d5c3c6a3c08875149a975a9ea348d9c35291779693f2c49d`，與 1.13 相同。
- Git tag：`v1.14`，tag 指向的提交含上述 APK、原始碼、測試與文件。使用 `git rev-parse 'v1.14^{commit}'` 查詢提交。

## 原始碼及 APK 比對

- NumberMonitorTracker 與 NumberRegionFingerprint 的執行程式碼與取回的 1.12 原始碼快照逐字相符（統一換行後比較）。
- 1.12 與 1.14 DEX 都使用 `isNumericElement`／`numberRegionFingerprint`；1.13 使用 `numericFragments`／`numberRegionSignature`。
- `MainActivity.kt`、`ui/`、`res/`、`vision/`、`capture/`、AutomationSession、ActionStateMachine、DailyTriggerStats 與交付前 1.13 HEAD 相同。
- 此比對確認演算法回復與介面保留，不是以 APK 符號取代實機準確率驗證。

## 自動驗證

| 項目 | 結果 |
| --- | --- |
| 數字 tracker／ROI 指紋／RecognitionZone 受影響測試 | 通過 |
| 完整 testDebugUnitTest | 86 tests，0 failures，0 errors，0 skipped |
| lintDebug | 0 errors、45 warnings；沒有宣稱零警告 |
| assembleDebug `-PtargetAbi=arm64-v8a` | BUILD SUCCESSFUL |
| git diff --check | 通過 |
| APK 版本／ABI／簽章 | 通過 |

完整 Gradle 命令：

```powershell
New-Item -ItemType Directory -Force -Path 'D:\codee\shop\app\build\uds' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=D:\codee\shop\app\build\uds'
.\gradlew.bat --no-daemon testDebugUnitTest lintDebug assembleDebug -PtargetAbi=arm64-v8a
```

Gradle 執行 JVM：本機既有 Oracle 21.0.11。完整建置回報耗時 44 秒；先前受影響測試建置為 31 秒。這不是裝置端辨識延遲。

| 測試類別 | 數量 |
| --- | ---: |
| ActionStateMachineTest | 5 |
| AdaptiveScanControllerTest | 3 |
| AutomationSessionTest | 2 |
| DailyTriggerStatsTest | 4 |
| NumberMonitorTrackerTest | 12 |
| NumberRegionFingerprintTest | 2 |
| RecognitionRegionTest | 4 |
| RecognitionZoneTest | 30 |
| MediaProjectionAuthorizationGateTest | 4 |
| MediaProjectionRequestStateTest | 2 |
| BackArrowDetectorTest | 2 |
| CircleXAutoCalibratorTest | 4 |
| CircleXDetectorTest | 4 |
| GrayTemplateMatcherTest | 8 |

新固定的回歸情境：拒絕混合 OCR 元素、非數字元素分隔相鄰數字、相同可靠 ROI 的 Invalid 不啟動缺失、變更 ROI 的 Invalid 按 1.12 缺失期限及新觀察確認。

## 實機

- 裝置：已授權 LG G7（LM-G710），Android 10／API 29。
- 更新前 1.13／code 14；使用 `install -r -t` 回報 Success，更新後 1.14／code 15。
- 更新前先切到 CSC 設定頁；不在購物 App 執行自動手勢。
- 更新前後主設定逐 key/value 比對：24 → 24 個 key，ChangedKeys 為空，SettingsPreserved=true。zones、門檻、ROI、時間、色碼與圖片 URI 契約保留。
- Accessibility 在更新後已 enabled 且 bound。
- 啟動 MainActivity 回報 Status: ok；Android 10 隨後顯示 MediaProjection 系統同意視窗。
- 使用者在系統視窗按「立即開始」後，MediaProjection 顯示 CSC 的 TYPE_SCREEN_CAPTURE；MediaProjectionCaptureService 為 isForeground=true。
- 最終前景為 CSC MainActivity；實際截圖確認「服務已連線」、三日統計、辨識區域／數字監控／辨識調整卡片，沒有系統欄遮住主畫面控制項。
- 完成「數字監控與上滑」卡片展開及收合，顯示原停留門檻 0.19；未修改設定，操作後再次比較設定一致。
- 手機已安裝 base.apk 的 SHA-256 與交付 APK 一致。
- 本次 CSC 程序 logcat 沒有 FATAL EXCEPTION／Fatal signal／ANR；23:00 以後 crash buffer 無 crash 記錄。裝置有前一日其他 App 的歷史 crash，不列為本版錯誤。
- UIAutomator 因畫面持續更新回報 `could not get idle state`，未採用留下的舊 XML 判定畫面；改以本次實際截圖完成視覺驗證。

原始設定快照保存在 `build/handoff-1.14/automation-before.xml` 與 `automation-after.xml`，不提交使用者設定內容。

實際畫面證據保存在本機 `build/handoff-1.14/ui-ready.png`、`ui-number-expanded.png`；本次建立的手機端 XML／PNG 暫存檔已刪除。

工作期間 `AGENTS.md` 另有新增 APK commit／tag 規則的外部修改；本次遵循該規則，保留其工作樹變更，未混入本次程式提交。tag 中交接文件同樣明列版本追溯規則。

## 驗證界線

未執行真實購物頁的五分鐘 A/B 辨識率與自動上滑驗收；未在 API 30+ 裝置重跑。本次確認的是核心回復、純邏輯回歸、建置及上述實機狀態。1.12 Invalid／缺失語意的誤上滑風險詳見工程交接文件。
