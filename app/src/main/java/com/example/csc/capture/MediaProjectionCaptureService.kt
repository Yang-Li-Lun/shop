package com.example.csc.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.csc.MainActivity
import com.example.csc.R
import com.example.csc.automation.AutomationActionReceiver
import com.example.csc.automation.ScreenAutomationService

/** Android 10 fallback for AccessibilityService.takeScreenshot(), which starts at API 30. */
class MediaProjectionCaptureService : Service() {
    private val frameThread = HandlerThread("csc-capture")
    private lateinit var frameHandler: Handler
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val callbackLock = Any()
    private var pendingFrameCallback: ((Bitmap?) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        running = false
        frameThread.start()
        frameHandler = Handler(frameThread.looper)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startProjection(resultCode, resultData)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(callbackLock) {
            pendingFrameCallback?.invoke(null)
            pendingFrameCallback = null
        }
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        frameThread.quitSafely()
        projection = null
        virtualDisplay = null
        imageReader = null
        running = false
        instance = null
        super.onDestroy()
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()

        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val manager = getSystemService(MediaProjectionManager::class.java)
        val mediaProjection = manager.getMediaProjection(resultCode, resultData) ?: run {
            stopSelf()
            return
        }
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, frameHandler)
        projection = mediaProjection
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ source -> onImageAvailable(source) }, frameHandler)
        }
        val display = projection?.createVirtualDisplay(
            "CSCCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            frameHandler,
        )
        if (display == null) {
            stopSelf()
            return
        }
        virtualDisplay = display
        // Publish readiness only after an ImageReader-backed virtual display exists.
        running = true
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val callback = synchronized(callbackLock) {
            pendingFrameCallback.also { pendingFrameCallback = null }
        }
        if (callback == null) {
            image.close()
            return
        }
        val bitmap = runCatching { imageToBitmap(image) }.getOrNull()
        image.close()
        callback(bitmap)
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL,
                "螢幕擷取狀態",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Android 10 螢幕辨識所需的持續擷取狀態" },
        )
        val openIntent = PendingIntent.getActivity(
            this,
            31,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            32,
            Intent(this, AutomationActionReceiver::class.java).setAction(ScreenAutomationService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_app)
            .setContentTitle("CSC 正在擷取螢幕")
            .setContentText("僅在裝置上辨識；點此返回設定")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "立即停止", stopIntent).build())
            .build()
    }

    private fun requestFrameInternal(callback: (Bitmap?) -> Unit) {
        synchronized(callbackLock) {
            if (pendingFrameCallback != null) {
                callback(null)
            } else {
                pendingFrameCallback = callback
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 4103
        private const val NOTIFICATION_CHANNEL = "screen_capture_status"
        private const val ACTION_START = "com.example.csc.capture.START"
        private const val ACTION_STOP = "com.example.csc.capture.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        private var instance: MediaProjectionCaptureService? = null

        @Volatile
        var running: Boolean = false
            private set

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, MediaProjectionCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)

        fun requestFrame(callback: (Bitmap?) -> Unit) {
            val service = instance
            if (service == null) callback(null) else service.requestFrameInternal(callback)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionCaptureService::class.java))
        }
    }
}
