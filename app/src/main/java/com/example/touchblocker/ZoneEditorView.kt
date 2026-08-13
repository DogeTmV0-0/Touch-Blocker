package com.example.touchblocker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Lives inside MainActivity (NOT a system overlay) so the user can see the
 * real screen behind a translucent red rectangle while they drag out the
 * region they want to become a dead zone. Reports the final rectangle via
 * onZoneDrawn once the user lifts their finger.
 */
class ZoneEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onZoneDrawn: ((Rect) -> Unit)? = null

    private val fillPaint = Paint().apply {
        color = Color.argb(90, 244, 67, 54)
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint().apply {
        color = Color.argb(220, 244, 67, 54)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var startX = 0f
    private var startY = 0f
    private var currentRect: Rect? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentRect = Rect(startX.toInt(), startY.toInt(), startX.toInt(), startY.toInt())
            }
            MotionEvent.ACTION_MOVE -> {
                currentRect = normalizedRect(startX, startY, event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val rect = normalizedRect(startX, startY, event.x, event.y)
                currentRect = rect
                invalidate()
                if (rect.width() > MIN_ZONE_SIZE_PX && rect.height() > MIN_ZONE_SIZE_PX) {
                    onZoneDrawn?.invoke(rect)
                }
            }
        }
        return true
    }

    private fun normalizedRect(x1: Float, y1: Float, x2: Float, y2: Float): Rect = Rect(
        minOf(x1, x2).toInt(),
        minOf(y1, y2).toInt(),
        maxOf(x1, x2).toInt(),
        maxOf(y1, y2).toInt()
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        currentRect?.let {
            canvas.drawRect(it, fillPaint)
            canvas.drawRect(it, strokePaint)
        }
    }

    /** Clears the in-progress rectangle, e.g. after the zone has been saved. */
    fun reset() {
        currentRect = null
        invalidate()
    }

    companion object {
        private const val MIN_ZONE_SIZE_PX = 20
    }
}
