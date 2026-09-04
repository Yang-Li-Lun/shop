package com.example.csc.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.DisplayMetrics
import android.util.Log
import com.example.csc.MainActivity
import com.example.csc.R
import com.example.csc.capture.MediaProjectionCaptureService
import com.example.csc.vision.CircleXDetector
import com.example.csc.vision.CircleXAutoCalibrator
import com.example.csc.vision.BackArrowDetector
import com.example.csc.vision.TemplateMatcher
import com.example.csc.vision.VisualMatch
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ScreenAutomationService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val visionExecutor = Executors.newSingleThreadExecutor()
    private val processing = AtomicBoolean(false)
    private val clickPending = AtomicBoolean(false)
    private val swipePending = AtomicBoolean(false)
    private val prioritySwipePending = AtomicBoolean(false)
    private val destroyed = AtomicBoolean(false)
    private val actionState = ActionStateMachine()
    private val sessionGate = AutomationSessionGate()
    private val adaptiveScan = AdaptiveScanController()
    private val numberMonitorTracker = NumberMonitorTracker()
    private val circleXCalibrator = CircleXAutoCalibrator()
    private val latinRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val chineseRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var foregroundPackage: String? = null
    private var lastClickAt = 0L
    private val cachedReferences = mutableMapOf<String, Bitmap>()
    private val referenceCacheLock = Any()
    private val unavailableReferenceUntil = ConcurrentHashMap<String, Long>()
    private val zoneSimilarities = mutableMapOf<String, Float>()
    private val zoneStatuses = mutableMapOf<String, String>()
    private val targetQuality = ConcurrentHashMap<String, Float>()
    private val zoneRecognitionCache = ConcurrentHashMap<String, ZoneRecognitionCache>()
    private var pendingClickZoneId: String? = null
    private var lastResult = "等待目標出現"
    private var lastNotificationText: String? = null
    private var lastNotificationAt = 0L
    private var clickMarkerView: View? = null
    private var recognitionRegionOverlayView: RecognitionRegionOverlayView? = null
    private var numberAbsenceRunnable: Runnable? = null
    private var triggerSwipeRunnable: Runnable? = null
    private var numberCountdownDeadline = 0L
    private var numberCountdownLabel: String? = null
    private var numberOverlayBaseText = "尚未開始"
    private val numberCountdownRunnable = object : Runnable {
        override fun run() {
            if (numberCountdownDeadline <= 0L || numberCountdownLabel == null) return
            val remaining = numberCountdownDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                numberCountdownDeadline = 0L
                numberCountdownLabel = null
                renderNumberOverlay()
                return
            }
            renderNumberOverlay()
            mainHandler.postDelayed(this, 500L)
        }
    }
    private var monitoredPackage: String? = null
    private var detectedNumberOverlayText = "尚未開始"
    private var confirmedNumberDisplay = "尚未開始"
    private var circleXHoldingNumberCountdown = false
    private var textTargetHoldingNumberCountdown = false
    private var imageTargetHoldingNumberCountdown = false
    private val recognizedZoneIds = mutableSetOf<String>()
    private var prioritySwipeSourceZoneId: String? = null
    private var prioritySwipeSourceName: String? = null
    private var prioritySwipeObservedPackage: String? = null
    private var prioritySwipeSession: AutomationSession? = null
    private var prioritySwipeBlockedByRecognition = false
    private var lastVisualSafetyScanAt = Long.MIN_VALUE
    private var numberPriorityPassPending = false
    private var numberTrackerGeneration = 0L
    private var numberTrackerKey: NumberTrackerKey? = null
    private var pendingGestureToken: ActionToken? = null
    private var gestureWatchdog: Runnable? = null

    private val scanRunnable = object : Runnable {
        override fun run() = scanOnce()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        lastResult = "服務已連線"
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val activePackage = rootInActiveWindow?.packageName?.toString()
            ?: event?.packageName?.toString()
            ?: return
        if (activePackage == foregroundPackage) return
        foregroundPackage = activePackage
        sessionGate.invalidate()
        // A scan may have intentionally stopped while automation was disabled. Wake it whenever
        // the foreground app changes so enabling and immediately switching apps cannot miss the
        // one-shot refresh that creates the recognition-region overlay.
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.post(scanRunnable)
    }

    override fun onInterrupt() {
        lastResult = "服務被系統中斷"
    }

    override fun onDestroy() {
        destroyed.set(true)
        sessionGate.invalidate()
        gestureWatchdog?.let(mainHandler::removeCallbacks)
        gestureWatchdog = null
        pendingGestureToken = null
        connected = false
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        processing.set(false)
        clickPending.set(false)
        swipePending.set(false)
        prioritySwipePending.set(false)
        actionState.cancel()
        adaptiveScan.reset()
        numberMonitorTracker.reset()
        circleXCalibrator.reset()
        circleXHoldingNumberCountdown = false
        textTargetHoldingNumberCountdown = false
        imageTargetHoldingNumberCountdown = false
        recognizedZoneIds.clear()
        clearPrioritySwipeTracking()
        numberAbsenceRunnable = null
        triggerSwipeRunnable = null
        numberCountdownDeadline = 0L
        numberCountdownLabel = null
        resetImageEvidence()
        mainHandler.removeCallbacks(numberCountdownRunnable)
        runCatching { latinRecognizer.close() }
        runCatching { chineseRecognizer.close() }
        visionExecutor.shutdownNow()
        releaseReferencesAfterVisionStops()
        removeClickMarker()
        removeRecognitionRegionOverlay()
        notificationManager().cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun scanOnce() {
        rootInActiveWindow?.packageName?.toString()?.let { foregroundPackage = it }
        val settings = AutomationConfig.read(this)
        currentSession(settings)
        if (foregroundPackage != monitoredPackage) {
            monitoredPackage = foregroundPackage
            circleXHoldingNumberCountdown = false
            textTargetHoldingNumberCountdown = false
            imageTargetHoldingNumberCountdown = false
            recognizedZoneIds.clear()
            if (prioritySwipePending.get()) cancelPrioritySwipe("前景頁面已變更，取消優先上滑")
            resetNumberAbsenceTracking()
            numberMonitorTracker.reset()
            numberTrackerKey = null
            confirmedNumberDisplay = "尚未開始"
            resetImageEvidence()
        }
        if (!settings.numberMonitorEnabled) {
            resetNumberAbsenceTracking()
            numberMonitorTracker.reset()
            numberTrackerKey = null
        } else {
            syncNumberTrackerGeneration(settings, foregroundPackage)
        }
        val targetForeground = isTargetForeground(settings)
        syncRecognitionRegionOverlay(
            regions = overlayRegions(settings),
            visible = settings.enabled && targetForeground,
        )
        showOrHideNotification(settings)
        if (!settings.enabled) {
            resetImageEvidence()
            mainHandler.removeCallbacks(scanRunnable)
            return
        }
        val nextDelay = adaptiveScan.nextDelay(
            minOf(settings.scanIntervalMs, FAST_SCAN_INTERVAL_MS),
            SystemClock.elapsedRealtime(),
            !targetForeground,
        )
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, nextDelay)

        if (
            !settings.enabled || processing.get() || clickPending.get() || swipePending.get() ||
            actionState.blocksRecognition()
        ) return
        if (!targetForeground) {
            lastResult = if (settings.targetPackage.isBlank()) {
                "已暫停：尚未設定目標 App"
            } else {
                "已暫停：僅允許 ${settings.targetPackage}"
            }
            return
        }
        pruneReferenceCache(settings)
        val clicksAllowed = !prioritySwipePending.get() &&
            SystemClock.elapsedRealtime() - lastClickAt >= settings.clickCooldownMs
        if (settings.targetCount == 0 && !settings.numberMonitorEnabled) return
        if (clicksAllowed && settings.numberTriggerZoneId != null) {
            findConfiguredTextNode(settings, settings.numberTriggerZoneId)?.let { hit ->
                val metrics = gestureDisplayMetrics()
                tap(
                    hit.bounds,
                    hit.zone.region,
                    hit.zone.id,
                    "${hit.zone.name} · 優先文字「${hit.target.value}」",
                    metrics.widthPixels,
                    metrics.heightPixels,
                )
                return
            }
        }
        if (!settings.numberMonitorEnabled && clicksAllowed) {
            findConfiguredTextNode(settings)?.let { hit ->
                val metrics = gestureDisplayMetrics()
                tap(
                    hit.bounds,
                    hit.zone.region,
                    hit.zone.id,
                    "${hit.zone.name} · 文字「${hit.target.value}」",
                    metrics.widthPixels,
                    metrics.heightPixels,
                )
                return
            }
        }

        captureAndRecognize(settings, clicksAllowed)
    }

    private fun captureAndRecognize(settings: AutomationSettings, clicksAllowed: Boolean) {
        if (!processing.compareAndSet(false, true)) return
        if (!actionState.tryStartRecognition()) {
            processing.set(false)
            return
        }
        val frameProfile = FrameProfile().also { it.session = currentSession(settings) }
        val capturePackage = foregroundPackage
        if (Build.VERSION.SDK_INT < 30) {
            MediaProjectionCaptureService.requestFrame { bitmap ->
                frameProfile.captureMs = frameProfile.elapsedMs()
                frameProfile.markMainCallbackPosted()
                mainHandler.post {
                    frameProfile.captureCallbackWaitMs = frameProfile.elapsedSincePostMs()
                    if (destroyed.get()) {
                        bitmap?.recycle()
                        return@post
                    }
                    if (!isSessionCurrent(frameProfile.session)) {
                        bitmap?.recycle()
                        finishProcessing(frameProfile)
                        return@post
                    }
                    if (bitmap == null) {
                        finishProcessing(frameProfile)
                        lastResult = "請允許 CSC 擷取螢幕"
                    } else {
                        recognizeBitmap(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
                    }
                }
            }
            return
        }
        captureWithAccessibility(settings, capturePackage, clicksAllowed, frameProfile)
    }

    @android.annotation.TargetApi(30)
    private fun captureWithAccessibility(
        settings: AutomationSettings,
        capturePackage: String?,
        clicksAllowed: Boolean,
        frameProfile: FrameProfile,
    ) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    frameProfile.captureMs = frameProfile.elapsedMs()
                    val conversionStartedAt = SystemClock.elapsedRealtime()
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    }
                    frameProfile.bitmapConversionMs = SystemClock.elapsedRealtime() - conversionStartedAt
                    if (bitmap == null) {
                        finishProcessing(frameProfile)
                        lastResult = "無法讀取截圖"
                        return
                    }
                    if (destroyed.get()) {
                        bitmap.recycle()
                        return
                    }
                    if (!isSessionCurrent(frameProfile.session)) {
                        bitmap.recycle()
                        finishProcessing(frameProfile)
                        return
                    }

                    recognizeBitmap(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
                }

                override fun onFailure(errorCode: Int) {
                    finishProcessing(frameProfile)
                    lastResult = when (errorCode) {
                        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "截圖間隔過短，稍後重試"
                        else -> "截圖失敗（$errorCode）"
                    }
                }
            },
        )
    }

    private fun recognizeBitmap(
        bitmap: Bitmap,
        settings: AutomationSettings,
        capturePackage: String?,
        clicksAllowed: Boolean,
        frameProfile: FrameProfile,
    ) {
        if (!isSessionCurrent(frameProfile.session)) {
            bitmap.recycle()
            finishProcessing(frameProfile)
            return
        }
        val fingerprintStartedAt = SystemClock.elapsedRealtime()
        val frameChanged = adaptiveScan.observeFrame(bitmapFingerprint(bitmap))
        frameProfile.fingerprintMs = SystemClock.elapsedRealtime() - fingerprintStartedAt
        if (!frameChanged) {
            bitmap.recycle()
            finishProcessing(frameProfile)
            return
        }
        circleXHoldingNumberCountdown = false
        textTargetHoldingNumberCountdown = false
        imageTargetHoldingNumberCountdown = false
        recognizedZoneIds.clear()
        val now = SystemClock.elapsedRealtime()
        if (shouldRunVisualSafetyScan(
                settings.numberMonitorEnabled,
                lastVisualSafetyScanAt,
                now,
                numberPriorityPassPending,
            )
        ) {
            lastVisualSafetyScanAt = now
            numberPriorityPassPending = settings.numberMonitorEnabled
            recognizePriorityCircleX(bitmap, settings, capturePackage, clicksAllowed, frameProfile) {
                recognizeBitmapAfterPriority(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
            }
        } else {
            // A full Circle-X / back-arrow pass is substantially more expensive than OCR
            // on current devices.  Keep the number decision responsive between those
            // safety passes without disabling the visual targets altogether.
            numberPriorityPassPending = false
            recognizeBitmapAfterPriority(
                bitmap,
                settings,
                capturePackage,
                clicksAllowed,
                frameProfile,
                skipVisualSafetyScan = true,
            )
        }
    }

    private fun recognizeBitmapAfterPriority(
        bitmap: Bitmap,
        settings: AutomationSettings,
        capturePackage: String?,
        clicksAllowed: Boolean,
        frameProfile: FrameProfile,
        skipVisualSafetyScan: Boolean = false,
    ) {
        val textTargets = settings.zones.flatMap { zone ->
            zone.targets.filter { it.mode == TargetMode.TEXT }.map { zone to it }
        }.sortedByDescending { (zone, _) -> zone.id == settings.numberTriggerZoneId }
        if (textTargets.isEmpty() && !settings.numberMonitorEnabled) {
            if (skipVisualSafetyScan) {
                bitmap.recycle()
                finishProcessing(frameProfile)
            } else {
                recognizeImages(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
            }
            return
        }
        val ocrRegion = ocrRegion(settings, textTargets)
        val preparedOcr = cropRecognitionRegionCopy(bitmap, ocrRegion)
        val ocrBitmap = preparedOcr.bitmap
        fun releaseOcrBitmap() {
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) ocrBitmap.recycle()
        }
        val recognizer = if (textTargets.any { containsCjk(it.second.value) }) {
            chineseRecognizer
        } else {
            latinRecognizer
        }
        val ocrStartedAt = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
            .addOnSuccessListener { result ->
                frameProfile.ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
                if (destroyed.get()) {
                    releaseOcrBitmap()
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                    return@addOnSuccessListener
                }
                if (!resultIsStillRelevant(settings, capturePackage, frameProfile.session)) {
                    releaseOcrBitmap()
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                    return@addOnSuccessListener
                }
                val recognizedTextHits = textTargets.mapNotNull { (zone, target) ->
                    findTextBounds(
                        result,
                        target.value,
                        zone.region,
                        bitmap.width,
                        bitmap.height,
                        preparedOcr.offsetX,
                        preparedOcr.offsetY,
                    )?.let { bounds -> TextHit(zone, target, bounds) }
                }
                textTargetHoldingNumberCountdown = recognizedTextHits.isNotEmpty()
                recognizedZoneIds += recognizedTextHits.map { it.zone.id }
                if (isAnyRecognitionHoldingNumberCountdown(settings)) resetNumberAbsenceTracking()
                observeNumbers(
                    result,
                    settings,
                    capturePackage,
                    bitmap,
                    preparedOcr.offsetX,
                    preparedOcr.offsetY,
                    frameProfile.session,
                )
                if (swipePending.get()) {
                    releaseOcrBitmap()
                    // Keep image percentages fresh while a delayed swipe is pending. Image
                    // clicks remain disabled so the two gestures cannot conflict.
                    recognizeImages(bitmap, settings, capturePackage, clicksAllowed = false, frameProfile = frameProfile)
                    return@addOnSuccessListener
                }
                // Once an image region is already above its configured threshold, let the
                // image pipeline act first. Otherwise a persistent OCR target can consume
                // every cooldown window while the image keeps displaying 99% without a tap.
                val imageHasPriority = imageAtOrAboveThreshold(settings)
                val hit = if (clicksAllowed && !imageHasPriority) recognizedTextHits.firstOrNull() else null
                if (hit != null) {
                    tap(
                        ClickBounds(hit.bounds.left.toFloat(), hit.bounds.top.toFloat(), hit.bounds.right.toFloat(), hit.bounds.bottom.toFloat()),
                        hit.zone.region,
                        hit.zone.id,
                        "${hit.zone.name} · OCR「${hit.target.value}」",
                        bitmap.width,
                        bitmap.height,
                        actionSession = frameProfile.session,
                    )
                    releaseOcrBitmap()
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                } else {
                    releaseOcrBitmap()
                    if (skipVisualSafetyScan) {
                        bitmap.recycle()
                        finishProcessing(frameProfile)
                    } else {
                        recognizeImages(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
                    }
                }
            }
            .addOnFailureListener { error ->
                frameProfile.ocrMs = SystemClock.elapsedRealtime() - ocrStartedAt
                if (destroyed.get()) {
                    releaseOcrBitmap()
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                    return@addOnFailureListener
                }
                textTargetHoldingNumberCountdown = false
                observeInvalidNumber(settings, capturePackage, bitmap, "辨識失敗", frameProfile.session)
                if (!settings.numberMonitorEnabled) lastResult = "文字辨識失敗"
                releaseOcrBitmap()
                if (skipVisualSafetyScan) {
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                } else {
                    recognizeImages(bitmap, settings, capturePackage, clicksAllowed, frameProfile)
                }
            }
    }

    private fun recognizePriorityCircleX(
        bitmap: Bitmap,
        settings: AutomationSettings,
        capturePackage: String?,
        clicksAllowed: Boolean,
        frameProfile: FrameProfile,
        continuePipeline: () -> Unit,
    ) {
        val zone = settings.zones.firstOrNull { candidate ->
            candidate.targets.any { it.mode == TargetMode.CIRCLE_X }
        }
        val target = zone?.targets?.firstOrNull { it.mode == TargetMode.CIRCLE_X }
        if (zone == null || target == null) {
            circleXHoldingNumberCountdown = false
            continuePipeline()
            return
        }
        frameProfile.markVisionQueued()
        visionExecutor.execute {
            frameProfile.circleXQueueWaitMs = frameProfile.elapsedSinceVisionQueuedMs()
            val prepared = cropRecognitionRegionCopy(bitmap, zone.region)
            val calibration = circleXCalibrator.calibration(zone.id, settings.circleXThreshold)
            val circleXStartedAt = SystemClock.elapsedRealtime()
            val match = try {
                CircleXDetector.find(
                    prepared.bitmap,
                    calibration.minDiameterRatio,
                    calibration.maxDiameterRatio,
                )
            } catch (error: Exception) {
                Log.e(TAG, "Circle-X recognition failed for zone ${zone.id}", error)
                null
            }
            frameProfile.circleXMs = SystemClock.elapsedRealtime() - circleXStartedAt
            val score = match?.score ?: 0f
            val currentCalibration = circleXCalibrator.calibration(zone.id, settings.circleXThreshold, score)
            zoneRecognitionCache[zone.id] = ZoneRecognitionCache(
                bitmapFingerprint(prepared.bitmap),
                visualTargetSignature(zone),
                target.id,
                match,
                SystemClock.elapsedRealtime(),
                calibrationObserved = match != null,
            )
            if (match != null) {
                val ratio = match.width / minOf(prepared.bitmap.width, prepared.bitmap.height)
                circleXCalibrator.observe(
                    zone.id,
                    settings.circleXThreshold,
                    score,
                    ratio,
                    accepted = score >= currentCalibration.effectiveThreshold + HIGH_CONFIDENCE_MARGIN,
                )
            }
            if (currentCalibration.nearThreshold) adaptiveScan.markNearThreshold(SystemClock.elapsedRealtime())
            if (prepared.bitmap !== bitmap) prepared.bitmap.recycle()
            frameProfile.markMainCallbackPosted()
            mainHandler.post {
                frameProfile.mainCallbackWaitMs += frameProfile.elapsedSincePostMs()
                if (destroyed.get()) {
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                    return@post
                }
                    if (!resultIsStillRelevant(settings, capturePackage, frameProfile.session)) {
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                    return@post
                }
                zoneSimilarities[zone.id] = score
                val circleXAboveThreshold = shouldHoldSwipeCountdownForVisualTarget(
                    score,
                    currentCalibration.effectiveThreshold,
                )
                circleXHoldingNumberCountdown = circleXAboveThreshold
                if (circleXAboveThreshold) recognizedZoneIds += zone.id
                if (isAnyRecognitionHoldingNumberCountdown(settings)) resetNumberAbsenceTracking()
                zoneStatuses[zone.id] = if (circleXAboveThreshold) {
                    if (settings.numberMonitorEnabled) "倒數重設" else "命中"
                } else {
                    "門檻 ${(currentCalibration.effectiveThreshold * 100).roundToInt()}%"
                }
                syncRecognitionRegionOverlay(overlayRegions(settings), isTargetForeground(settings))
                if (match != null && circleXAboveThreshold && clicksAllowed) {
                    val centerX = match.clickCenterX + prepared.offsetX
                    val centerY = match.clickCenterY + prepared.offsetY
                    tap(
                        visualSafeClickBounds(
                            centerX,
                            centerY,
                            match.width,
                            match.height,
                            CIRCLE_X_SAFE_HALF_RATIO,
                        ),
                        zone.region,
                        zone.id,
                        "${zone.name} · 優先圓圈＋X ${(score * 100).toInt()}%",
                        bitmap.width,
                        bitmap.height,
                        target.id,
                        ImageVerification(
                            target.value,
                            zone.region,
                            currentCalibration.effectiveThreshold,
                            centerX,
                            centerY,
                            maxOf(match.width, match.height),
                            TargetMode.CIRCLE_X,
                            currentCalibration.minDiameterRatio,
                            currentCalibration.maxDiameterRatio,
                        ),
                        actionSession = frameProfile.session,
                    )
                    bitmap.recycle()
                    finishProcessing(frameProfile)
                } else {
                    continuePipeline()
                }
            }
        }
    }

    private fun recognizeImages(
        bitmap: Bitmap,
        settings: AutomationSettings,
        capturePackage: String?,
        clicksAllowed: Boolean,
        frameProfile: FrameProfile,
    ) {
        val imageZones = settings.zones.filter { zone ->
            zone.targets.any { it.mode.isVisual() }
        }.sortedByDescending { zone -> zone.id == settings.numberTriggerZoneId }
        val captureWidth = bitmap.width
        val captureHeight = bitmap.height
        if (imageZones.isEmpty()) {
            if (!settings.numberMonitorEnabled) lastResult = "尚未找到任何設定項目"
            updatePrioritySwipeRecognitionGate(settings)
            bitmap.recycle()
            finishProcessing(frameProfile)
            return
        }
        frameProfile.markVisionQueued()
        visionExecutor.execute {
            try {
                val hits = mutableListOf<ImageHit>()
                val currentSimilarities = mutableMapOf<String, Float>()
                val effectiveThresholds = mutableMapOf<String, Float>()
                val circleCalibrations = mutableMapOf<String, com.example.csc.vision.CircleXCalibration>()
                for (zone in imageZones) {
                    val zoneStartedAt = SystemClock.elapsedRealtime()
                    val prepared = cropRecognitionRegionCopy(bitmap, zone.region)
                    try {
                        var bestTarget: RecognitionTarget? = null
                        var bestMatch: VisualMatch? = null
                        var circleCalibrationAlreadyObserved = false
                        val circleXTarget = zone.targets.firstOrNull { it.mode == TargetMode.CIRCLE_X }
                        val backArrowTarget = zone.targets.firstOrNull { it.mode == TargetMode.BACK_ARROW }
                        val imageTargets = zone.targets
                            .filter { it.mode == TargetMode.IMAGE }
                            .sortedByDescending { targetQuality[it.id] ?: 0.5f }
                        val fingerprint = bitmapFingerprint(prepared.bitmap)
                        val targetSignature = visualTargetSignature(zone)
                        val cached = zoneRecognitionCache[zone.id]?.takeIf {
                            it.fingerprint == fingerprint && it.targetSignature == targetSignature &&
                                SystemClock.elapsedRealtime() - it.createdAt <= 2_500L
                        }
                        if (cached != null) {
                            bestTarget = (imageTargets + listOfNotNull(circleXTarget, backArrowTarget))
                                .firstOrNull { it.id == cached.targetId }
                            bestMatch = cached.match
                            circleCalibrationAlreadyObserved = cached.calibrationObserved
                        } else if (circleXTarget != null) {
                            bestTarget = circleXTarget
                            val calibration = circleXCalibrator.calibration(zone.id, settings.circleXThreshold)
                            circleCalibrations[zone.id] = calibration
                            bestMatch = CircleXDetector.find(
                                prepared.bitmap,
                                calibration.minDiameterRatio,
                                calibration.maxDiameterRatio,
                            )
                            val detectedMatch = bestMatch
                            if (detectedMatch != null) {
                                zoneRecognitionCache[zone.id] = ZoneRecognitionCache(
                                    fingerprint,
                                    targetSignature,
                                    circleXTarget.id,
                                    detectedMatch,
                                    SystemClock.elapsedRealtime(),
                                )
                            }
                        } else if (backArrowTarget != null) {
                            bestTarget = backArrowTarget
                            val backArrowStartedAt = SystemClock.elapsedRealtime()
                            bestMatch = BackArrowDetector.find(prepared.bitmap)
                            frameProfile.backArrowMs[zone.id] = SystemClock.elapsedRealtime() - backArrowStartedAt
                            val detectedMatch = bestMatch
                            if (detectedMatch != null) {
                                zoneRecognitionCache[zone.id] = ZoneRecognitionCache(
                                    fingerprint,
                                    targetSignature,
                                    backArrowTarget.id,
                                    detectedMatch,
                                    SystemClock.elapsedRealtime(),
                                )
                            }
                        } else TemplateMatcher.prepare(prepared.bitmap).use { matcher ->
                            for (target in imageTargets) {
                                val reference = loadReference(target.value)
                                if (reference == null) {
                                    frameProfile.referenceLoadFailures++
                                    continue
                                }
                                val templateStartedAt = SystemClock.elapsedRealtime()
                                val match = matcher.findBest(reference)
                                frameProfile.templateMatcherMs[target.id] =
                                    (frameProfile.templateMatcherMs[target.id] ?: 0L) +
                                        (SystemClock.elapsedRealtime() - templateStartedAt)
                                if (match == null) continue
                                val previousQuality = targetQuality[target.id] ?: match.score
                                val learnedQuality = previousQuality * 0.82f + match.score * 0.18f
                                targetQuality[target.id] = learnedQuality
                                // Learned quality controls evaluation order, but the raw score
                                // always selects the winner so the displayed percentage and
                                // click threshold can never disagree.
                                if (bestMatch == null || match.score > bestMatch.score) {
                                    bestTarget = target
                                    bestMatch = match
                                }
                            }
                            if (bestTarget != null && bestMatch != null) {
                                zoneRecognitionCache[zone.id] = ZoneRecognitionCache(
                                    fingerprint,
                                    targetSignature,
                                    bestTarget.id,
                                    bestMatch,
                                    SystemClock.elapsedRealtime(),
                                )
                            }
                        }
                        bestMatch?.let { match ->
                            currentSimilarities[zone.id] = match.score
                            val selectedTarget = bestTarget
                            val requiredThreshold = if (selectedTarget?.mode == TargetMode.CIRCLE_X) {
                                val calibration = circleXCalibrator.calibration(
                                    zone.id,
                                    settings.circleXThreshold,
                                    match.score,
                                )
                                circleCalibrations[zone.id] = calibration
                                val diameterRatio = match.width / minOf(prepared.bitmap.width, prepared.bitmap.height)
                                if (!circleCalibrationAlreadyObserved) {
                                    circleXCalibrator.observe(
                                        zone.id,
                                        settings.circleXThreshold,
                                        match.score,
                                        diameterRatio,
                                        accepted = match.score >= calibration.effectiveThreshold + HIGH_CONFIDENCE_MARGIN,
                                    )
                                }
                                if (calibration.nearThreshold) {
                                    adaptiveScan.markNearThreshold(SystemClock.elapsedRealtime())
                                }
                                calibration.effectiveThreshold
                            } else configuredVisualThreshold(selectedTarget?.mode, settings)
                            effectiveThresholds[zone.id] = requiredThreshold
                            if (clicksAllowed && match.score >= requiredThreshold && selectedTarget != null) {
                                hits += ImageHit(zone, selectedTarget, match, prepared.offsetX, prepared.offsetY)
                            }
                        } ?: run { currentSimilarities[zone.id] = 0f }
                    } finally {
                        frameProfile.visualZoneMs[zone.id] = SystemClock.elapsedRealtime() - zoneStartedAt
                        if (prepared.bitmap !== bitmap) prepared.bitmap.recycle()
                    }
                }
                frameProfile.markMainCallbackPosted()
                mainHandler.post {
                    frameProfile.mainCallbackWaitMs += frameProfile.elapsedSincePostMs()
                    if (destroyed.get()) return@post
                    val completedHits = hits
                    val relevant = resultIsStillRelevant(settings, capturePackage, frameProfile.session)
                    if (relevant) {
                        zoneSimilarities.keys.retainAll(imageZones.map { it.id }.toSet())
                        zoneSimilarities.putAll(currentSimilarities)
                        zoneStatuses.keys.retainAll(imageZones.map { it.id }.toSet())
                        imageZones.forEach { zone ->
                            val score = currentSimilarities[zone.id] ?: 0f
                            val visualMode = zone.primaryVisualMode()
                            val threshold = effectiveThresholds[zone.id]
                                ?: configuredVisualThreshold(visualMode, settings)
                            zoneStatuses[zone.id] = when {
                                pendingClickZoneId == zone.id -> "點擊中"
                                score >= threshold && !clicksAllowed -> "冷卻"
                                score >= threshold -> "命中"
                                visualMode != TargetMode.IMAGE -> "門檻 ${(threshold * 100).roundToInt()}%"
                                else -> ""
                            }
                        }
                        imageTargetHoldingNumberCountdown = imageZones.any { zone ->
                            val score = currentSimilarities[zone.id] ?: 0f
                            val threshold = effectiveThresholds[zone.id]
                                ?: configuredVisualThreshold(zone.primaryVisualMode(), settings)
                            shouldHoldSwipeCountdownForVisualTarget(score, threshold)
                        }
                        imageZones.forEach { zone ->
                            val score = currentSimilarities[zone.id] ?: 0f
                            val threshold = effectiveThresholds[zone.id]
                                ?: configuredVisualThreshold(zone.primaryVisualMode(), settings)
                            if (shouldHoldSwipeCountdownForVisualTarget(score, threshold)) {
                                recognizedZoneIds += zone.id
                            }
                        }
                        if (isAnyRecognitionHoldingNumberCountdown(settings)) resetNumberAbsenceTracking()
                        updatePrioritySwipeRecognitionGate(settings)
                        syncRecognitionRegionOverlay(
                            regions = overlayRegions(settings),
                            visible = isTargetForeground(settings),
                        )
                    }
                    val confirmedHit = if (relevant) {
                        completedHits.maxWithOrNull(
                            compareBy<ImageHit> { if (it.zone.id == settings.numberTriggerZoneId) 1 else 0 }
                                .thenBy { it.match.score },
                        )
                    } else {
                        null
                    }
                    if (confirmedHit != null) {
                        val safeCenterX = confirmedHit.match.clickCenterX + confirmedHit.offsetX
                        val safeCenterY = confirmedHit.match.clickCenterY + confirmedHit.offsetY
                        val hitThreshold = effectiveThresholds[confirmedHit.zone.id]
                            ?: configuredVisualThreshold(confirmedHit.target.mode, settings)
                        val calibration = circleCalibrations[confirmedHit.zone.id]
                        val safeHalfRatio = visualSafeHalfRatio(confirmedHit.target.mode)
                        tap(
                            visualSafeClickBounds(
                                safeCenterX,
                                safeCenterY,
                                confirmedHit.match.width,
                                confirmedHit.match.height,
                                safeHalfRatio,
                            ),
                            confirmedHit.zone.region,
                            confirmedHit.zone.id,
                            visualHitLabel(confirmedHit),
                            captureWidth,
                            captureHeight,
                            confirmedHit.target.id,
                            ImageVerification(
                                referenceUri = confirmedHit.target.value,
                                region = confirmedHit.zone.region,
                                threshold = hitThreshold,
                                expectedX = safeCenterX,
                                expectedY = safeCenterY,
                                expectedSize = maxOf(confirmedHit.match.width, confirmedHit.match.height),
                                visualMode = confirmedHit.target.mode,
                                minDiameterRatio = calibration?.minDiameterRatio ?: 0.16f,
                                maxDiameterRatio = calibration?.maxDiameterRatio ?: 0.72f,
                            ),
                            actionSession = frameProfile.session,
                        )
                    } else if (completedHits.isNotEmpty() && relevant && !settings.numberMonitorEnabled) {
                        val bestPending = completedHits.maxByOrNull { it.match.score }!!
                        lastResult = "${visualHitLabel(bestPending)}，等待下一幀確認"
                    } else if (!settings.numberMonitorEnabled) {
                        lastResult = "尚未找到目標（${settings.zones.size} 個區域）"
                    }
                    frameProfile.log()
                }
            } catch (error: Throwable) {
                lastResult = "視覺辨識失敗：${error.localizedMessage ?: "未知錯誤"}"
            } finally {
                bitmap.recycle()
                finishProcessing()
            }
        }
    }

    private fun finishProcessing(frameProfile: FrameProfile? = null) {
        frameProfile?.log()
        processing.set(false)
        actionState.recognitionFinished()
    }

    private fun loadReference(uriString: String): Bitmap? {
        if (destroyed.get()) return null
        synchronized(referenceCacheLock) {
            cachedReferences[uriString]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val now = SystemClock.elapsedRealtime()
        unavailableReferenceUntil[uriString]?.let { retryAt ->
            if (now < retryAt) return null
            unavailableReferenceUntil.remove(uriString, retryAt)
        }

        if (uriString.startsWith(ASSET_URI_PREFIX)) {
            val assetPath = uriString.removePrefix(ASSET_URI_PREFIX)
            val bitmap = runCatching {
                assets.open(assetPath).use(android.graphics.BitmapFactory::decodeStream)
            }.getOrNull() ?: return null
            val cached = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (cached !== bitmap) bitmap.recycle()
            return cacheReference(uriString, cached)
        }

        val originalUri = Uri.parse(uriString)
        val candidates = listOfNotNull(originalUri, mediaStoreFallback(originalUri)).distinct()
        val bitmap = candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                contentResolver.openInputStream(candidate)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()
        } ?: run {
            unavailableReferenceUntil[uriString] = now + UNAVAILABLE_REFERENCE_RETRY_MS
            return null
        }
        unavailableReferenceUntil.remove(uriString)
        val cached = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        if (cached !== bitmap) bitmap.recycle()
        return cacheReference(uriString, cached)
    }

    private fun cacheReference(uriString: String, bitmap: Bitmap): Bitmap? =
        synchronized(referenceCacheLock) {
            if (destroyed.get()) {
                bitmap.recycle()
                null
            } else {
                cachedReferences.put(uriString, bitmap)?.takeIf { it !== bitmap }?.recycle()
                bitmap
            }
        }

    private fun pruneReferenceCache(settings: AutomationSettings) {
        val activeUris = settings.zones.asSequence()
            .flatMap { it.targets.asSequence() }
            .filter { it.mode == TargetMode.IMAGE }
            .map { it.value }
            .toSet()
        val removed = synchronized(referenceCacheLock) {
            cachedReferences.keys.filterNot(activeUris::contains).mapNotNull(cachedReferences::remove)
        }
        unavailableReferenceUntil.keys.filterNot(activeUris::contains).forEach(unavailableReferenceUntil::remove)
        removed.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun releaseReferencesAfterVisionStops() {
        Thread({
            var terminated = false
            while (!terminated) {
                terminated = try {
                    visionExecutor.awaitTermination(1L, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    false
                }
            }
            val references = synchronized(referenceCacheLock) {
                cachedReferences.values.toList().also { cachedReferences.clear() }
            }
            references.forEach { if (!it.isRecycled) it.recycle() }
        }, "csc-reference-cleanup").start()
    }

    private fun mediaStoreFallback(uri: Uri): Uri? {
        if (uri.authority != "com.android.providers.media.documents") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val parts = documentId.split(':', limit = 2)
        if (parts.size != 2 || parts[0] != "image") return null
        val mediaId = parts[1].toLongOrNull() ?: return null
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId)
    }

    private fun resultIsStillRelevant(
        capturedSettings: AutomationSettings,
        capturePackage: String?,
        capturedSession: AutomationSession? = null,
    ): Boolean {
        val current = AutomationConfig.read(this)
        if (!current.enabled) return false
        if (foregroundPackage != capturePackage || !isTargetForeground(current)) return false
        if (capturedSession != null && !isSessionCurrent(capturedSession)) return false
        return current.zones == capturedSettings.zones &&
            current.targetPackage == capturedSettings.targetPackage &&
            current.matchThreshold == capturedSettings.matchThreshold &&
            current.circleXThreshold == capturedSettings.circleXThreshold &&
            current.numberMonitorEnabled == capturedSettings.numberMonitorEnabled &&
            current.numberMonitorRegion == capturedSettings.numberMonitorRegion &&
            current.numberMonitorThreshold == capturedSettings.numberMonitorThreshold &&
            current.numberMonitorUpperLimit == capturedSettings.numberMonitorUpperLimit &&
            current.numberColorFilterEnabled == capturedSettings.numberColorFilterEnabled &&
            current.numberColorHex == capturedSettings.numberColorHex &&
            current.numberColorTolerance == capturedSettings.numberColorTolerance &&
            current.numberAbsenceTimeoutMs == capturedSettings.numberAbsenceTimeoutMs &&
            current.numberTriggerZoneId == capturedSettings.numberTriggerZoneId &&
            current.numberTriggerDelayMs == capturedSettings.numberTriggerDelayMs
    }

    private fun currentSession(settings: AutomationSettings): AutomationSession = sessionGate.update(
        targetPackage = settings.targetPackage,
        foregroundPackage = foregroundPackage,
        configSignature = settings.sessionSignature(),
        projectionGeneration = if (Build.VERSION.SDK_INT < 30) {
            MediaProjectionCaptureService.projectionGeneration
        } else {
            0L
        },
    )

    private fun isSessionCurrent(session: AutomationSession?): Boolean {
        if (session == null || destroyed.get()) return false
        val settings = AutomationConfig.read(this)
        return sessionGate.isCurrent(currentSession(settings)) && sessionGate.isCurrent(session) &&
            isTargetForeground(settings)
    }

    private fun isActionCurrent(token: ActionToken): Boolean = isSessionCurrent(token.session) &&
        sessionGate.isCurrent(token)

    private fun syncNumberTrackerGeneration(
        settings: AutomationSettings,
        observedPackage: String?,
    ) {
        val key = NumberTrackerKey(
            foregroundPackage = observedPackage,
            targetPackage = settings.targetPackage,
            enabled = settings.enabled,
            monitorEnabled = settings.numberMonitorEnabled,
            region = settings.numberMonitorRegion.normalized(),
            threshold = settings.numberMonitorThreshold,
            upperLimit = settings.numberMonitorUpperLimit,
            colorFilterEnabled = settings.numberColorFilterEnabled,
            colorHex = settings.numberColorHex,
            colorTolerance = settings.numberColorTolerance,
            absenceTimeoutMs = settings.numberAbsenceTimeoutMs,
            triggerZoneId = settings.numberTriggerZoneId,
            triggerDelayMs = settings.numberTriggerDelayMs,
        )
        if (key == numberTrackerKey) return
        numberTrackerKey = key
        numberTrackerGeneration++
        numberMonitorTracker.reset()
        resetNumberAbsenceTracking()
    }

    private fun resetImageEvidence() {
        // Image hits no longer retain cross-frame state. Reaching the configured threshold
        // enters the click flow immediately.
        zoneRecognitionCache.clear()
    }

    private fun imageAtOrAboveThreshold(settings: AutomationSettings): Boolean =
        settings.zones.any { zone ->
            val visualMode = zone.primaryVisualMode()
            visualMode != null &&
                (zoneSimilarities[zone.id] ?: 0f) >= configuredVisualThreshold(visualMode, settings)
        }

    private fun findConfiguredTextNode(settings: AutomationSettings, onlyZoneId: String? = null): NodeHit? {
        val root = rootInActiveWindow ?: return null
        val configured = settings.zones.flatMap { zone ->
            if (onlyZoneId != null && zone.id != onlyZoneId) return@flatMap emptyList()
            zone.targets.filter { it.mode == TargetMode.TEXT }.map { target ->
                Triple(zone, target, normalize(target.value))
            }
        }
        if (configured.isEmpty()) return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var tightestHit: NodeHit? = null
        var tightestArea = Long.MAX_VALUE
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            val nodeValues = sequenceOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString() }
                .map(::normalize)
                .toList()
            if (nodeValues.isNotEmpty() && node.isVisibleToUser) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val metrics = gestureDisplayMetrics()
                if (!bounds.isEmpty) {
                    configured.firstOrNull { (zone, _, target) ->
                        nodeValues.any { it.contains(target) } &&
                            zone.region.hasSafeClickArea(
                                bounds.left.toFloat(),
                                bounds.top.toFloat(),
                                bounds.right.toFloat(),
                                bounds.bottom.toFloat(),
                                metrics.widthPixels,
                                metrics.heightPixels,
                            )
                    }?.let { (zone, target, _) ->
                        val area = bounds.width().toLong() * bounds.height().toLong()
                        if (area in 1 until tightestArea) {
                            tightestArea = area
                            tightestHit = NodeHit(
                                zone,
                                target,
                                ClickBounds(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                            )
                        }
                    }
                }
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return tightestHit
    }

    private fun findTextBounds(
        result: Text,
        target: String,
        region: RecognitionRegion,
        screenWidth: Int,
        screenHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): Rect? {
        val normalizedTarget = normalize(target)
        val lines = result.textBlocks.flatMap { it.lines }
        val candidates = buildList {
            // Prefer the individual OCR element. A line or block can span
            // several nearby controls and would make a valid random point hit
            // the wrong item.
            lines.flatMap { it.elements }
                .filter { normalize(it.text).contains(normalizedTarget) }
                .mapNotNullTo(this) { it.boundingBox?.offsetCopy(offsetX, offsetY) }
            lines.filter { normalize(it.text).contains(normalizedTarget) }
                .mapNotNullTo(this) { it.boundingBox?.offsetCopy(offsetX, offsetY) }
            result.textBlocks.filter { normalize(it.text).contains(normalizedTarget) }
                .mapNotNullTo(this) { it.boundingBox?.offsetCopy(offsetX, offsetY) }
        }
        return candidates
            .filter { bounds ->
                region.hasSafeClickArea(
                    bounds.left.toFloat(),
                    bounds.top.toFloat(),
                    bounds.right.toFloat(),
                    bounds.bottom.toFloat(),
                    screenWidth,
                    screenHeight,
                )
            }
            .minByOrNull { bounds -> bounds.width().toLong() * bounds.height().toLong() }
    }

    private fun observeNumbers(
        result: Text,
        settings: AutomationSettings,
        capturePackage: String?,
        bitmap: Bitmap,
        offsetX: Int,
        offsetY: Int,
        capturedSession: AutomationSession?,
    ) {
        if (!settings.numberMonitorEnabled || foregroundPackage != capturePackage ||
            !isSessionCurrent(capturedSession)
        ) return
        syncNumberTrackerGeneration(settings, capturePackage)
        val roiFingerprint = numberMonitorFingerprint(bitmap, settings.numberMonitorRegion)
        val filterColor = if (settings.numberColorFilterEnabled) {
            runCatching { Color.parseColor(settings.numberColorHex.trim()) }.getOrNull()
        } else {
            null
        }
        if (settings.numberColorFilterEnabled && filterColor == null) {
            numberMonitorTracker.reset()
            resetNumberAbsenceTracking()
            updateDetectedNumberDisplay("色碼格式錯誤")
            lastResult = "數字色碼錯誤，請用 #RRGGBB。"
            return
        }
        val candidates = buildList {
            result.textBlocks.flatMap { it.lines }.forEach { line ->
                val elements = line.elements.mapNotNull { element ->
                    element.boundingBox?.offsetCopy(offsetX, offsetY)?.let { bounds ->
                        NumberTextElement(
                            text = element.text,
                            bounds = ClickBounds(
                                bounds.left.toFloat(),
                                bounds.top.toFloat(),
                                bounds.right.toFloat(),
                                bounds.bottom.toFloat(),
                            ),
                        )
                    }
                }
                val tokens = rebuildNumberTokens(elements)
                if (tokens.isNotEmpty()) {
                    tokens.forEach { token ->
                        add(
                            NumberTextCandidate(
                                text = token.text,
                                centerDistanceSquared = 0.0,
                                area = ((token.bounds.right - token.bounds.left) *
                                    (token.bounds.bottom - token.bounds.top)).toLong().coerceAtLeast(1L),
                                bounds = token.bounds,
                            ),
                        )
                    }
                } else if (elements.isEmpty()) {
                    // Keep a conservative fallback for recognizers that return line text without
                    // element boxes. Once element boxes exist, an unrecognised line is treated as
                    // missing instead of using a loose line-sized color/position candidate.
                    line.boundingBox?.offsetCopy(offsetX, offsetY)?.let { bounds ->
                        add(
                            NumberTextCandidate(
                                text = line.text,
                                centerDistanceSquared = 0.0,
                                area = bounds.width().toLong() * bounds.height().toLong(),
                                bounds = ClickBounds(
                                    bounds.left.toFloat(),
                                    bounds.top.toFloat(),
                                    bounds.right.toFloat(),
                                    bounds.bottom.toFloat(),
                                ),
                            ),
                        )
                    }
                }
            }
        }
        val normalizedMonitorRegion = settings.numberMonitorRegion.normalized()
        val monitorCenterX = (normalizedMonitorRegion.left + normalizedMonitorRegion.right) / 2f
        val monitorCenterY = (normalizedMonitorRegion.top + normalizedMonitorRegion.bottom) / 2f
        val values = candidates
            .filter { candidate ->
                val bounds = candidate.bounds ?: return@filter false
                settings.numberMonitorRegion.containsBounds(
                    bounds.left,
                    bounds.top,
                    bounds.right,
                    bounds.bottom,
                    bitmap.width,
                    bitmap.height,
                ) && (filterColor == null || numberBoundsContainColor(
                    bitmap,
                    Rect(
                        bounds.left.toInt(),
                        bounds.top.toInt(),
                        bounds.right.toInt(),
                        bounds.bottom.toInt(),
                    ),
                    filterColor,
                    settings.numberColorTolerance,
                ))
            }
            .map { candidate ->
                val bounds = candidate.bounds!!
                val centerX = (bounds.left + bounds.right) / 2f / bitmap.width
                val centerY = (bounds.top + bounds.bottom) / 2f / bitmap.height
                val deltaX = (centerX - monitorCenterX).toDouble()
                val deltaY = (centerY - monitorCenterY).toDouble()
                candidate.copy(centerDistanceSquared = deltaX * deltaX + deltaY * deltaY)
            }
            .let(::selectNumberMonitorValues)

        val displayText = if (values.isEmpty()) {
            if (filterColor == null) "無數字" else "無符合顏色數字"
        } else {
            formatRecognizedNumbers(values)
        }
        val priorityPending = prioritySwipePending.get()
        val observedNumber = values.maxOrNull()?.let { value -> NumberMonitorTracker.Observation.Value(value) }
            ?: NumberMonitorTracker.Observation.Missing
        val trackerAction = numberMonitorTracker.observe(
            nowMs = SystemClock.elapsedRealtime(),
            observation = observedNumber,
            roiFingerprint = roiFingerprint,
            threshold = settings.numberMonitorThreshold,
            upperLimit = settings.numberMonitorUpperLimit,
            absenceTimeoutMs = settings.numberAbsenceTimeoutMs,
            prioritySwipePending = priorityPending,
            generation = numberTrackerGeneration,
        )

        if (priorityPending) {
            updateDetectedNumberDisplay(displayText)
            return
        }

        if (isAnyRecognitionHoldingNumberCountdown(settings)) {
            numberMonitorTracker.reset()
            resetNumberAbsenceTracking()
            updateDetectedNumberDisplay(
                if (values.isEmpty()) "目標命中" else displayText,
            )
            lastResult = "辨識區域命中目標，上滑倒數已重設"
            return
        }

        if (values.isNotEmpty()) {
            resetNumberAbsenceTracking()
            val maximum = values.maxOrNull() ?: return
            val decision = decideNumberMonitorAction(
                values,
                settings.numberMonitorThreshold,
                settings.numberMonitorUpperLimit,
            )
            val riskReason = if (decision == NumberMonitorDecision.SWIPE_UP) {
                if (maximum > settings.numberMonitorUpperLimit) {
                    "數字 $maximum 超過上限 ${settings.numberMonitorUpperLimit}"
                } else {
                    "數字 $maximum 小於門檻 ${settings.numberMonitorThreshold}"
                }
            } else null
            applyNumberMonitorAction(
                action = trackerAction,
                nowMs = SystemClock.elapsedRealtime(),
                settings = settings,
                capturePackage = capturePackage,
                displayText = displayText,
                absenceReason = "連續沒有偵測到數字",
                riskReason = riskReason,
                stayMessage = "數字 $maximum，停留目前頁面",
                actionSession = capturedSession,
            )
            return
        }

        applyNumberMonitorAction(
            action = trackerAction,
            nowMs = SystemClock.elapsedRealtime(),
            settings = settings,
            capturePackage = capturePackage,
            displayText = displayText,
            absenceReason = if (filterColor == null) {
                "連續沒有偵測到數字"
            } else {
                "連續沒有偵測到符合顏色的數字"
            },
            riskReason = null,
            stayMessage = null,
            actionSession = capturedSession,
        )
    }

    private fun observeInvalidNumber(
        settings: AutomationSettings,
        capturePackage: String?,
        bitmap: Bitmap,
        displayText: String,
        capturedSession: AutomationSession?,
    ) {
        if (!settings.numberMonitorEnabled || foregroundPackage != capturePackage ||
            !isSessionCurrent(capturedSession)
        ) return
        syncNumberTrackerGeneration(settings, capturePackage)
        val action = numberMonitorTracker.observe(
            nowMs = SystemClock.elapsedRealtime(),
            observation = NumberMonitorTracker.Observation.Invalid,
            roiFingerprint = numberMonitorFingerprint(bitmap, settings.numberMonitorRegion),
            threshold = settings.numberMonitorThreshold,
            upperLimit = settings.numberMonitorUpperLimit,
            absenceTimeoutMs = settings.numberAbsenceTimeoutMs,
            prioritySwipePending = prioritySwipePending.get(),
            generation = numberTrackerGeneration,
        )
        if (prioritySwipePending.get()) {
            updateDetectedNumberDisplay(displayText)
            return
        }
        if (isAnyRecognitionHoldingNumberCountdown(settings)) {
            numberMonitorTracker.reset()
            resetNumberAbsenceTracking()
            updateDetectedNumberDisplay("目標命中")
            lastResult = "辨識區域命中目標，上滑倒數已重設"
            return
        }
        applyNumberMonitorAction(
            action = action,
            nowMs = SystemClock.elapsedRealtime(),
            settings = settings,
            capturePackage = capturePackage,
            displayText = displayText,
            absenceReason = "連續無法辨識數字",
            riskReason = null,
            stayMessage = null,
            actionSession = capturedSession,
        )
    }

    private fun applyNumberMonitorAction(
        action: NumberMonitorAction,
        nowMs: Long,
        settings: AutomationSettings,
        capturePackage: String?,
        displayText: String,
        absenceReason: String,
        riskReason: String?,
        stayMessage: String?,
        actionSession: AutomationSession?,
    ) {
        when (action) {
            NumberMonitorAction.STAY -> {
                resetNumberAbsenceTracking()
                if (displayText.isNotBlank() && !displayText.contains("無") && !displayText.contains("失敗")) {
                    confirmedNumberDisplay = displayText
                }
                updateDetectedNumberDisplay(displayText)
                stayMessage?.let { lastResult = it }
            }
            NumberMonitorAction.WAIT_FOR_CONFIRMATION -> {
                updateDetectedNumberDisplay("${confirmedNumberDisplay.takeIf { it != "尚未開始" } ?: displayText}\n確認中")
                adaptiveScan.requestConfirmation(nowMs)
                mainHandler.removeCallbacks(scanRunnable)
                mainHandler.postDelayed(scanRunnable, NUMBER_CONFIRMATION_SCAN_DELAY_MS)
                lastResult = "${riskReason ?: "數字需確認"}，等待確認"
            }
            NumberMonitorAction.START_OR_KEEP_ABSENCE -> {
                updateDetectedNumberDisplay("${confirmedNumberDisplay.takeIf { it != "尚未開始" } ?: "無數字"}\n重新確認")
                scheduleNumberWaitSwipe(absenceReason, settings.numberAbsenceTimeoutMs, capturePackage)
            }
            NumberMonitorAction.REQUEST_FRESH_OBSERVATION -> {
                resetNumberAbsenceTracking()
                val status = if (displayText.contains("失敗")) "辨識失敗" else {
                    confirmedNumberDisplay.takeIf { it != "尚未開始" } ?: "無數字"
                }
                updateDetectedNumberDisplay("$status\n重新確認")
                requestFreshNumberObservation(nowMs)
            }
            NumberMonitorAction.SWIPE_LOW,
            NumberMonitorAction.SWIPE_HIGH,
            -> {
                numberMonitorTracker.markActionConsumed()
                scheduleSwipeUp(riskReason ?: "數字條件觸發上滑", actionSession = actionSession)
            }
            NumberMonitorAction.SWIPE_ABSENT -> {
                numberMonitorTracker.markActionConsumed()
                scheduleSwipeUp("$absenceReason；期限點仍無數字", actionSession = actionSession)
            }
        }
    }

    private fun ocrRegion(
        settings: AutomationSettings,
        textTargets: List<Pair<RecognitionZone, RecognitionTarget>>,
    ): RecognitionRegion {
        val regions = buildList {
            textTargets.mapTo(this) { it.first.region.normalized() }
            if (settings.numberMonitorEnabled) add(settings.numberMonitorRegion.normalized())
        }
        if (regions.isEmpty()) return RecognitionRegion.FULL
        val paddingX = 0.02f
        val paddingY = 0.02f
        return RecognitionRegion(
            left = regions.minOf { it.left } - paddingX,
            top = regions.minOf { it.top } - paddingY,
            right = regions.maxOf { it.right } + paddingX,
            bottom = regions.maxOf { it.bottom } + paddingY,
        ).normalized()
    }

    private fun numberBoundsContainColor(
        bitmap: Bitmap,
        bounds: Rect,
        targetColor: Int,
        tolerance: Int,
    ): Boolean {
        val left = bounds.left.coerceIn(0, bitmap.width - 1)
        val top = bounds.top.coerceIn(0, bitmap.height - 1)
        val right = bounds.right.coerceIn(left + 1, bitmap.width)
        val bottom = bounds.bottom.coerceIn(top + 1, bitmap.height)
        val step = maxOf(1, minOf(right - left, bottom - top) / 40)
        val targetRed = Color.red(targetColor)
        val targetGreen = Color.green(targetColor)
        val targetBlue = Color.blue(targetColor)
        val allowedDistance = tolerance * tolerance * 3
        var matches = 0
        var samples = 0
        for (y in top until bottom step step) {
            for (x in left until right step step) {
                samples++
                val color = bitmap.getPixel(x, y)
                val red = Color.red(color) - targetRed
                val green = Color.green(color) - targetGreen
                val blue = Color.blue(color) - targetBlue
                if (red * red + green * green + blue * blue <= allowedDistance) matches++
            }
        }
        return hasSufficientNumberColorCoverage(matches, samples)
    }

    private fun formatSeconds(milliseconds: Long): String =
        if (milliseconds % 1_000L == 0L) {
            (milliseconds / 1_000L).toString()
        } else {
            "%.1f".format(Locale.US, milliseconds / 1_000.0)
        }

    private fun formatSecondsTenths(milliseconds: Long): String =
        "%.1f".format(Locale.US, milliseconds.coerceAtLeast(0L) / 1_000.0)

    private fun updateDetectedNumberDisplay(value: String) {
        numberOverlayBaseText = value
        renderNumberOverlay()
    }

    private fun isAnyRecognitionHoldingNumberCountdown(settings: AutomationSettings): Boolean =
        settings.numberMonitorEnabled && (
            circleXHoldingNumberCountdown ||
                textTargetHoldingNumberCountdown ||
                imageTargetHoldingNumberCountdown
            )

    private fun renderNumberOverlay() {
        val settings = AutomationConfig.read(this)
        val remaining = numberCountdownDeadline - SystemClock.elapsedRealtime()
        detectedNumberOverlayText = if (numberCountdownLabel != null && remaining > 0L) {
            "$numberOverlayBaseText\n上滑 ${formatSecondsTenths(remaining)} 秒"
        } else if (prioritySwipeBlockedByRecognition) {
            "$numberOverlayBaseText\n優先：等待辨識降低"
        } else if (isAnyRecognitionHoldingNumberCountdown(settings) && !prioritySwipePending.get()) {
            "$numberOverlayBaseText\n辨識：重設"
        } else {
            numberOverlayBaseText.replace('\n', ' ').take(24)
        }
        syncRecognitionRegionOverlay(
            regions = overlayRegions(settings),
            visible = settings.enabled && isTargetForeground(settings),
        )
    }

    private fun resetNumberAbsenceTracking() {
        numberAbsenceRunnable?.let(mainHandler::removeCallbacks)
        numberAbsenceRunnable = null
        numberCountdownDeadline = 0L
        numberCountdownLabel = null
        mainHandler.removeCallbacks(numberCountdownRunnable)
        renderNumberOverlay()
    }

    private fun startNumberCountdown(label: String, waitMs: Long) {
        numberCountdownLabel = label
        numberCountdownDeadline = SystemClock.elapsedRealtime() + waitMs
        mainHandler.removeCallbacks(numberCountdownRunnable)
        mainHandler.post(numberCountdownRunnable)
        renderNumberOverlay()
    }

    private fun requestFreshNumberObservation(nowMs: Long) {
        adaptiveScan.requestConfirmation(nowMs)
        mainHandler.removeCallbacks(scanRunnable)
        mainHandler.postDelayed(scanRunnable, NUMBER_CONFIRMATION_SCAN_DELAY_MS)
        lastResult = "等待期限點重新辨識"
    }

    private fun scheduleNumberWaitSwipe(reason: String, waitMs: Long, observedPackage: String?) {
        if (prioritySwipePending.get() || numberAbsenceRunnable != null) return
        val waitSeconds = formatSeconds(waitMs)
        val scheduledGeneration = numberTrackerGeneration
        val runnable = Runnable {
            numberAbsenceRunnable = null
            val current = AutomationConfig.read(this)
            if (prioritySwipePending.get()) {
                resetNumberAbsenceTracking()
                return@Runnable
            }
            if (isAnyRecognitionHoldingNumberCountdown(current)) {
                resetNumberAbsenceTracking()
                lastResult = "辨識區域仍有目標，上滑倒數維持重設"
                return@Runnable
            }
            if (
                current.enabled && current.numberMonitorEnabled &&
                foregroundPackage == observedPackage && isTargetForeground(current)
            ) {
                syncNumberTrackerGeneration(current, observedPackage)
                if (scheduledGeneration != numberTrackerGeneration) {
                    resetNumberAbsenceTracking()
                    return@Runnable
                }
                when (numberMonitorTracker.onAbsenceDeadline(
                    SystemClock.elapsedRealtime(),
                    scheduledGeneration,
                )) {
                    NumberMonitorAction.REQUEST_FRESH_OBSERVATION -> {
                        resetNumberAbsenceTracking()
                        updateDetectedNumberDisplay("無數字\n重新確認")
                        requestFreshNumberObservation(SystemClock.elapsedRealtime())
                    }
                    NumberMonitorAction.START_OR_KEEP_ABSENCE -> {
                        // A handler can run a little early. Keep the deadline safe by leaving the
                        // tracker in absence state and asking for a fresh observation instead of
                        // ever converting the timer directly into a swipe.
                        updateDetectedNumberDisplay("無數字\n重新確認")
                        requestFreshNumberObservation(SystemClock.elapsedRealtime())
                    }
                    else -> resetNumberAbsenceTracking()
                }
            }
        }
        numberAbsenceRunnable = runnable
        mainHandler.postDelayed(runnable, waitMs)
        lastResult = "$reason，等待 $waitSeconds 秒"
        startNumberCountdown("$reason，等待上滑", waitMs)
    }

    private fun scheduleConfiguredTriggerSwipe(
        zoneId: String,
        zoneName: String,
        settings: AutomationSettings,
        session: AutomationSession,
    ) {
        triggerSwipeRunnable?.let(mainHandler::removeCallbacks)
        resetNumberAbsenceTracking()
        numberMonitorTracker.markActionConsumed()
        prioritySwipePending.set(true)
        actionState.gestureFinished(cooldown = false)
        prioritySwipeSourceZoneId = zoneId
        prioritySwipeSourceName = zoneName
        prioritySwipeObservedPackage = foregroundPackage
        prioritySwipeSession = session
        prioritySwipeBlockedByRecognition = false
        startPrioritySwipeCountdown(settings)
    }

    private fun startPrioritySwipeCountdown(settings: AutomationSettings) {
        triggerSwipeRunnable?.let(mainHandler::removeCallbacks)
        val delayMs = settings.numberTriggerDelayMs
        val waitSeconds = formatSeconds(delayMs)
        val zoneName = prioritySwipeSourceName ?: return
        val detectedPackage = prioritySwipeObservedPackage
        val detectedSession = prioritySwipeSession
        val runnable = Runnable {
            triggerSwipeRunnable = null
            val current = AutomationConfig.read(this)
            if (
                !isSessionCurrent(detectedSession) ||
                !current.enabled || !current.numberMonitorEnabled ||
                current.numberTriggerZoneId != prioritySwipeSourceZoneId ||
                foregroundPackage != detectedPackage || !isTargetForeground(current)
            ) {
                cancelPrioritySwipe("已取消過期的優先上滑")
                return@Runnable
            }
            if (hasRecognitionOutsideTriggerZone(recognizedZoneIds, prioritySwipeSourceZoneId)) {
                prioritySwipeBlockedByRecognition = true
                resetNumberAbsenceTracking()
                lastResult = "其他辨識區域命中，等待降低後重新倒數"
                return@Runnable
            }
            scheduleSwipeUp(
                "$zoneName 已觸發一次後等待 $waitSeconds 秒",
                priority = true,
                actionSession = detectedSession,
            )
        }
        triggerSwipeRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
        startNumberCountdown("$zoneName 已觸發，等待上滑", delayMs)
        lastResult = if (delayMs == 0L) {
            "$zoneName 已觸發一次，準備上滑"
        } else {
            "$zoneName 已觸發一次，等待 $waitSeconds 秒後上滑"
        }
    }

    private fun updatePrioritySwipeRecognitionGate(settings: AutomationSettings) {
        if (!prioritySwipePending.get() || swipePending.get() || prioritySwipeSourceZoneId == null) return
        val blocked = hasRecognitionOutsideTriggerZone(recognizedZoneIds, prioritySwipeSourceZoneId)
        if (blocked) {
            triggerSwipeRunnable?.let(mainHandler::removeCallbacks)
            triggerSwipeRunnable = null
            prioritySwipeBlockedByRecognition = true
            resetNumberAbsenceTracking()
            lastResult = "其他辨識區域命中，優先上滑等待中"
        } else if (prioritySwipeBlockedByRecognition) {
            prioritySwipeBlockedByRecognition = false
            startPrioritySwipeCountdown(settings)
            lastResult = "其他辨識已降低，重新等待 ${formatSeconds(settings.numberTriggerDelayMs)} 秒後上滑"
        }
    }

    private fun clearPrioritySwipeTracking() {
        prioritySwipeSourceZoneId = null
        prioritySwipeSourceName = null
        prioritySwipeObservedPackage = null
        prioritySwipeSession = null
        prioritySwipeBlockedByRecognition = false
        numberMonitorTracker.markActionConsumed()
    }

    private fun cancelPrioritySwipe(reason: String) {
        triggerSwipeRunnable?.let(mainHandler::removeCallbacks)
        triggerSwipeRunnable = null
        prioritySwipePending.set(false)
        clearPrioritySwipeTracking()
        actionState.cancelSwipe()
        resetNumberAbsenceTracking()
        lastResult = reason
    }

    private fun gestureDisplayMetrics(): DisplayMetrics = DisplayMetrics().also { metrics ->
        @Suppress("DEPRECATION")
        val display = getSystemService(WindowManager::class.java)?.defaultDisplay
        if (display != null) display.getRealMetrics(metrics) else metrics.setTo(resources.displayMetrics)
    }

    private fun scheduleSwipeUp(
        reason: String,
        priority: Boolean = false,
        actionSession: AutomationSession? = null,
    ) {
        if (!priority && prioritySwipePending.get()) return
        if (clickPending.get()) {
            mainHandler.postDelayed({ scheduleSwipeUp(reason, priority, actionSession) }, 100L)
            return
        }
        val settings = AutomationConfig.read(this)
        if (!settings.enabled || !settings.numberMonitorEnabled || !isTargetForeground(settings)) {
            if (priority) {
                prioritySwipePending.set(false)
                clearPrioritySwipeTracking()
            }
            return
        }
        if (!swipePending.compareAndSet(false, true)) {
            if (priority) {
                prioritySwipePending.set(false)
                clearPrioritySwipeTracking()
            }
            return
        }
        if (!actionState.armPrioritySwipe()) {
            swipePending.set(false)
            if (priority) {
                prioritySwipePending.set(false)
                clearPrioritySwipeTracking()
            }
            return
        }
        val actionToken = if (actionSession != null) {
            actionSession.takeIf(sessionGate::isCurrent)?.let { sessionGate.token(null, null) }
        } else {
            currentSession(settings)
            sessionGate.token(null, null)
        }
        if (actionToken == null) {
            swipePending.set(false)
            if (priority) {
                prioritySwipePending.set(false)
                clearPrioritySwipeTracking()
            }
            actionState.cancelSwipe()
            return
        }
        resetNumberAbsenceTracking()
        val spec = randomSwipeSpec(settings.randomClickMaxMs)
        val detectedPackage = foregroundPackage
        lastResult = "$reason；等待 ${spec.delayMs} ms 後向上滑"
        mainHandler.postDelayed({
            val current = AutomationConfig.read(this)
            if (
                !isActionCurrent(actionToken) ||
                !current.enabled || !current.numberMonitorEnabled ||
                foregroundPackage != detectedPackage || !isTargetForeground(current)
            ) {
                swipePending.set(false)
                if (priority) {
                    prioritySwipePending.set(false)
                    clearPrioritySwipeTracking()
                }
                actionState.cancelSwipe()
                lastResult = "已取消過期的滑動操作"
                return@postDelayed
            }
            val metrics = gestureDisplayMetrics()
            if (!actionState.beginSwiping()) {
                swipePending.set(false)
                if (priority) {
                    prioritySwipePending.set(false)
                    clearPrioritySwipeTracking()
                }
                return@postDelayed
            }
            val path = Path().apply {
                moveTo(metrics.widthPixels * spec.startXRatio, metrics.heightPixels * spec.startYRatio)
                lineTo(metrics.widthPixels * spec.endXRatio, metrics.heightPixels * spec.endYRatio)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, spec.durationMs))
                .build()
            val accepted = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (!finishGesture(actionToken)) return
                        if (priority) {
                            prioritySwipePending.set(false)
                            clearPrioritySwipeTracking()
                        }
                        actionState.gestureFinished()
                        adaptiveScan.markGesture(SystemClock.elapsedRealtime())
                        resetNumberAbsenceTracking()
                        val dailyCount = if (priority) {
                            DailyTriggerStats.recordCompletedSwipe(this@ScreenAutomationService)
                        } else null
                        lastResult = if (dailyCount != null) {
                            "已向上滑：$reason；今日第 $dailyCount 次"
                        } else {
                            "已向上滑：$reason"
                        }
                        showOrHideNotification(AutomationConfig.read(this@ScreenAutomationService))
                        mainHandler.removeCallbacks(scanRunnable)
                        // dispatchGesture completes before the target app's inertial scroll
                        // necessarily settles. Keep recognition/clicking blocked so a moving
                        // control near the bottom cannot be mistaken for the next-page target.
                        mainHandler.postDelayed({
                            swipePending.set(false)
                            mainHandler.post(scanRunnable)
                        }, POST_SWIPE_SETTLE_MS)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (!finishGesture(actionToken)) return
                        swipePending.set(false)
                        if (priority) {
                            prioritySwipePending.set(false)
                            clearPrioritySwipeTracking()
                        }
                        actionState.cancelSwipe()
                        lastResult = "滑動被系統取消"
                    }
                },
                mainHandler,
            )
            if (!accepted) {
                swipePending.set(false)
                if (priority) {
                    prioritySwipePending.set(false)
                    clearPrioritySwipeTracking()
                }
                actionState.cancelSwipe()
                lastResult = "系統拒絕滑動手勢"
            } else {
                armGestureWatchdog(actionToken) {
                    if (actionState.cancelSwipe()) {
                        swipePending.set(false)
                        if (priority) {
                            prioritySwipePending.set(false)
                            clearPrioritySwipeTracking()
                        }
                        lastResult = "滑動回呼逾時，已解除等待"
                    }
                }
            }
        }, spec.delayMs)
    }

    private fun tap(
        targetBounds: ClickBounds,
        allowedRegion: RecognitionRegion,
        zoneId: String,
        description: String,
        captureWidth: Int,
        captureHeight: Int,
        imageTargetId: String? = null,
        imageVerification: ImageVerification? = null,
        actionSession: AutomationSession? = null,
    ) {
        val settings = AutomationConfig.read(this)
        if (!settings.enabled || !isTargetForeground(settings)) return
        if (prioritySwipePending.get()) return
        val metrics = gestureDisplayMetrics()
        val gestureBounds = mapClickBoundsToScreen(
            targetBounds,
            captureWidth,
            captureHeight,
            metrics.widthPixels,
            metrics.heightPixels,
        ) ?: run {
            lastResult = "無法換算辨識座標"
            return
        }
        val point = randomClickPoint(
            gestureBounds,
            allowedRegion,
            metrics.widthPixels,
            metrics.heightPixels,
        ) ?: run {
            lastResult = "找不到目標內的安全點擊位置"
            return
        }
        val safeX = point.x
        val safeY = point.y
        if (!settings.canClick(safeX, safeY, metrics.widthPixels, metrics.heightPixels)) {
            lastResult = "已拒絕區域外點擊：(${safeX.toInt()}, ${safeY.toInt()})"
            return
        }
        if (!clickPending.compareAndSet(false, true)) return
        if (!actionState.beginClickDelay()) {
            clickPending.set(false)
            return
        }
        val actionToken = if (actionSession != null) {
            actionSession.takeIf(sessionGate::isCurrent)?.let { sessionGate.token(zoneId, imageTargetId) }
        } else {
            currentSession(settings)
            sessionGate.token(zoneId, imageTargetId)
        }
        if (actionToken == null) {
            clickPending.set(false)
            actionState.cancelClick()
            return
        }
        pendingClickZoneId = zoneId
        zoneStatuses[zoneId] = "延遲"
        syncRecognitionRegionOverlay(
            regions = overlayRegions(settings),
            visible = isTargetForeground(settings),
        )
        val timing = randomGestureTiming(settings.randomClickMaxMs)
        val detectedPackage = foregroundPackage
        lastResult = "點擊中"
        mainHandler.postDelayed({
            val current = AutomationConfig.read(this)
            if (
                !isActionCurrent(actionToken) ||
                !current.enabled || foregroundPackage != detectedPackage ||
                !isTargetForeground(current) ||
                !current.canClick(safeX, safeY, metrics.widthPixels, metrics.heightPixels)
            ) {
                clickPending.set(false)
                actionState.cancelClick()
                pendingClickZoneId = null
                zoneStatuses[zoneId] = "已取消"
                lastResult = "已取消過期的隨機點擊"
                syncRecognitionRegionOverlay(
                    regions = overlayRegions(current),
                    visible = isTargetForeground(current),
                )
                return@postDelayed
            }
            if (imageVerification == null) {
                dispatchTapGesture(point, current, zoneId, description, timing, imageTargetId, actionToken)
            } else {
                zoneStatuses[zoneId] = "重驗"
                syncRecognitionRegionOverlay(
                    regions = overlayRegions(current),
                    visible = isTargetForeground(current),
                )
                verifyImageBeforeClick(imageVerification) { verified ->
                    val latest = AutomationConfig.read(this)
                    val latestMetrics = gestureDisplayMetrics()
                    val verifiedGestureBounds = verified?.let {
                        mapClickBoundsToScreen(
                            it.bounds,
                            it.captureWidth,
                            it.captureHeight,
                            latestMetrics.widthPixels,
                            latestMetrics.heightPixels,
                        )
                    }
                    val verifiedPoint = verifiedGestureBounds?.let {
                        randomClickPoint(it, allowedRegion, latestMetrics.widthPixels, latestMetrics.heightPixels)
                    }
                    if (
                        verifiedPoint == null || !isActionCurrent(actionToken) || !latest.enabled || foregroundPackage != detectedPackage ||
                        !isTargetForeground(latest) ||
                        !latest.canClick(verifiedPoint.x, verifiedPoint.y, latestMetrics.widthPixels, latestMetrics.heightPixels)
                    ) {
                        cancelPendingClick(zoneId, "重驗時目標已移動或消失")
                    } else {
                        dispatchTapGesture(verifiedPoint, latest, zoneId, description, timing, imageTargetId, actionToken)
                    }
                }
            }
        }, timing.startDelayMs)
    }

    private fun dispatchTapGesture(
        point: ClickPoint,
        settings: AutomationSettings,
        zoneId: String,
        description: String,
        timing: GestureTiming,
        imageTargetId: String?,
        actionToken: ActionToken,
    ) {
        if (!isActionCurrent(actionToken) || !actionState.beginClicking()) {
            cancelPendingClick(zoneId, "動作狀態已改變")
            return
        }
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, timing.pressDurationMs))
            .build()
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (!finishGesture(actionToken)) return
                    clickPending.set(false)
                    pendingClickZoneId = null
                    zoneStatuses[zoneId] = "已點擊"
                    imageTargetId?.let { id ->
                        targetQuality[id] = ((targetQuality[id] ?: 0.5f) + 0.08f).coerceAtMost(1f)
                    }
                    lastClickAt = SystemClock.elapsedRealtime()
                    adaptiveScan.markGesture(lastClickAt)
                    lastResult = "已點擊：$description"
                    if (settings.numberMonitorEnabled && settings.numberTriggerZoneId == zoneId) {
                        // This is the highest-priority follow-up. It is armed before
                        // overlays, markers or the next recognition scan can run.
                        scheduleConfiguredTriggerSwipe(
                            zoneId,
                            description.substringBefore(" · "),
                            settings,
                            actionToken.session,
                        )
                    } else {
                        actionState.gestureFinished()
                    }
                    if (settings.showClickMarker) showClickMarker(point.x, point.y)
                    showOrHideNotification(AutomationConfig.read(this@ScreenAutomationService))
                    syncRecognitionRegionOverlay(
                        regions = overlayRegions(settings),
                        visible = isTargetForeground(settings),
                    )
                    mainHandler.removeCallbacks(scanRunnable)
                    mainHandler.post(scanRunnable)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (!finishGesture(actionToken)) return
                    cancelPendingClick(zoneId, "點擊被系統取消")
                }
            },
            mainHandler,
        )
        if (!accepted) {
            cancelPendingClick(zoneId, "系統拒絕點擊手勢", "被拒絕")
        } else {
            armGestureWatchdog(actionToken) {
                if (actionState.cancelClick()) {
                    clickPending.set(false)
                    pendingClickZoneId = null
                    zoneStatuses[zoneId] = "逾時"
                    lastResult = "點擊回呼逾時，已解除等待"
                }
            }
        }
    }

    private fun cancelPendingClick(zoneId: String, reason: String, status: String = "已取消") {
        clearGestureWatchdog()
        clickPending.set(false)
        actionState.cancelClick()
        pendingClickZoneId = null
        zoneStatuses[zoneId] = status
        lastResult = reason
        val settings = AutomationConfig.read(this)
        syncRecognitionRegionOverlay(
            regions = overlayRegions(settings),
            visible = isTargetForeground(settings),
        )
    }

    private fun armGestureWatchdog(token: ActionToken, timeoutMs: Long = 4_000L, onTimeout: () -> Unit) {
        clearGestureWatchdog()
        pendingGestureToken = token
        gestureWatchdog = Runnable {
            if (pendingGestureToken == token) {
                pendingGestureToken = null
                gestureWatchdog = null
                onTimeout()
            }
        }.also { mainHandler.postDelayed(it, timeoutMs) }
    }

    private fun finishGesture(token: ActionToken): Boolean {
        if (pendingGestureToken != token) return false
        clearGestureWatchdog()
        return isActionCurrent(token)
    }

    private fun clearGestureWatchdog() {
        gestureWatchdog?.let(mainHandler::removeCallbacks)
        gestureWatchdog = null
        pendingGestureToken = null
    }

    private fun isTargetForeground(settings: AutomationSettings): Boolean =
        settings.targetPackage.isNotBlank() &&
            settings.targetPackage != packageName &&
            foregroundPackage == settings.targetPackage

    private fun verifyImageBeforeClick(
        request: ImageVerification,
        callback: (VerifiedImageClick?) -> Unit,
    ) {
        fun analyze(bitmap: Bitmap?) {
            if (destroyed.get()) {
                bitmap?.recycle()
                return
            }
            if (bitmap == null) {
                mainHandler.post { callback(null) }
                return
            }
            try {
                visionExecutor.execute {
                val verified = try {
                    val prepared = cropRecognitionRegionCopy(bitmap, request.region)
                    try {
                        val reference = if (request.visualMode == TargetMode.IMAGE) loadReference(request.referenceUri) else null
                        val match = when (request.visualMode) {
                            TargetMode.CIRCLE_X -> CircleXDetector.find(
                                prepared.bitmap,
                                request.minDiameterRatio,
                                request.maxDiameterRatio,
                            )
                            TargetMode.BACK_ARROW -> BackArrowDetector.find(prepared.bitmap)
                            TargetMode.IMAGE -> reference?.let { TemplateMatcher.findBest(prepared.bitmap, it) }
                            TargetMode.TEXT -> null
                        }
                        val absoluteX = (match?.clickCenterX ?: 0f) + prepared.offsetX
                        val absoluteY = (match?.clickCenterY ?: 0f) + prepared.offsetY
                        val confirmedSameObject = match?.let {
                            isSpatiallyConsistentVisualMatch(
                                request.expectedX,
                                request.expectedY,
                                request.expectedSize,
                                absoluteX,
                                absoluteY,
                                maxOf(it.width, it.height),
                            )
                        } ?: false
                        if (
                            match == null || match.score < request.threshold ||
                            !confirmedSameObject
                        ) {
                            null
                        } else {
                            Log.i(TAG, "第二幀確認通過：${request.visualMode} ${(match.score * 100).roundToInt()}%")
                            val safeHalfRatio = visualSafeHalfRatio(request.visualMode)
                            VerifiedImageClick(
                                visualSafeClickBounds(
                                    absoluteX,
                                    absoluteY,
                                    match.width,
                                    match.height,
                                    safeHalfRatio,
                                ),
                                bitmap.width,
                                bitmap.height,
                            )
                        }
                    } finally {
                        if (prepared.bitmap !== bitmap) prepared.bitmap.recycle()
                    }
                } catch (_: Throwable) {
                    null
                } finally {
                    bitmap.recycle()
                }
                    if (!destroyed.get()) mainHandler.post { callback(verified) }
                }
            } catch (_: RejectedExecutionException) {
                bitmap.recycle()
            }
        }

        if (Build.VERSION.SDK_INT < 30) {
            MediaProjectionCaptureService.requestFrame(::analyze)
        } else {
            captureVerificationWithAccessibility(::analyze)
        }
    }

    @android.annotation.TargetApi(30)
    private fun captureVerificationWithAccessibility(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    }
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) = callback(null)
            },
        )
    }

    private fun cropRecognitionRegionCopy(
        source: Bitmap,
        region: RecognitionRegion,
    ): PreparedBitmap {
        val normalized = region.normalized()
        val left = (normalized.left * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (normalized.top * source.height).toInt().coerceIn(0, source.height - 1)
        val right = kotlin.math.ceil(normalized.right * source.width).toInt()
            .coerceIn(left + 1, source.width)
        val bottom = kotlin.math.ceil(normalized.bottom * source.height).toInt()
            .coerceIn(top + 1, source.height)
        if (left == 0 && top == 0 && right == source.width && bottom == source.height) {
            return PreparedBitmap(source, 0, 0)
        }
        val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        return PreparedBitmap(cropped, left, top)
    }

    private fun bitmapFingerprint(bitmap: Bitmap): Long {
        if (bitmap.width <= 0 || bitmap.height <= 0) return 0L
        var hash = 1_125_899_906_842_597L
        val columns = 24
        val rows = 24
        for (row in 1..rows) {
            val y = (row * bitmap.height / (rows + 1)).coerceIn(0, bitmap.height - 1)
            for (column in 1..columns) {
                val x = (column * bitmap.width / (columns + 1)).coerceIn(0, bitmap.width - 1)
                val color = bitmap.getPixel(x, y)
                val luminance = ((Color.red(color) * 77 + Color.green(color) * 150 + Color.blue(color) * 29) shr 8) / 12
                hash = (hash xor luminance.toLong()) * 1_099_511_628_211L
            }
        }
        return hash
    }

    private fun numberMonitorFingerprint(bitmap: Bitmap, region: RecognitionRegion): Long {
        val normalized = region.normalized()
        return numberRegionFingerprint(
            width = bitmap.width,
            height = bitmap.height,
            left = normalized.left,
            top = normalized.top,
            right = normalized.right,
            bottom = normalized.bottom,
            pixelAt = bitmap::getPixel,
        )
    }

    private fun visualTargetSignature(zone: RecognitionZone): Int = zone.targets
        .filter { it.mode.isVisual() }
        .fold(1) { value, target -> 31 * value + 31 * target.id.hashCode() + target.value.hashCode() }

    private fun showClickMarker(x: Float, y: Float) {
        removeClickMarker()
        val marker = ClickMarkerView(this, x, y)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        val windowManager = getSystemService(WindowManager::class.java)
        runCatching {
            windowManager.addView(marker, params)
            clickMarkerView = marker
            mainHandler.postDelayed({ removeClickMarker() }, 700L)
        }
    }

    private fun removeClickMarker() {
        val marker = clickMarkerView ?: return
        clickMarkerView = null
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(marker) }
    }

    private fun syncRecognitionRegionOverlay(
        regions: List<OverlayRegion>,
        visible: Boolean,
    ) {
        if (!visible || regions.isEmpty()) {
            removeRecognitionRegionOverlay()
            return
        }
        val screenMetrics = gestureDisplayMetrics()
        recognitionRegionOverlayView?.let {
            it.setScreenSize(screenMetrics.widthPixels, screenMetrics.heightPixels)
            it.setRegions(regions)
            return
        }

        val overlay = RecognitionRegionOverlayView(
            context = this,
            screenWidth = screenMetrics.widthPixels,
            screenHeight = screenMetrics.heightPixels,
        ).apply { setRegions(regions) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching {
            getSystemService(WindowManager::class.java).addView(overlay, params)
            recognitionRegionOverlayView = overlay
        }
    }

    private fun removeRecognitionRegionOverlay() {
        val overlay = recognitionRegionOverlayView ?: return
        recognitionRegionOverlayView = null
        runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(overlay) }
    }

    private fun overlayRegions(settings: AutomationSettings): List<OverlayRegion> = buildList {
        settings.zones.filter { it.targets.isNotEmpty() }.forEach { zone ->
            val hasImages = zone.targets.any { it.mode.isVisual() }
            add(
                OverlayRegion(
                    region = zone.region,
                    name = zone.name,
                    similarity = if (hasImages) zoneSimilarities[zone.id] else null,
                    showSimilarity = hasImages,
                    statusText = null,
                    phaseText = if (hasImages) zoneStatuses[zone.id]?.ifBlank { null } else null,
                ),
            )
        }
        if (settings.numberMonitorEnabled) {
            add(
                OverlayRegion(
                    settings.numberMonitorRegion,
                    "",
                    null,
                    false,
                    detectedNumberOverlayText,
                    null,
                ),
            )
        }
    }

    private fun showOrHideNotification(settings: AutomationSettings) {
        val manager = notificationManager()
        if (Build.VERSION.SDK_INT < 30) {
            manager.cancel(NOTIFICATION_ID)
            lastNotificationText = null
            return
        }
        if (!settings.enabled) {
            manager.cancel(NOTIFICATION_ID)
            lastNotificationText = null
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "自動點擊狀態",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "顯示 CSC 是否正在辨識螢幕" },
        )

        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, AutomationActionReceiver::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val target = "${settings.zones.size} 個區域 · ${settings.targetCount} 個項目"
        val notificationText = "$target · $lastResult"
        val now = SystemClock.elapsedRealtime()
        if (notificationText == lastNotificationText && now - lastNotificationAt < NOTIFICATION_REFRESH_MS) return
        val notification = android.app.Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(Icon.createWithResource(this, R.drawable.ic_app))
            .setContentTitle("CSC 正在辨識")
            .setContentText(notificationText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.app.Notification.Action.Builder(
                    null,
                    "立即停止",
                    stopIntent,
                ).build(),
            )
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
        lastNotificationText = notificationText
        lastNotificationAt = now
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(NotificationManager::class.java)

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), "")

    private fun TargetMode.isVisual(): Boolean =
        this == TargetMode.IMAGE || this == TargetMode.CIRCLE_X || this == TargetMode.BACK_ARROW

    private fun RecognitionZone.primaryVisualMode(): TargetMode? = when {
        targets.any { it.mode == TargetMode.CIRCLE_X } -> TargetMode.CIRCLE_X
        targets.any { it.mode == TargetMode.BACK_ARROW } -> TargetMode.BACK_ARROW
        targets.any { it.mode == TargetMode.IMAGE } -> TargetMode.IMAGE
        else -> null
    }

    private fun visualSafeHalfRatio(mode: TargetMode): Float = when (mode) {
        TargetMode.CIRCLE_X -> CIRCLE_X_SAFE_HALF_RATIO
        TargetMode.BACK_ARROW -> BACK_ARROW_SAFE_HALF_RATIO
        else -> IMAGE_SAFE_HALF_RATIO
    }

    private fun visualHitLabel(hit: ImageHit): String = when (hit.target.mode) {
        TargetMode.CIRCLE_X -> "${hit.zone.name} · 圓圈＋X ${(hit.match.score * 100).toInt()}%"
        TargetMode.BACK_ARROW -> "${hit.zone.name} · 返回箭頭 ${(hit.match.score * 100).toInt()}%"
        else -> "${hit.zone.name} · 圖片「${hit.target.label}」 ${(hit.match.score * 100).toInt()}%"
    }

    private fun Rect.offsetCopy(offsetX: Int, offsetY: Int): Rect = Rect(
        left + offsetX,
        top + offsetY,
        right + offsetX,
        bottom + offsetY,
    )

    private fun containsCjk(value: String): Boolean = value.any { char ->
        char.code in 0x3400..0x9fff || char.code in 0xf900..0xfaff
    }

    private data class PreparedBitmap(
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
    )

    /** One structured log line per processed frame; filter logcat with CSC_FRAME_PROFILE. */
    private class FrameProfile {
        private val startedAt = SystemClock.elapsedRealtime()
        private var mainCallbackPostedAt = 0L
        private var visionQueuedAt = 0L
        private var logged = false

        var session: AutomationSession? = null
        var captureMs = 0L
        var bitmapConversionMs = 0L
        var fingerprintMs = 0L
        var circleXQueueWaitMs = 0L
        var circleXMs = 0L
        var ocrMs = 0L
        var captureCallbackWaitMs = 0L
        var mainCallbackWaitMs = 0L
        var referenceLoadFailures = 0
        val visualZoneMs = linkedMapOf<String, Long>()
        val backArrowMs = linkedMapOf<String, Long>()
        val templateMatcherMs = linkedMapOf<String, Long>()

        fun elapsedMs(): Long = SystemClock.elapsedRealtime() - startedAt

        fun markVisionQueued() {
            visionQueuedAt = SystemClock.elapsedRealtime()
        }

        fun elapsedSinceVisionQueuedMs(): Long = if (visionQueuedAt == 0L) 0L else {
            SystemClock.elapsedRealtime() - visionQueuedAt
        }

        fun markMainCallbackPosted() {
            mainCallbackPostedAt = SystemClock.elapsedRealtime()
        }

        fun elapsedSincePostMs(): Long = if (mainCallbackPostedAt == 0L) 0L else {
            SystemClock.elapsedRealtime() - mainCallbackPostedAt
        }

        fun log() {
            if (logged) return
            logged = true
            val zones = visualZoneMs.entries.joinToString(",") { "${it.key}:${it.value}" }
            val arrows = backArrowMs.entries.joinToString(",") { "${it.key}:${it.value}" }
            val templates = templateMatcherMs.entries.joinToString(",") { "${it.key}:${it.value}" }
            Log.i(
                "CSC_FRAME_PROFILE",
                "total=${elapsedMs()}ms capture=${captureMs}ms bitmap=${bitmapConversionMs}ms " +
                    "fingerprint=${fingerprintMs}ms circleXQueue=${circleXQueueWaitMs}ms " +
                    "circleX=${circleXMs}ms ocr=${ocrMs}ms visualZones=[$zones] " +
                    "backArrow=[$arrows] templateMatcher=[$templates] " +
                    "referenceLoadFailures=$referenceLoadFailures captureCallbackWait=${captureCallbackWaitMs}ms " +
                    "mainCallbackWait=${mainCallbackWaitMs}ms",
            )
        }
    }

    private data class TextHit(
        val zone: RecognitionZone,
        val target: RecognitionTarget,
        val bounds: Rect,
    )

    private data class NodeHit(
        val zone: RecognitionZone,
        val target: RecognitionTarget,
        val bounds: ClickBounds,
    )

    private data class ImageHit(
        val zone: RecognitionZone,
        val target: RecognitionTarget,
        val match: VisualMatch,
        val offsetX: Int,
        val offsetY: Int,
    )

    private data class ImageVerification(
        val referenceUri: String,
        val region: RecognitionRegion,
        val threshold: Float,
        val expectedX: Float,
        val expectedY: Float,
        val expectedSize: Float,
        val visualMode: TargetMode,
        val minDiameterRatio: Float,
        val maxDiameterRatio: Float,
    )

    private data class VerifiedImageClick(
        val bounds: ClickBounds,
        val captureWidth: Int,
        val captureHeight: Int,
    )

    private data class ZoneRecognitionCache(
        val fingerprint: Long,
        val targetSignature: Int,
        val targetId: String,
        val match: VisualMatch?,
        val createdAt: Long,
        val calibrationObserved: Boolean = false,
    )

    private data class NumberTrackerKey(
        val foregroundPackage: String?,
        val targetPackage: String,
        val enabled: Boolean,
        val monitorEnabled: Boolean,
        val region: RecognitionRegion,
        val threshold: Float,
        val upperLimit: Float,
        val colorFilterEnabled: Boolean,
        val colorHex: String,
        val colorTolerance: Int,
        val absenceTimeoutMs: Long,
        val triggerZoneId: String?,
        val triggerDelayMs: Long,
    )

    private data class OverlayRegion(
        val region: RecognitionRegion,
        val name: String,
        val similarity: Float?,
        val showSimilarity: Boolean,
        val statusText: String?,
        val phaseText: String?,
    )

    private class ClickMarkerView(
        context: Context,
        private val markerX: Float,
        private val markerY: Float,
    ) : View(context) {
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(103, 80, 164)
            style = Paint.Style.FILL
        }
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = 5f * resources.displayMetrics.density
            canvas.drawCircle(markerX, markerY, radius, dotPaint)
            canvas.drawCircle(markerX, markerY, radius, outlinePaint)
        }
    }

    private class RecognitionRegionOverlayView(
        context: Context,
        private var screenWidth: Int,
        private var screenHeight: Int,
    ) : View(context) {
        private val density = resources.displayMetrics.density
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
        }
        private val frameColors = intArrayOf(
            Color.argb(225, 103, 80, 164),
            Color.argb(225, 0, 121, 107),
            Color.argb(225, 0, 94, 184),
            Color.argb(225, 198, 80, 0),
        )
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 12f * density
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private var regions = emptyList<OverlayRegion>()

        fun setScreenSize(width: Int, height: Int) {
            if (screenWidth == width && screenHeight == height) return
            screenWidth = width
            screenHeight = height
            invalidate()
        }

        fun setRegions(values: List<OverlayRegion>) {
            val normalized = values.map { it.copy(region = it.region.normalized()) }
            if (normalized == regions) return
            regions = normalized
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = framePaint.strokeWidth / 2f
            val location = IntArray(2)
            getLocationOnScreen(location)
            regions.forEachIndexed { index, item ->
                val region = mapRecognitionRegionToOverlay(
                    region = item.region,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    overlayLeft = location[0],
                    overlayTop = location[1],
                )
                framePaint.color = frameColors[index % frameColors.size]
                val left = region.left.coerceAtLeast(inset)
                val top = region.top.coerceAtLeast(inset)
                val right = region.right.coerceAtMost(width - inset)
                val bottom = region.bottom.coerceAtMost(height - inset)
                canvas.drawRect(left, top, right, bottom, framePaint)
                if (item.showSimilarity || item.statusText != null) {
                    drawSimilarityLabel(canvas, item, index, left, top, right, bottom)
                }
            }
        }

        private fun drawSimilarityLabel(
            canvas: Canvas,
            item: OverlayRegion,
            index: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ) {
            val detail = item.statusText ?: buildString {
                append(item.similarity?.let { "${(it * 100).roundToInt()}%" } ?: "--%")
                item.phaseText?.let { append(' ').append(it) }
            }
            val label = if (item.name.isBlank()) detail else "${item.name.take(10)} $detail"
            val paddingX = 5f * density
            val labelHeight = 20f * density
            val desiredWidth = labelPaint.measureText(label) + paddingX * 2f
            val labelWidth = desiredWidth.coerceAtMost((width - paddingX * 2f).coerceAtLeast(1f))
            val gap = 2f * density
            val canPlaceAbove = top >= labelHeight + gap
            val canPlaceBelow = bottom + labelHeight + gap <= height
            val canPlaceRight = right + labelWidth + gap <= width
            val canPlaceLeft = left - labelWidth - gap >= 0f
            val (rawLeft, rawTop) = when {
                canPlaceAbove -> left to (top - labelHeight - gap)
                canPlaceBelow -> left to (bottom + gap)
                canPlaceRight -> (right + gap) to top
                canPlaceLeft -> (left - labelWidth - gap) to top
                else -> left to top
            }
            val labelLeft = rawLeft.coerceIn(0f, (width - labelWidth).coerceAtLeast(0f))
            val labelTop = rawTop.coerceIn(0f, (height - labelHeight).coerceAtLeast(0f))
            labelBackgroundPaint.color = frameColors[index % frameColors.size]
            canvas.drawRect(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight, labelBackgroundPaint)
            canvas.save()
            canvas.clipRect(labelLeft, labelTop, labelLeft + labelWidth, labelTop + labelHeight)
            canvas.drawText(label, labelLeft + paddingX, labelTop + 14.5f * density, labelPaint)
            canvas.restore()
        }
    }

    companion object {
        const val ACTION_STOP = "com.example.csc.action.STOP"
        private const val NOTIFICATION_CHANNEL = "automation_status"
        private const val NOTIFICATION_ID = 4102
        private const val MAX_NODES = 2_000
        private const val ASSET_URI_PREFIX = "asset://"
        private const val FAST_SCAN_INTERVAL_MS = 500L
        private const val NOTIFICATION_REFRESH_MS = 1_000L
        private const val UNAVAILABLE_REFERENCE_RETRY_MS = 30_000L
        private const val POST_SWIPE_SETTLE_MS = 900L
        private const val NUMBER_CONFIRMATION_SCAN_DELAY_MS = 250L
        private const val HIGH_CONFIDENCE_MARGIN = 0.10f
        private const val CIRCLE_X_SAFE_HALF_RATIO = 0.06f
        private const val BACK_ARROW_SAFE_HALF_RATIO = 0.08f
        private const val IMAGE_SAFE_HALF_RATIO = 0.10f
        private const val TAG = "ScreenAutomation"

        @Volatile
        var connected: Boolean = false
            private set

        @Volatile
        private var instance: ScreenAutomationService? = null

        fun requestImmediateRefresh() {
            instance?.mainHandler?.post {
                instance?.mainHandler?.removeCallbacks(instance?.scanRunnable ?: return@post)
                instance?.mainHandler?.post(instance?.scanRunnable ?: return@post)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
