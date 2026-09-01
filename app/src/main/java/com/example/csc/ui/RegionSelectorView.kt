package com.example.csc.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.csc.automation.RecognitionRegion
import kotlin.math.hypot
import kotlin.math.min

class RegionSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 242, 249) }
    private val screenBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(121, 116, 126)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 73, 69, 79)
        strokeWidth = density
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(125, 29, 27, 32) }
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(103, 80, 164)
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(103, 80, 164) }
    private val screenRect = RectF()
    private val selectionRect = RectF()
    private var region = RecognitionRegion.FULL
    private var dragMode = DragMode.NONE
    private var downRegion = RecognitionRegion.FULL

    var onRegionChanged: ((RecognitionRegion) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        updateContentDescription()
    }

    fun setRegion(value: RecognitionRegion, notify: Boolean = false) {
        region = value.normalized()
        updateContentDescription()
        invalidate()
        if (notify) onRegionChanged?.invoke(region)
    }

    fun getRegion(): RecognitionRegion = region

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (300f * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableHeight = height - 20f * density
        val previewHeight = min(availableHeight, 270f * density)
        val previewWidth = min(width - 32f * density, previewHeight * 0.52f)
        val left = (width - previewWidth) / 2f
        val top = (height - previewHeight) / 2f
        screenRect.set(left, top, left + previewWidth, top + previewHeight)

        canvas.drawRoundRect(screenRect, 18f * density, 18f * density, screenPaint)
        for (step in 1..2) {
            val x = screenRect.left + screenRect.width() * step / 3f
            val y = screenRect.top + screenRect.height() * step / 3f
            canvas.drawLine(x, screenRect.top, x, screenRect.bottom, gridPaint)
            canvas.drawLine(screenRect.left, y, screenRect.right, y, gridPaint)
        }

        selectionRect.set(
            screenRect.left + screenRect.width() * region.left,
            screenRect.top + screenRect.height() * region.top,
            screenRect.left + screenRect.width() * region.right,
            screenRect.top + screenRect.height() * region.bottom,
        )
        canvas.save()
        canvas.clipRect(screenRect)
        canvas.clipOutRect(selectionRect)
        canvas.drawRect(screenRect, shadePaint)
        canvas.restore()

        canvas.drawRect(selectionRect, selectionPaint)
        if (isEnabled) {
            val handleRadius = 8f * density
            canvas.drawCircle(selectionRect.left, selectionRect.top, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.left, selectionRect.bottom, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handlePaint)
        }
        canvas.drawRoundRect(screenRect, 18f * density, 18f * density, screenBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || screenRect.isEmpty) return false
        val normalizedX = ((event.x - screenRect.left) / screenRect.width()).coerceIn(0f, 1f)
        val normalizedY = ((event.y - screenRect.top) / screenRect.height()).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = chooseDragMode(event.x, event.y)
                if (dragMode == DragMode.NONE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                downRegion = region
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateDraggedRegion(normalizedX, normalizedY)
                return true
            }
            MotionEvent.ACTION_UP -> {
                updateDraggedRegion(normalizedX, normalizedY)
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMode = DragMode.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun chooseDragMode(x: Float, y: Float): DragMode {
        val hitRadius = 22f * density
        val corners = listOf(
            DragMode.TOP_LEFT to Pair(selectionRect.left, selectionRect.top),
            DragMode.TOP_RIGHT to Pair(selectionRect.right, selectionRect.top),
            DragMode.BOTTOM_LEFT to Pair(selectionRect.left, selectionRect.bottom),
            DragMode.BOTTOM_RIGHT to Pair(selectionRect.right, selectionRect.bottom),
        )
        corners.minByOrNull { (_, point) -> hypot(x - point.first, y - point.second) }
            ?.let { (mode, point) ->
                if (hypot(x - point.first, y - point.second) <= hitRadius) return mode
            }
        return DragMode.NONE
    }

    private fun updateDraggedRegion(x: Float, y: Float) {
        val minSize = RecognitionRegion.MIN_SIZE
        val updated = when (dragMode) {
            DragMode.TOP_LEFT -> downRegion.copy(
                left = x.coerceAtMost(downRegion.right - minSize),
                top = y.coerceAtMost(downRegion.bottom - minSize),
            )
            DragMode.TOP_RIGHT -> downRegion.copy(
                right = x.coerceAtLeast(downRegion.left + minSize),
                top = y.coerceAtMost(downRegion.bottom - minSize),
            )
            DragMode.BOTTOM_LEFT -> downRegion.copy(
                left = x.coerceAtMost(downRegion.right - minSize),
                bottom = y.coerceAtLeast(downRegion.top + minSize),
            )
            DragMode.BOTTOM_RIGHT -> downRegion.copy(
                right = x.coerceAtLeast(downRegion.left + minSize),
                bottom = y.coerceAtLeast(downRegion.top + minSize),
            )
            DragMode.NONE -> region
        }.normalized()
        if (updated != region) {
            region = updated
            updateContentDescription()
            invalidate()
            onRegionChanged?.invoke(region)
        }
    }

    private fun updateContentDescription() {
        contentDescription = "辨識區域：左 ${(region.left * 100).toInt()}%，上 ${(region.top * 100).toInt()}%，" +
            "右 ${(region.right * 100).toInt()}%，下 ${(region.bottom * 100).toInt()}%"
    }

    private enum class DragMode {
        NONE,
        TOP_LEFT,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_RIGHT,
    }
}
