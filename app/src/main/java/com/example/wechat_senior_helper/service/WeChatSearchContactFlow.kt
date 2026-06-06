package com.example.wechat_senior_helper.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.util.Log

class WeChatSearchContactFlow(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "WechatSearchFlow"
    }

    /**
     * Pure coordinate-based search flow. 不再使用无障碍节点树。
     */
    // 可调节的坐标参数（按屏幕比例）
    private val SEARCH_ICON_X_RATIO = 0.86f
    private val SEARCH_ICON_Y_RATIO = 0.055f

    // 水平居中
    private val SEARCH_INPUT_X_RATIO = 0.50f
    // 垂直约 13% 处 这是搜索输入框参数
    private val SEARCH_INPUT_Y_RATIO = 0.12f

    fun openContactChatByCoordinate(query: String): Boolean {
        Log.e(TAG, "[COORD_FLOW] start query=$query")

        val dm = service.resources.displayMetrics

        // 1) 点击右上角搜索图标
        clickSearchIconByPosition(dm)
        SystemClock.sleep(500)

        // 2) 点击搜索输入框（水平居中，垂直约 13%）
        clickSearchInputByPosition(dm)
        SystemClock.sleep(200)

        // 3) 粘贴文本
        pasteText(query)
        SystemClock.sleep(200)

        // 4) 点击键盘搜索/回车
        clickKeyboardSearchByPosition(dm)
        SystemClock.sleep(800)

        // 5) 点击第一个联系人候选（尝试并重试）
        // 5) 优先尝试通过屏幕文字识别定位联系人并点击
        if (findTextOnScreenAndTap(query)) {
            Log.e(TAG, "[COORD_FLOW_OK_OCR] tapped by OCR")
            return true
        }

        val clicked = clickFirstContactResultCandidate(dm, query)
        if (clicked) return true

        repeat(3) { i ->
            SystemClock.sleep(300)
            if (clickFirstContactResultCandidate(dm, query)) return true
        }

        Log.e(TAG, "[COORD_FLOW_FAIL] could not open contact chat by coordinate")
        return false
    }

    private fun clickSearchIconByPosition(dm: android.util.DisplayMetrics) {
        val x = (dm.widthPixels * SEARCH_ICON_X_RATIO).toInt()
        val y = (dm.heightPixels * SEARCH_ICON_Y_RATIO).toInt()
        Log.e(TAG, "[TAP] searchIcon x=$x y=$y")
        tap(x, y)
    }

    private fun clickSearchInputByPosition(dm: android.util.DisplayMetrics) {
        val x = (dm.widthPixels * SEARCH_INPUT_X_RATIO).toInt()   // 水平居中
        val y = (dm.heightPixels * SEARCH_INPUT_Y_RATIO).toInt()  // 垂直约 13% 处这是搜索输入框参数
        Log.e(TAG, "[TAP] searchInput x=$x y=$y")
        tap(x, y)
    }

    private fun clickKeyboardSearchByPosition(dm: android.util.DisplayMetrics) {
        val x = (dm.widthPixels * 0.9f).toInt()
        val y = (dm.heightPixels * 0.85f).toInt()
        Log.e(TAG, "[TAP] keyboardSearch x=$x y=$y")
        tap(x, y)
    }

    private fun clickFirstContactResultCandidate(dm: android.util.DisplayMetrics, query: String): Boolean {
        val baseX = (dm.widthPixels * 0.5f).toInt()
        val startY = (dm.heightPixels * 0.25f).toInt()
        val step = (dm.density * 56).toInt()

        for (i in 0 until 4) {
            val y = startY + i * step
            Log.e(TAG, "[TAP] candidate#$i x=$baseX y=$y")
            tap(baseX, y)
            SystemClock.sleep(350)
            return true
        }
        return false
    }

    /**
     * Stub for OCR-based screen text search.
     * Currently returns false. If you want, I can integrate ML Kit / Tesseract
     * to capture the screen and locate the text, then tap the found coordinates.
     */
    private fun findTextOnScreenAndTap(query: String): Boolean {
        Log.e(TAG, "[OCR] findTextOnScreenAndTap stub called for query='$query'")
        // TODO: implement screenshot capture + OCR detection (ML Kit or Tesseract)
        // For now return false so coordinate fallback runs.
        return false
    }

    private fun pasteText(text: String) {
        try {
            val clipboard = service.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("wechat_search", text)
            clipboard.setPrimaryClip(clip)
            Log.e(TAG, "[PASTE] text placed into clipboard")
        } catch (t: Throwable) {
            Log.e(TAG, "[PASTE_FAIL] ${t.message}")
        }
    }

    private fun tap(x: Int, y: Int, durationMs: Long = 50L) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            try {
                val path = android.graphics.Path()
                path.moveTo(x.toFloat(), y.toFloat())
                val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
                gestureBuilder.addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs))
                val gesture = gestureBuilder.build()
                val latch = java.util.concurrent.CountDownLatch(1)
                service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        super.onCompleted(gestureDescription)
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        super.onCancelled(gestureDescription)
                        latch.countDown()
                    }
                }, null)
                latch.await(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (t: Throwable) {
                Log.e(TAG, "[GESTURE_FAIL] ${t.message}")
            }
        } else {
            Log.e(TAG, "[TAP_SKIP] gesture not supported on SDK ${android.os.Build.VERSION.SDK_INT}")
        }
    }
}
