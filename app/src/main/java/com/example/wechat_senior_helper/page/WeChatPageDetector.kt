package com.example.wechat_senior_helper.page

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * WeChat Page Detector
 * Identifies current WeChat page type based on accessibility node tree features
 * Uses rule-based scoring system without OCR
 * 
 * @author moxuan
 */
class WeChatPageDetector {

    private companion object {
        private const val TAG = "WeChatPageDetector"
    }

    /**
     * Detect current WeChat page type
     * 
     * @param root Root accessibility node
     * @return Detection result with page type, confidence and matched rules
     */
    fun detect(root: AccessibilityNodeInfo?): PageDetectResult {
        if (root == null) {
            Log.w(TAG, "Root node is null")
            return PageDetectResult(WeChatPageType.UNKNOWN, 0f, listOf("root_null"))
        }

        val features = collectFeatures(root)
        
        Log.d(TAG, "========== 页面特征收集完成 ==========")
        Log.d(TAG, "文本特征数: ${features.texts.size}")
        Log.d(TAG, "类名特征数: ${features.classNames.size}")
        Log.d(TAG, "可编辑节点数: ${features.editableCount}")
        Log.d(TAG, "可点击节点数: ${features.clickableCount}")

        val candidates = listOf(
            scoreChatPage(features) to WeChatPageType.CHAT_PAGE,
            scoreSessionList(features) to WeChatPageType.SESSION_LIST,
            scoreContactsPage(features) to WeChatPageType.CONTACTS_PAGE,
            scoreDiscoverPage(features) to WeChatPageType.DISCOVER_PAGE,
            scoreMePage(features) to WeChatPageType.ME_PAGE
        )

        // Log scores for debugging
        candidates.forEach { (score, type) ->
            Log.d(TAG, "页面类型评分: ${type.name} = $score")
        }

        val best = candidates.maxByOrNull { it.first } ?: (0f to WeChatPageType.UNKNOWN)

        val result = if (best.first <= 0f) {
            PageDetectResult(WeChatPageType.UNKNOWN, 0f, listOf("no_rule_matched"))
        } else {
            PageDetectResult(
                pageType = best.second,
                confidence = best.first.coerceAtMost(1f),
                matchedRules = features.matchedRulesFor(best.second)
            )
        }

        Log.e(TAG, "📱 页面识别结果: ${result.pageType.name}, 置信度: ${result.confidence}")
        Log.d(TAG, "匹配规则: ${result.matchedRules}")
        Log.d(TAG, "========== 页面识别完成 ==========")

        return result
    }

    /**
     * Feature data class for page detection
     */
    private data class Features(
        val texts: List<String>,
        val classNames: List<String>,
        val viewIds: List<String>,
        val editableCount: Int,
        val clickableCount: Int,
        val matchedRulesMap: MutableMap<WeChatPageType, MutableList<String>>
    ) {
        fun matchedRulesFor(pageType: WeChatPageType): List<String> =
            matchedRulesMap[pageType].orEmpty()
    }

