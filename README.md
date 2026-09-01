# CSC

CSC 是一個 Android 10+ 的本機多區域螢幕辨識／自動點擊工具。使用者可在各區域設定多個文字或參考圖片，App 會擷取目前螢幕、在裝置端辨識目標，並透過 Android `AccessibilityService` 在命中位置送出點擊手勢。

Android 套件識別碼為 `com.example.csc`。

## 功能

- 指定文字：先搜尋畫面的 accessibility 節點；找不到時以 ML Kit OCR 辨識中文字或拉丁文字。
- 參考圖片：以灰階亮度與邊緣特徵做多尺度模板比對，並對接近門檻的命中進行連續幀確認。
- 返回箭頭：直接辨識白色向左箭頭的方向、雙斜線、負空間與周邊隔離，不需要參考圖片。
- 點擊防抖：可設定 1–15 秒冷卻時間。
- 點擊位置：成功手勢會短暫顯示紫色定位圈，主畫面保留時間、來源與座標紀錄。
- 辨識區域：預設鎖定防止誤觸，開啟調整後以四角控制點限制辨識範圍，可一鍵恢復全螢幕。
- 可建立最多 8 個辨識區域；每區可設定多個文字與多張參考圖片，命中任一項目即點擊。
- 辨識執行期間會以不攔截觸控的不同顏色細線框出各辨識區域。
- 圖片區域會顯示目前最高相似度；符號比對採亮度正規化的邊緣形狀分數，適合箭頭、叉與圈等簡單圖示。
- OCR 會優先限制在文字與數字監控區域，減少整張螢幕辨識造成的耗電與誤判。
- 偵測服務停用時會停止輪詢；回到目標 App 以外的畫面時會降低掃描頻率。
- 最終點擊入口會再次驗證座標，區域外手勢一律拒絕。
- 命中門檻：圖片模式可設定 55–99%；圓圈＋X與返回箭頭共用可設定的 50–99% 圖形門檻。
- 安全停止：停用主畫面開關，或從持續通知點「立即停止」。
- 隱私：截圖與辨識全部留在裝置上；使用的是隨 App 打包的 ML Kit 模型。
- Android 10 透過使用者明確允許的 `MediaProjection` 前景服務擷取；Android 11+ 直接使用 Accessibility 截圖 API。

## 建置與安裝

需求：Android Studio（JDK 17）、Android SDK 36、Android 10 或更新的實機／模擬器。

1. 使用 Android Studio 開啟本資料夾並等待 Gradle Sync。
2. 執行 `./gradlew assembleDebug`（Windows 使用 `gradlew.bat assembleDebug`）。
3. 安裝 `app/build/outputs/apk/debug/app-debug.apk`。
4. 首次開啟後按「開啟無障礙服務」，選擇「CSC 螢幕辨識」並允許。
5. 輸入文字或選擇參考圖片，開啟「啟用自動辨識」，再切換到目標 App。

## 參考圖片建議

- 從同一支裝置、相同顯示縮放與方向的截圖裁切。
- 裁切範圍包含完整按鈕／圖示並保留少量四周邊界。
- 避免只使用純色區塊；有輪廓與文字的目標較可靠。
- 預設門檻 82%。若誤點，提高門檻；若漏掉，逐步降低。

## 系統限制與責任界線

- Android 的 `FLAG_SECURE` 畫面不可截圖；文字模式仍可能由 accessibility 節點找到文字。
- Android 10 每次重新啟動擷取服務時，都必須由使用者在系統對話框中允許螢幕擷取。
- 螢幕方向、字體大小、顯示縮放或目標動畫會降低圖片匹配效果。
- 目前支援多區域、多項目、數字等待判定與向上滑動；不包含雲端物件模型或多步驟工作流編輯器。
- 請勿用於付款、金融交易、驗證碼、刪除資料或其他不可逆操作。
- 若要上架 Google Play，必須確認 Accessibility API 的用途、揭露、同意流程與商店政策符合當時規範。

## 專案結構

- `MainActivity.kt`：設定與啟用介面。
- `automation/ScreenAutomationService.kt`：截圖排程、OCR、手勢與停止通知。
- `capture/MediaProjectionCaptureService.kt`：Android 10 的螢幕擷取相容層。
- `vision/TemplateMatcher.kt`：圖片模板比對演算法。
- `vision/BackArrowDetector.kt`：無參考圖片的白色向左箭頭幾何偵測。
- `GrayTemplateMatcherTest.kt`：純陣列比對核心測試。
