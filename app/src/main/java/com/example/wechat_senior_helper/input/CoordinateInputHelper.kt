package com.example.wechat_senior_helper.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CoordinateInputHelper(
    private val service: AccessibilityService,
    private val screenWidth: Int,
    private val screenHeight: Int
) {
    companion object {
        private const val TAG = "CoordInput"
        private const val INDICATOR_SIZE_DP = 36
        private const val INDICATOR_DURATION_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var indicatorView: TouchIndicatorView? = null
    private var removalRunnable: Runnable? = null

    private val density: Float get() = service.resources.displayMetrics.density
    private val indicatorSizePx: Int get() = (INDICATOR_SIZE_DP * density).toInt()

    suspend fun tapByRatio(xRatio: Float, yRatio: Float) {
        val x = (screenWidth * xRatio).toInt()
        val y = (screenHeight * yRatio).toInt()
        Log.e(TAG, "[TAP] ratio=($xRatio, $yRatio) pixel=($x, $y)")
        showIndicator(x, y)
        dispatchGesture(x, y, 50L)
        scheduleRemoval()
    }

    suspend fun longPressByRatio(xRatio: Float, yRatio: Float, durationMs: Long = 600) {
        val x = (screenWidth * xRatio).toInt()
        val y = (screenHeight * yRatio).toInt()
        Log.e(TAG, "[LONG_PRESS] ratio=($xRatio, $yRatio) pixel=($x, $y) durationMs=$durationMs")
        showIndicator(x, y)
        dispatchGesture(x, y, durationMs)
        scheduleRemoval()
    }

    private suspend fun dispatchGesture(x: Int, y: Int, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            var resumed = false
            val timeoutMs = durationMs + 1000  // 手势时长 + 1s buffer
            val timeoutRunnable = Runnable {
                if (!resumed) {
                    resumed = true
                    Log.e(TAG, "[GESTURE_TIMEOUT] ($x, $y) durationMs=$durationMs, force resume")
                    if (cont.isActive) cont.resume(true)
                }
            }
            handler.postDelayed(timeoutRunnable, timeoutMs)

            try {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                val dispatched = service.dispatchGesture(gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (!resumed) {
                                resumed = true
                                Log.e(TAG, "[GESTURE_OK] ($x, $y) durationMs=$durationMs")
                                if (cont.isActive) cont.resume(true)
                            }
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            handler.removeCallbacks(timeoutRunnable)
                            if (!resumed) {
                                resumed = true
                                Log.e(TAG, "[GESTURE_CANCELLED] ($x, $y) durationMs=$durationMs")
                                if (cont.isActive) cont.resume(false)
                            }
                        }
                    },
                    null
                )

                if (!dispatched) {
                    handler.removeCallbacks(timeoutRunnable)
                    if (!resumed) {
                        resumed = true
                        Log.e(TAG, "[GESTURE_DISPATCH_FAILED] ($x, $y)")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } catch (t: Throwable) {
                handler.removeCallbacks(timeoutRunnable)
                if (!resumed) {
                    resumed = true
                    Log.e(TAG, "[GESTURE_EXCEPTION] ${t.message}", t)
                    if (cont.isActive) cont.resume(false)
                }
            }
        }

    // ===================== 点击动画指示器（圆圈+十字准心） =====================
    private fun showIndicator(cx: Int, cy: Int) {
        // 取消旧定时器，防止误删新指示器
        cancelRemoval()
        removeIndicator()

        val size = indicatorSizePx
        val view = TouchIndicatorView(service, size)

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = cx - size / 2
            this.y = cy - size / 2
        }

        try {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm.addView(view, params)
            indicatorView = view
            Log.e(TAG, "[INDICATOR] center=($cx, $cy) size=${size}px")
        } catch (e: Throwable) {
            Log.e(TAG, "[INDICATOR_FAIL] ${e.message}", e)
        }
    }

    private fun scheduleRemoval() {
        cancelRemoval()
        removalRunnable = Runnable { removeIndicator() }
        handler.postDelayed(removalRunnable!!, INDICATOR_DURATION_MS)
    }

    private fun cancelRemoval() {
        removalRunnable?.let { handler.removeCallbacks(it) }
        removalRunnable = null
    }

    private fun removeIndicator() {
        indicatorView?.let { view ->
            try {
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Throwable) { }
            indicatorView = null
        }
    }

    /** 自定义 View：半透明圆 + 十字准心，圆心精确对准点击坐标 */
    private class TouchIndicatorView(context: Context, sizePx: Int) : android.view.View(context) {
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#8044AAFF")
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF64B5F6")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FFFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val cx = sizePx / 2f
        private val cy = sizePx / 2f
        private val radius = sizePx / 2f - 4f
        private val crossLen = radius * 0.45f

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // 填充圆
            canvas.drawCircle(cx, cy, radius, circlePaint)
            // 描边
            canvas.drawCircle(cx, cy, radius, strokePaint)
            // 十字准心（白色，确认圆心就是点击位置）
            canvas.drawLine(cx - crossLen, cy, cx + crossLen, cy, crossPaint)
            canvas.drawLine(cx, cy - crossLen, cx, cy + crossLen, crossPaint)
        }
    }
}
