package com.example.csc

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.example.csc.automation.AutomationConfig
import com.example.csc.automation.AutomationSettings
import com.example.csc.automation.ScreenAutomationService
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit

/** Device-only synthetic OCR checks: no target-app gestures or preference writes. */
class NumberOcrDeviceCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val service = ScreenAutomationService()
            val settings = AutomationConfig.read(targetContext)
            val method = ScreenAutomationService::class.java.getDeclaredMethod("prepareNumberOcr", Bitmap::class.java, AutomationSettings::class.java)
            method.isAccessible = true
            val rows = mutableListOf<String>()
            val colorMethod = ScreenAutomationService::class.java.getDeclaredMethod("assessNumberBoundsColor", Bitmap::class.java, android.graphics.Rect::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            colorMethod.isAccessible = true
            for (fontSize in listOf(16f, 32f)) for ((label, ink, expected) in listOf(
                Triple("0.2", Color.parseColor(settings.numberColorHex), "0.2"),
                Triple("1.2", Color.parseColor(settings.numberColorHex), "1.2"),
                Triple("0.3", Color.parseColor(settings.numberColorHex), "0.3"),
                Triple("12", Color.parseColor(settings.numberColorHex), "12"),
                Triple("8", Color.WHITE, ""),
                Triple("9", Color.RED, ""),
                Triple("8", Color.WHITE, "background")
            )) {
                val source = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
                var prepared: Bitmap? = null
                try {
                    val canvas = Canvas(source); canvas.drawColor(Color.rgb(35,35,35))
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = fontSize; typeface = Typeface.DEFAULT_BOLD }
                    val roi = settings.numberMonitorRegion
                    val x = (roi.left+roi.right)*540 - paint.measureText(label)/2
                    val y = (roi.top+roi.bottom)*1170 + 12
                    if (expected == "background") {
                        canvas.drawRect(roi.left*1080, roi.top*2340, roi.right*1080, roi.bottom*2340,
                            Paint().apply { color = Color.parseColor(settings.numberColorHex) })
                    }
                    canvas.drawText(label,x,y,paint)
                    val image = method.invoke(service,source,settings)
                    val bitmapGetter = image.javaClass.getDeclaredMethod("getBitmap"); bitmapGetter.isAccessible = true
                    prepared = bitmapGetter.invoke(image) as Bitmap
                    val result = Tasks.await(recognizer.process(InputImage.fromBitmap(prepared,0)),20,TimeUnit.SECONDS)
                    val actual = result.text.filterNot(Char::isWhitespace).replace(',', '.')
                    fun property(name: String): Number {
                        val getter = image.javaClass.getDeclaredMethod(name); getter.isAccessible = true
                        return getter.invoke(image) as Number
                    }
                    val scale = property("getScale").toFloat()
                    val dx = property("getOffsetX").toInt(); val dy = property("getOffsetY").toInt()
                    val assessments = result.textBlocks.flatMap { it.lines }.flatMap { it.elements }
                        .filter { it.text.any(Char::isDigit) }.mapNotNull { it.boundingBox }.map { b ->
                            val bounds = android.graphics.Rect((b.left/scale+dx).toInt(), (b.top/scale+dy).toInt(), (b.right/scale+dx).toInt(), (b.bottom/scale+dy).toInt())
                            colorMethod.invoke(service, source, bounds, Color.parseColor(settings.numberColorHex), settings.numberColorTolerance).toString()
                        }
                    if (expected == "background") check(assessments.none { it == "MATCH" }) { "Background accepted: $assessments" }
                    else {
                        check(if (expected.isEmpty()) actual.none(Char::isDigit) else actual == expected) { "$label expected=$expected actual=$actual size=$fontSize" }
                        if (expected.isNotEmpty()) check(assessments.isNotEmpty() && assessments.all { it == "MATCH" }) { "$label color rejected: $assessments" }
                    }
                    rows += "$label/$ink/$fontSize => $actual $assessments PASS"
                } finally { prepared?.recycle(); source.recycle() }
            }
            output.putString("stream", rows.joinToString("\n") + "\n14 device OCR cases passed\n")
            finish(Activity.RESULT_OK, output)
        } catch (e: Throwable) {
            output.putString("stream", "FAILED: ${e.stackTraceToString()}")
            finish(Activity.RESULT_CANCELED, output)
        } finally { recognizer.close() }
    }
}
