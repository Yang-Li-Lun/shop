# CSC 1.19 工程修正與交付紀錄

日期：2026-09-13

本次依提供的工程文檔實作安全性、非同步正確性與可獨立驗證的效能修正。文檔中的大規模模組拆分、全量狀態遷移、正式私鑰遷移及長時間實測屬後續階段，沒有宣稱已完成全部路線圖。

## 已實作

- bundled profile 固定 disabled；讀取持久 enabled 時還須符合本程序明確啟用狀態。服務重連、中斷及銷毀會撤銷 runtime arming。
- interruption／前景變更取消排程、watchdog、pending flags、reward page 與辨識證據；保留擁有 Bitmap 的結果回呼讓它們安全釋放資源，舊 session 不得操作。
- ActionStateMachine 接管辨識 in-flight 與 frameId，移除 service 的 processing AtomicBoolean；舊 frame 不能結束新 frame，主執行緒套用 visual 結果後才完成辨識。
- 延遲點擊、二次確認、上滑及 post-swipe settle 具有 action owner；延遲重試仍驗證原 session。
- 數字上下限驗證與防禦性正規化，涵蓋倒置、負值、NaN／Infinity。
- 領取完整正向比對，拒絕倒數、成功、完成、紀錄、詳情；Accessibility 路徑額外要求 enabled 及 click action。保留六分鐘下限與三秒合格數字有效期限。
- 文字欄位 400 ms debounce；設定僅寫入有變更的 keys；loadSettings 不再無條件回寫。
- 模板 cache 最多 16 references、各 2 種 downscale；bitmap reference LRU 最多 24 張，eviction 同步清模板 cache。清理最長等 5 秒，逾時不 recycle worker 仍可能使用的 Bitmap。
- 顯示尺寸／密度／rotation 納入 session。Android 10 發現幾何改變會停止 capture、遞增 projection generation，必須重新同意擷取；未實作自動重建 VirtualDisplay。
- frame profile 加入 session generation 與 frameId。
- 新增外部簽章參數的 tools/build-release.ps1，包含測試、Debug/Release Lint、R8／resource shrink、arm64 build、apksigner verify；keystore 類檔案加入 Git ignore。

## 已驗證

- testDebugUnitTest：122 tests，0 failures。
- lintDebug／lintRelease：各 0 errors、50 warnings（未宣稱 warnings 已清空）。
- assembleRelease：成功，R8 與 shrinkResources 啟用，實機 package 無 DEBUGGABLE flag。
- APK：versionName 1.19、versionCode 20、僅 arm64-v8a。
- APK 內 default_profile.enabled=false。
- apksigner verify：通過，v3 signature。
- LG G7（LM_G710，授權裝置 LMG710AWMff88f3c6）：覆蓋安裝 Success、啟動 Status ok、前景 MainActivity、程序持續存活。
- 啟動畫面實際顯示「自動辨識 已關閉」、目標 com.shopee.tw、保留歷史統計。
- 啟動程序記錄未發現 FATAL EXCEPTION／ANR／Fatal signal，且未出現本次自動辨識 frame／手勢記錄。
- UIAutomator 因畫面持續更新未取得 idle；改用實際 screenshot 驗證畫面。
- 原 APK 及設定快照保存在忽略追蹤的 app/build/，未卸載或清除資料。尚未逐項比較所有設定的升級後值。

## 交付對應

- APK：APK/CSC-1.19-arm64-v8a.apk
- APK SHA-256：B1992AF0EA51E0BA92669289E37B6EBC081EDFC65784BE73953D9C5FE3517FDA
- Git tag：v1.19；對應 commit 可由 git rev-parse v1.19^{commit} 取得。
- 簽章 certificate SHA-256：0730f2a722c245b3d5c3c6a3c08875149a975a9ea348d9c35291779693f2c49d
- 為保留現有 Android 10 安裝資料，本次 Release 沿用 1.18 Android Debug certificate。沒有建立／宣稱已遷移至獨立正式私鑰。外部簽章流程已可接受未來正式 keystore。
- 本次未執行 Git push 或公開 Release。

## 尚待完成／實測

- 覆蓋安裝後 Android 清除了 Accessibility 與 MediaProjection 授權。必須由使用者重新同意並手動啟用；不得用 ADB 寫 secure settings 繞過。
- 真實直播完整 E2E、6 分鐘／7～8 分鐘晚出現領取、實機旋轉、30 分鐘記憶體與 latency baseline 尚未驗證。本次單元測試不可替代真實流程。
- 狀態機採文檔的漸進遷移，已移除 processing；clickPending、swipePending、prioritySwipePending 的完整移除與 Service controllers 拆分尚未完成。
- matching 預篩／round-robin frame budget、block-average fingerprint、完整 structured event log、SeekBar／region 拖曳的專用節流、backup／商店發布政策均未變更。
- 以上長期項目需各自完成測試與裝置比較，不能由本次 Release build 成功推定完成。