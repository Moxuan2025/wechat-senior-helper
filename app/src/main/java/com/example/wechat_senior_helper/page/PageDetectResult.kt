package com.example.wechat_senior_helper.page

/**
 * Page Detection Result Model
 * Contains detected page type, confidence score, and matched rules
 * 
 * @author moxuan
 */
data class PageDetectResult(
    val pageType: WeChatPageType,
    val confidence: Float,
    val matchedRules: List<String> = emptyList()
)
