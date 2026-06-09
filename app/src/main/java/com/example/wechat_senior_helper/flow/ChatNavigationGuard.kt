package com.example.wechat_senior_helper.flow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.example.wechat_senior_helper.input.CoordinateInputHelper
import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.MlKitOcrEngine
import com.example.wechat_senior_helper.ocr.OcrLineMatcher
import com.example.wechat_senior_helper.util.WeChatLauncher
import kotlinx.coroutines.delay

class ChatNavigationGuard(
    private val input: CoordinateInputHelper,
    private val screenshotProvider: AccessibilityScreenshotProvider,
    private val ocrEngine: MlKitOcrEngine,
    private val searchContact: suspend (String) -> Boolean,
    private val context: Context,
    private val isWeChatForeground: () -> Boolean
) {
    companion object {
        private const val TAG = "ChatNavGuard"

        // 顶部标题栏裁剪："微信"文字在搜索图标同高度、水平居中
        private const val TITLE_CROP_Y_START = 0.0f
        private const val TITLE_CROP_Y_END = 0.10f
        private const val TITLE_CROP_X_START = 0.25f
        private const val TITLE_CROP_X_END = 0.75f

        // 聊天标题裁剪（联系人名字）
        private const val CHAT_TOP_START_Y = 0.06f
        private const val CHAT_TOP_END_Y = 0.13f

        // 底部 Tab 栏裁剪
        private const val BOTTOM_TAB_START_Y = 0.85f
        private const val BOTTOM_TAB_END_Y = 1.0f

        // 底部 Tab 关键词
        private val BOTTOM_TAB_KEYWORDS = listOf("微信", "通讯录", "发现", "我")

        // 匹配阈值
        private const val NAME_MATCH_THRESHOLD = 0.7f

        // 最多按 1 次返回（微信首页按返回就是退出，不能多按）
        private const val MAX_BACK_ATTEMPTS = 1
    }

    // ===================== 统一入口 =====================

    /**
     * 确保微信前台 + 进入目标聊天 + 执行操作。
     * 悬浮球应在调用前已隐藏。
     */
    suspend fun navigateAndExecute(targetName: String, action: suspend () -> Unit): String {
        // 1. 确保微信前台
        if (!isWeChatForeground()) {
            Log.e(TAG, "启动微信")
            WeChatLauncher.launchWeChat(context)
            delay(800)
            if (!isWeChatForeground()) return "无法打开微信"
        }

        // 2. 已在目标聊天 → 直接执行
        if (isCorrectChat(targetName)) {
            Log.e(TAG, "已在目标聊天页")
            action()
            return "已执行"
        }

        // 3. 确保在微信首页
        goToHome()

        // 4. 搜索进入目标聊天
        Log.e(TAG, "搜索进入: $targetName")
        delay(300) // 首页稳定缓冲
        val entered = searchContact(targetName)
        if (!entered) return "没有找到「$targetName」，请检查名字"

        // 5. 等聊天页加载完成
        delay(800)

        // 6. 执行操作（完全复用 WeChatVoiceFlow / WeChatVideoCallFlow）
        action()
        return "已给${targetName}发语音"
    }

    /**
     * 原有方法：确保在目标聊天（不执行动作），返回是否成功。
     */
    suspend fun ensureChatWithTarget(targetName: String): Boolean {
        if (!isWeChatForeground()) {
            WeChatLauncher.launchWeChat(context)
            delay(800)
            if (!isWeChatForeground()) return false
        }
        if (isCorrectChat(targetName)) return true
        goToHome()
        delay(300)
        return searchContact(targetName)
    }

    // ===================== 页面检测 =====================

    private suspend fun isCorrectChat(targetName: String): Boolean {
        val bitmap = try { screenshotProvider.capture() } catch (_: Exception) { return false }
        val top = (bitmap.height * CHAT_TOP_START_Y).toInt()
        val bottom = (bitmap.height * CHAT_TOP_END_Y).toInt()
        if (bottom <= top) return false
        val crop = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
        val lines = OcrLineMatcher.extractLines(ocrEngine.recognize(crop))
        crop.recycle()
        if (lines.isEmpty()) return false
        val best = lines.maxOfOrNull { similarity(it.text, targetName) } ?: 0f
        Log.e(TAG, "聊天标题匹配: best=$best")
        return best >= NAME_MATCH_THRESHOLD
    }

    /**
     * 识别微信首页，满足任一条件即判定为首页：
     * 1. 顶部居中区域 OCR 检测到"微信"（原标题检测）
     * 2. 底部 Tab 栏同时出现 {微信, 通讯录, 发现, 我} 中 ≥3 个
     */
    private suspend fun isHomePage(): Boolean {
        val bitmap = try { screenshotProvider.capture() } catch (_: Exception) { return false }

        // 条件 1：顶部标题检测
        val topLeft = (bitmap.width * TITLE_CROP_X_START).toInt()
        val topTop = (bitmap.height * TITLE_CROP_Y_START).toInt()
        val topRight = (bitmap.width * TITLE_CROP_X_END).toInt()
        val topBottom = (bitmap.height * TITLE_CROP_Y_END).toInt()

        var topHit = false
        if (topRight > topLeft && topBottom > topTop) {
            val topCrop = Bitmap.createBitmap(bitmap, topLeft, topTop, topRight - topLeft, topBottom - topTop)
            val topText = ocrEngine.recognize(topCrop).text
            topCrop.recycle()
            topHit = topText.contains("微信")
            Log.e(TAG, "首页检测[顶部]: '$topText' -> $topHit")
        }

        // 条件 2：底部 Tab 检测
        val botLeft = 0
        val botTop = (bitmap.height * BOTTOM_TAB_START_Y).toInt()
        val botRight = bitmap.width
        val botBottom = (bitmap.height * BOTTOM_TAB_END_Y).toInt()

        var bottomHit = false
        if (botBottom > botTop) {
            val botCrop = Bitmap.createBitmap(bitmap, botLeft, botTop, botRight - botLeft, botBottom - botTop)
            val botText = ocrEngine.recognize(botCrop).text
            botCrop.recycle()
            val matched = BOTTOM_TAB_KEYWORDS.count { botText.contains(it) }
            bottomHit = matched >= 3
            Log.e(TAG, "首页检测[底部]: '$botText' -> 匹配${matched}/4 -> $bottomHit")
        }

        val result = topHit || bottomHit
        Log.e(TAG, "首页检测: 顶部=$topHit 底部=$bottomHit -> $result")
        return result
    }

    suspend fun goToHome() {
        repeat(MAX_BACK_ATTEMPTS) {
            if (!isWeChatForeground()) return
            if (isHomePage()) { Log.e(TAG, "已回到首页"); return }
            input.pressBack()
            delay(400)
        }
        Log.w(TAG, "未确认回到首页")
    }

    // ===================== 文本相似度 =====================
    private fun similarity(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        return 1f - levenshtein(a, b).toFloat() / maxLen
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