    /**
     * Collect features from accessibility node tree using BFS
     */
    private fun collectFeatures(root: AccessibilityNodeInfo): Features {
        val texts = mutableListOf<String>()
        val classNames = mutableListOf<String>()
        val viewIds = mutableListOf<String>()
        var editableCount = 0
        var clickableCount = 0

        val matchedRulesMap = mutableMapOf(
            WeChatPageType.CHAT_PAGE to mutableListOf<String>(),
            WeChatPageType.SESSION_LIST to mutableListOf<String>(),
            WeChatPageType.CONTACTS_PAGE to mutableListOf<String>(),
            WeChatPageType.DISCOVER_PAGE to mutableListOf<String>(),
            WeChatPageType.ME_PAGE to mutableListOf<String>()
        )

        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        var nodeCount = 0
        val maxNodes = 500 // Limit traversal to avoid performance issues

        while (queue.isNotEmpty() && nodeCount < maxNodes) {
            val node = queue.removeFirst()
            nodeCount++

            // Collect text features
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            
            // Collect class name features
            node.className?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { classNames.add(it) }
            
            // Collect view ID features
            node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() }?.let { viewIds.add(it) }

            // Count editable and clickable nodes
            if (node.isEditable) editableCount++
            if (node.isClickable) clickableCount++

            // Add children to queue
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }
        }

        if (nodeCount >= maxNodes) {
            Log.w(TAG, "达到最大节点遍历限制: $maxNodes")
        }

        return Features(
            texts = texts.distinct(),
            classNames = classNames.distinct(),
            viewIds = viewIds.distinct(),
            editableCount = editableCount,
            clickableCount = clickableCount,
            matchedRulesMap = matchedRulesMap
        )
    }

    /**
     * Score for chat page detection
     */
    private fun scoreChatPage(features: Features): Float {
        var score = 0f
        val texts = features.texts
        val classNames = features.classNames
        val viewIds = features.viewIds

        if (containsAny(texts, listOf("发送", "按住 说话", "语音输入", "表情"))) {
            score += 0.35f
            features.matchedRulesMap[WeChatPageType.CHAT_PAGE]?.add("chat_input_keywords")
        }
        
        if (features.editableCount > 0) {
            score += 0.35f
            features.matchedRulesMap[WeChatPageType.CHAT_PAGE]?.add("has_editable_node")
        }
        
        if (containsAny(texts, listOf("聊天信息", "置顶聊天", "消息免打扰", "更多"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.CHAT_PAGE]?.add("chat_menu_keywords")
        }
        
        if (containsAny(classNames, listOf("android.widget.EditText"))) {
            score += 0.15f
            features.matchedRulesMap[WeChatPageType.CHAT_PAGE]?.add("has_edittext_class")
        }
        
        if (containsAny(viewIds, listOf("input", "editor", "send", "chat"))) {
            score += 0.15f
            features.matchedRulesMap[WeChatPageType.CHAT_PAGE]?.add("chat_view_id")
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Score for session list page detection
     */
    private fun scoreSessionList(features: Features): Float {
        var score = 0f
        val texts = features.texts

        if (containsAny(texts, listOf("微信", "通讯录", "发现", "我"))) {
            score += 0.45f
            features.matchedRulesMap[WeChatPageType.SESSION_LIST]?.add("bottom_tabs")
        }
        
        if (containsAny(texts, listOf("搜索", "聊天", "置顶"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.SESSION_LIST]?.add("search_keywords")
        }
        
        if (containsAny(texts, listOf("订阅号", "服务通知", "文件传输助手"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.SESSION_LIST]?.add("system_accounts")
        }
        
        if (features.clickableCount > 10) {
            score += 0.1f
            features.matchedRulesMap[WeChatPageType.SESSION_LIST]?.add("many_clickable_items")
        }
        
        if (containsAny(texts, listOf("未读消息"))) {
            score += 0.15f
            features.matchedRulesMap[WeChatPageType.SESSION_LIST]?.add("unread_message")
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Score for contacts page detection
     */
    private fun scoreContactsPage(features: Features): Float {
        var score = 0f
        val texts = features.texts

        if (containsAny(texts, listOf("新的朋友", "群聊", "标签", "公众号"))) {
            score += 0.45f
            features.matchedRulesMap[WeChatPageType.CONTACTS_PAGE]?.add("contact_sections")
        }
        
        if (containsAny(texts, listOf("搜索", "联系人"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.CONTACTS_PAGE]?.add("contact_search")
        }
        
        if (containsAny(texts, listOf("通讯录", "添加朋友"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.CONTACTS_PAGE]?.add("contacts_keywords")
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Score for discover page detection
     */
    private fun scoreDiscoverPage(features: Features): Float {
        var score = 0f
        val texts = features.texts

        if (containsAny(texts, listOf("朋友圈", "扫一扫", "摇一摇", "看一看", "搜一搜"))) {
            score += 0.5f
            features.matchedRulesMap[WeChatPageType.DISCOVER_PAGE]?.add("discover_features")
        }
        
        if (containsAny(texts, listOf("小程序", "游戏", "直播和附近"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.DISCOVER_PAGE]?.add("discover_more")
        }
        
        if (containsAny(texts, listOf("发现"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.DISCOVER_PAGE]?.add("discover_tab")
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Score for me/profile page detection
     */
    private fun scoreMePage(features: Features): Float {
        var score = 0f
        val texts = features.texts

        if (containsAny(texts, listOf("支付", "收藏", "朋友圈", "卡包", "表情", "设置"))) {
            score += 0.45f
            features.matchedRulesMap[WeChatPageType.ME_PAGE]?.add("me_menu_items")
        }
        
        if (containsAny(texts, listOf("我"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.ME_PAGE]?.add("me_tab")
        }
        
        if (containsAny(texts, listOf("头像", "微信号"))) {
            score += 0.2f
            features.matchedRulesMap[WeChatPageType.ME_PAGE]?.add("profile_info")
        }

        return score.coerceAtMost(1f)
    }

    /**
     * Check if any keyword exists in source list (case-insensitive)
     */
    private fun containsAny(source: List<String>, keywords: List<String>): Boolean {
        return source.any { item -> 
            keywords.any { kw -> item.contains(kw, ignoreCase = true) } 
        }
    }
}
