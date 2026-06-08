package com.example.wechat_senior_helper.flow

import android.graphics.Bitmap
import android.util.Log
import com.example.wechat_senior_helper.input.CoordinateInputHelper
import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.MlKitOcrEngine
import kotlinx.coroutines.delay

class WeChatVoiceFlow(
    private val input: CoordinateInputHelper,
    private val screenshotProvider: AccessibilityScreenshotProvider,
    private val ocrEngine: MlKitOcrEngine
) {

    companion object {
        private const val TAG = "WeChatVoiceFlow"

        // 录音按钮坐标（底部中央"按住说话"）
        private const val RECORD_BTN_X_RATIO = 0.5f
        private const val RECORD_BTN_Y_RATIO = 0.99f

        // 语音图标坐标（左下角，点击切换为语音模式）
        private const val VOICE_ICON_X_RATIO = 0.01f
        private const val VOICE_ICON_Y_RATIO = 0.99f

        // 底部检测区域：裁剪屏幕底部 8% 高度用于 OCR
        private const val BOTTOM_CROP_TOP_RATIO = 0.92f

        // 默认录音时长
        private const val DEFAULT_DURATION_MS = 2000L
    }

    /**
     * 发送语音消息：先确保语音模式，再长按录音。
     */
    suspend fun sendVoiceMessage(durationMs: Long = DEFAULT_DURATION_MS): Boolean {
        return try {
            ensureVoiceMode()
            input.longPressByRatio(RECORD_BTN_X_RATIO, RECORD_BTN_Y_RATIO, durationMs)
            true
        } catch (e: Exception) {
            Log.e(TAG, "语音发送失败: ${e.message}")
            false
        }
    }

    /**
     * 确保当前处于语音输入模式。
     * OCR 检测底部是否有"按住说话"，没有则点击语音图标切换。
     */
    private suspend fun ensureVoiceMode() {
        if (isVoiceModeActive()) return
        Log.e(TAG, "点击语音图标切换模式")
        input.tapByRatio(VOICE_ICON_X_RATIO, VOICE_ICON_Y_RATIO)
        delay(500)
    }

    /**
     * OCR 识别底部区域，判断是否显示"按住说话"。
     */
    private suspend fun isVoiceModeActive(): Boolean {
        return try {
            val fullBitmap = screenshotProvider.capture()
            val width = fullBitmap.width
            val height = fullBitmap.height
            val cropTop = (height * BOTTOM_CROP_TOP_RATIO).toInt()

            val bottomBitmap = Bitmap.createBitmap(
                fullBitmap, 0, cropTop, width, height - cropTop
            )

            val result = ocrEngine.recognize(bottomBitmap, preferChinese = true)
            bottomBitmap.recycle()

            val recognizedText = result.text
            Log.e(TAG, "[OCR_BOTTOM] text='${recognizedText.take(50)}'")

            recognizedText.contains("按住说话")
        } catch (e: Exception) {
            Log.e(TAG, "底部OCR检测失败: ${e.message}")
            false
        }
    }
}
