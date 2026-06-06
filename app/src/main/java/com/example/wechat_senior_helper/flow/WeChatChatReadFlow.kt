package com.example.wechat_senior_helper.flow

import com.example.wechat_senior_helper.ocr.AccessibilityScreenshotProvider
import com.example.wechat_senior_helper.ocr.WechatScreenAnalyzer

class WeChatChatReadFlow(
    private val screenshotProvider: AccessibilityScreenshotProvider,
    private val analyzer: WechatScreenAnalyzer
) {
    suspend fun readVisibleChat(): List<String> {
        val screenshot = screenshotProvider.capture()
        val blocks = analyzer.parseVisibleChatBlocks(screenshot)
        return blocks.map { it.text }
    }
}
