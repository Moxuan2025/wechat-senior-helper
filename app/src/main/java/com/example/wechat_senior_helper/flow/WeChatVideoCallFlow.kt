package com.example.wechat_senior_helper.flow

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.wechat_senior_helper.input.CoordinateInputHelper
import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.MlKitOcrEngine
import com.example.wechat_senior_helper.ocr.OcrLine
import com.example.wechat_senior_helper.ocr.OcrLineMatcher
import kotlinx.coroutines.delay

class WeChatVideoCallFlow(
    private val input: CoordinateInputHelper,
    private val screenshotProvider: AccessibilityScreenshotProvider,
    private val ocrEngine: MlKitOcrEngine
) {

    companion object {
        private const val TAG = "WeChatVideoCallFlow"

        // 加号按钮坐标（输入框右侧）
        private const val PLUS_BTN_X_RATIO = 0.99f
        private const val PLUS_BTN_Y_RATIO = 0.99f

        // 菜单弹出等待
        private const val MENU_DELAY_MS = 600L
        // 界面切换等待
        private const val PAGE_DELAY_MS = 600L

        // OCR 裁剪区域：从屏幕 40% 到 100%（覆盖菜单和底部面板）
        private const val CROP_TOP_RATIO = 0.4f

        // 文字匹配阈值（Levenshtein 相似度）
        private const val MATCH_THRESHOLD = 0.75f
    }

    /**
     * 发起语音/视频通话
     * @param mode AUDIO=语音通话，VIDEO=视频通话
     */
    suspend fun makeCall(mode: CallMode = CallMode.AUDIO): Boolean {
        return try {
            // 1. 点击加号
            input.tapByRatio(PLUS_BTN_X_RATIO, PLUS_BTN_Y_RATIO)
            delay(MENU_DELAY_MS)

            // 2. 点击菜单中的"视频通话"，失败重试一次
            var videoCallHit = findAndTapText("视频通话")
            if (videoCallHit == null) {
                delay(300)
                videoCallHit = findAndTapText("视频通话")
            }
            if (videoCallHit == null) {
                Log.e(TAG, "未找到菜单项「视频通话」")
                return false
            }
            Log.e(TAG, "点击视频通话: ${videoCallHit.text}")
            delay(PAGE_DELAY_MS)

            // 3. 点击"语音通话"或"视频通话"
            val targetText = when (mode) {
                CallMode.AUDIO -> "语音通话"
                CallMode.VIDEO -> "视频通话"
                CallMode.UNKNOWN -> {
                    Log.e(TAG, "CallMode.UNKNOWN 不支持")
                    return false
                }
            }
            val callHit = findAndTapText(targetText)
            if (callHit != null) {
                Log.e(TAG, "点击$targetText 成功")
                true
            } else {
                Log.e(TAG, "未找到 $targetText")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "通话发起失败: ${e.message}")
            false
        }
    }

    /**
     * 在当前屏幕截图中查找包含 [targetText] 的 OCR 行，并点击其中心。
     * 搜索区域为屏幕下半部分（CROP_TOP_RATIO 以下）。
     */
    private suspend fun findAndTapText(targetText: String): OcrLine? {
        val fullBitmap = try {
            screenshotProvider.capture()
        } catch (e: Exception) {
            Log.e(TAG, "截图失败: ${e.message}")
            return null
        }

        val width = fullBitmap.width
        val height = fullBitmap.height
        val cropTop = (height * CROP_TOP_RATIO).toInt()
        val cropBitmap = Bitmap.createBitmap(fullBitmap, 0, cropTop, width, height - cropTop)

        val ocrResult = ocrEngine.recognize(cropBitmap)
        cropBitmap.recycle()

        val lines = OcrLineMatcher.extractLines(ocrResult)

        // 按相似度排序，取最高且 ≥ 阈值的行
        val bestLine = lines
            .map { line -> line to similarity(line.text, targetText) }
            .filter { it.second >= MATCH_THRESHOLD }
            .maxByOrNull { it.second }

        if (bestLine != null) {
            val (line, _) = bestLine
            // 坐标还原到全屏坐标系（裁剪偏移量 cropTop）
            val adjustedRect = Rect(
                line.rect.left,
                line.rect.top + cropTop,
                line.rect.right,
                line.rect.bottom + cropTop
            )
            val clickX = adjustedRect.centerX()
            val clickY = adjustedRect.centerY()
            input.tapByCoordinate(clickX, clickY)
            return line
        }
        return null
    }

    // ===================== 文本相似度 =====================
    private fun similarity(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        val distance = levenshtein(a, b)
        return 1f - distance.toFloat() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val costs = IntArray(b.length + 1)
        for (i in 0..b.length) costs[i] = i
        for (i in 1..a.length) {
            var last = i - 1
            costs[0] = i
            for (j in 1..b.length) {
                val temp = costs[j]
                costs[j] = if (a[i - 1] == b[j - 1]) last
                else 1 + minOf(minOf(costs[j], costs[j - 1]), last)
                last = temp
            }
        }
        return costs[b.length]
    }
}
