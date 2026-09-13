package com.example.csc

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import com.example.csc.automation.DailyStatsOverlayState
import com.example.csc.automation.RecognitionRegion
import java.time.LocalDate

/** Renders the real overlay in CSC only; never writes counts or dispatches gestures. */
class DailyStatsOverlayDeviceCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        var activity: Activity? = null
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val host = activity
            val cls = Class.forName("com.example.csc.automation.ScreenAutomationService" + "$" + "RecognitionRegionOverlayView")
            val constructor = cls.getDeclaredConstructor(Context::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).apply { isAccessible = true }
            val setter = cls.getDeclaredMethod("setDailyStats", DailyStatsOverlayState::class.java, List::class.java).apply { isAccessible = true }
            var cases = 0
            for (scale in listOf(1f, 1.5f, 2f)) {
                lateinit var view: View
                runOnMainSync {
                    val configuration = android.content.res.Configuration(host.resources.configuration).apply { fontScale = scale }
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    host.windowManager.defaultDisplay.getRealMetrics(metrics)
                    view = constructor.newInstance(host.createConfigurationContext(configuration), metrics.widthPixels, metrics.heightPixels) as View
                    host.addContentView(view, ViewGroup.LayoutParams(-1, -1))
                }
                waitForIdleSync()
                var previousHash: Int? = null
                for (count in listOf(0, 9, 10, 99, 100, 999, 1000, Int.MAX_VALUE)) {
                    runOnMainSync {
                        setter.invoke(view, DailyStatsOverlayState(LocalDate.now(), count), emptyList<RecognitionRegion>())
                        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                        try {
                            view.draw(Canvas(bitmap))
                            val pixels = IntArray(bitmap.width * bitmap.height)
                            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                            check(pixels.any { it != 0 }) { "Empty panel count=$count scale=$scale" }
                            val hash = pixels.contentHashCode()
                            check(hash != previousHash) { "Count change did not redraw" }
                            previousHash = hash
                            if (scale == 1f && count == 1000) {
                                targetContext.openFileOutput("daily-overlay-device-check.png", Context.MODE_PRIVATE).use {
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                        } finally { bitmap.recycle() }
                    }
                    cases++
                }
                runOnMainSync {
                    setter.invoke(view, DailyStatsOverlayState(LocalDate.now(), 1000), listOf(RecognitionRegion.FULL))
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        check(pixels.all { it == 0 }) { "Overlapping panel was not suppressed" }
                    } finally { bitmap.recycle() }
                    (view.parent as ViewGroup).removeView(view)
                }
                cases++
            }
            output.putString("stream", "PASS: $cases real overlay render/count-change/font-scale/overlap cases; no statistics writes or gestures\n")
            finish(Activity.RESULT_OK, output)
        } catch (error: Throwable) {
            output.putString("stream", "FAILED: ${error.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, output)
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
    }
}
