# CSC 1.20 工程交付與驗證

日期：2026-09-13

1.20 包含 1.19 的安全啟用、frame/action ownership、interruption、領取文字、數字範圍、debounce、bounded cache 與顯示幾何保護。完整實作與未納入的漸進重構項目見 CSC_1.19_交付與驗證.md。

## 1.19 實機 OCR 崩潰修正

1.19 主畫面可以啟動，但真正啟用辨識後，ML Kit 的 CommonComponentRegistrar、VisionCommonRegistrar、TextRegistrar 出現 NoSuchMethodException，繼而 OCR 初始化 NullPointerException。

合併 manifest 以名稱反射載入上述類別；既有 firebase-components consumer rule 只保留 class，未保留 public 無參數建構子。1.20 加入明確 constructor keep rule；新 R8 mapping 已確認三個建構子存在。

新增 ReleaseRuntimeCheck.java，以純 Java 反射在已安裝的 minified Release 上驗證三個建構子及中英文 OCR client 初始化。實機結果 PASS。最初 Kotlin 測試工具因引用已混淆的 Kotlin runtime 失敗，已由純 Java 工具替代；此測試工具失敗與產品 1.19 的 OCR 故障分別記錄。

1.19 APK／commit／tag 保留供追溯，不建議安裝。最終使用 1.20。

## 驗證與交付

- versionName：1.20；versionCode：21；ABI：arm64-v8a。
- testDebugUnitTest：122 項，0 failures（產品修正只涉及 R8 rule，測試結果重用相同來源）。
- lintDebug／lintRelease：0 errors、50 warnings。
- assembleRelease：成功，R8／shrinkResources 啟用。
- 實機 package 未含 DEBUGGABLE；LG G7 覆蓋安裝 Success，冷啟動 Status ok。
- ReleaseRuntimeCheck：三個 registrar constructors + Chinese/Latin OCR clients 初始化 PASS。
- APK：APK/CSC-1.20-arm64-v8a.apk。
- SHA-256：77B353651DB7E24F766BD6CC6C5AF4191B66D18E445F470619CE132632631102。
- 簽章 SHA-256：0730f2a722c245b3d5c3c6a3c08875149a975a9ea348d9c35291779693f2c49d。
- 本次 Release 保留既有 Android Debug certificate 以覆蓋安裝並保留資料；尚未遷移獨立正式私鑰。
- Git tag：v1.20；最終 commit 由 git rev-parse v1.20^{commit} 取得。未 push。

## 實機直播驗證

使用者重新授權後，已完成一輪真實直播的觀看／領取點擊流程／優先換頁觀察：

- 12:25:22.245 完成上滑進入本輪頁面；加上 900 ms settle，六分鐘下限約為 12:31:23.145。
- 畫面可見 0.25 獎勵；持續有 VALUE／STAY 觀察。期間 PARSE_AMBIGUOUS 只要求新觀察，未被直接當成 Missing 上滑。
- 12:31:30.341 出現唯一的 priority=true 上滑排程；此路徑由領取區點擊 Completed callback 啟動，且設定等待為 6 秒，因此點擊完成時間可推估為 12:31:24 左右（程式目前沒有獨立 click timestamp log，這是依控制流程與等待值推估）。
- 12:31:32.114 priority swipe 收到 terminal=COMPLETED，沒有重複優先手勢。
- 新頁持續 Missing 時，在 12:31:39.868 到期要求 fresh observation；12:31:40.204 才決定 SWIPE_ABSENT，12:31:41.777 完成換頁。
- 後續實際 screenshot 顯示不同直播主，以及重設為 05:55 的下一輪倒數。
- 本輪驗證的是真實 OCR、等待、點擊回呼、優先上滑與畫面切換；沒有核對獎勵帳戶的實際入帳金額。7～8 分鐘按鈕晚出現、實機旋轉、其他中斷情境及 30 分鐘記憶體測試仍未完成。
- 本輪 CSC 程序 PID 11791 持續存活，記錄未發現 FATAL／ANR／Fatal signal。數次 PSS 約 107～126 MiB；短時間觀察不能當作長時間無洩漏證明。
- 本機詳細記錄 app/build/csc-1.20-live.log 與前後 screenshot 保留於忽略追蹤的 build 資料夾，不納入公開 Git 資料。

## 重跑 Release 初始化檢查

設定專案的 JAVA_HOME 與 repository-local jdk.net.unixdomain.tmpdir 後：

```powershell
.\gradlew.bat --no-daemon assembleDebugAndroidTest '-PtargetAbi=arm64-v8a' '-PdeviceRunner=com.example.csc.ReleaseRuntimeCheck'
# 先安裝相同簽章的 Release APK，再安裝 test APK。
adb -s <authorized-serial> install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <authorized-serial> shell am instrument -w com.example.csc.test/com.example.csc.ReleaseRuntimeCheck
```

此測試不修改設定、不操作目標 App、不送出手勢。
本輪效能摘要：Frames=581; median=377ms; P95=1233ms。這是單一實機情境，未與 1.18 做同條件 baseline 比較。
