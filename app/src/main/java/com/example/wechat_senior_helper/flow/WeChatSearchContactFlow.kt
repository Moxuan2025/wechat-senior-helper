package com.example.wechat_senior_helper.flow

import android.util.Log
import com.example.wechat_senior_helper.input.CoordinateInputHelper
import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.OcrHit
import com.example.wechat_senior_helper.ocr.OcrLineMatcher
import com.example.wechat_senior_helper.ocr.WechatScreenAnalyzer
import kotlinx.coroutines.delay

class WeChatSearchContactFlow(
    private val screenshotProvider: AccessibilityScreenshotProvider,
    private val analyzer: WechatScreenAnalyzer,
    private val input: CoordinateInputHelper,
    private val pasteMenuXRatio: Float = 0.12f,
    private val pasteMenuYRatio: Float = 0.11f,
    private val setClipboardText: (String) -> Unit,
    private val tap: (x: Int, y: Int) -> Unit,
    private val clickSearchIconByPosition: () -> Unit,
    private val searchWaitMs: Long = 1000L,
    private val matchThreshold: Float = OcrLineMatcher.MATCH_THRESHOLD
) {
    companion object {
        private const val TAG = "WeChatSearchFlow"
    }

    // ===================== 公开 API =====================

    /**
     * 判断当前搜索结果页是否存在与 [contactName] 匹配的联系人。
     * 纯判断，不点击。要求调用前已处于搜索结果页。
     */
    suspend fun checkMatchExists(contactName: String): Boolean {
        return findContactMatch(contactName) != null
    }

    /**
     * 完整搜索+点击流程：输入联系人 → 等待搜索 → OCR 匹配 → 点击命中行
     * @return true 表示找到并点击成功
     */
    suspend fun execute(contactName: String): Boolean {
        // 输入阶段
        setClipboardText(contactName)
        clickSearchIconByPosition()
        delay(250)

        Log.e(TAG, "[INPUT] tap search box")
        input.tapByRatio(0.50f, 0.07f)
        delay(200)

        Log.e(TAG, "[INPUT] long press search box 1500ms")
        input.longPressByRatio(0.50f, 0.07f, 1500)
        delay(250)

        Log.e(TAG, "[INPUT] tap paste menu")
        input.tapByRatio(pasteMenuXRatio, pasteMenuYRatio)

        // 匹配+点击阶段
        val hit = findContactMatch(contactName) ?: return false
        Log.e(TAG, "[MATCH] score=${hit.score} text='${hit.text}'")
        tap(hit.rect.centerX(), hit.rect.centerY())
        return true
    }

    // ===================== 私有方法 =====================

    /**
     * 截图 → OCR → 匹配，返回命中结果。
     * 任何一步失败均返回 null。
     */
    private suspend fun findContactMatch(contactName: String): OcrHit? {
        delay(searchWaitMs)

        val screenshot = try {
            screenshotProvider.capture()
        } catch (e: Exception) {
            Log.e(TAG, "[SCREENSHOT_FAIL] ${e.message}")
            return null
        }

        val hit = analyzer.findContactHit(screenshot, contactName) ?: run {
            Log.e(TAG, "[MATCH] no hit for '$contactName'")
            return null
        }

        if (hit.score < matchThreshold) {
            Log.e(TAG, "[MATCH] below threshold: score=${hit.score} < $matchThreshold")
            return null
        }

        return hit
    }
}
