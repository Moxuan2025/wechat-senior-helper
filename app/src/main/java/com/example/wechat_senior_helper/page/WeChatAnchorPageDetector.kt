package com.example.wechat_senior_helper.page

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import kotlin.math.max

/**
 * WeChat Anchor-based Page Detector
 * Uses high-confidence anchor points instead of whole-tree feature counting
 * More robust and maintainable than statistical approach
 * 
 * @author moxuan
 */
class WeChatAnchorPageDetector {

    private companion object {
        private const val TAG = "WeChatAnchorDetector"
    }

    /**
     * Node snapshot for efficient matching
     */
    data class NodeSnapshot(
        val text: String = "",
        val contentDescription: String = "",
        val className: String = "",
        val viewId: String = "",
        val clickable: Boolean = false,
        val editable: Boolean = false
    )

    /**
     * Anchor rule definition
     */
    data class AnchorRule(
        val name: String,
        val keywords: List<String>,
        val weight: Float,
        val requireAny: Boolean = true
    )

    /**
     * Page rules configuration with anchor points and weights
     */
    private val pageRules: Map<WeChatPageType, List<AnchorRule>> = mapOf(
        WeChatPageType.CHAT_PAGE to listOf(
            AnchorRule("chat_input", listOf("发送", "按住说话", "语音输入"), 0.40f),
            AnchorRule("chat_toolbar", listOf("表情", "+", "更多"), 0.20f),
            AnchorRule("chat_info", listOf("聊天信息", "置顶聊天", "消息免打扰"), 0.25f),
            AnchorRule("chat_editable", listOf("android.widget.EditText"), 0.35f)
        ),
        WeChatPageType.SESSION_LIST to listOf(
            AnchorRule("bottom_tabs", listOf("微信", "通讯录", "发现", "我"), 0.20f),
            AnchorRule("session_search", listOf("搜索"), 0.20f),
            AnchorRule("session_special", listOf("文件传输助手", "服务通知", "订阅号", "公众号"), 0.40f),
            AnchorRule("session_title", listOf("微信"), 0.15f)
        ),
        WeChatPageType.CONTACTS_PAGE to listOf(
            AnchorRule("contacts_entries", listOf("新的朋友", "群聊", "标签", "公众号"), 0.45f),
            AnchorRule("contacts_search", listOf("搜索"), 0.15f),
            AnchorRule("contacts_title", listOf("通讯录", "联系人"), 0.25f)
        ),
        WeChatPageType.DISCOVER_PAGE to listOf(
            AnchorRule("discover_entries", listOf("朋友圈", "扫一扫", "摇一摇", "看一看", "搜一搜"), 0.50f),
            AnchorRule("discover_extra", listOf("小程序", "游戏", "直播和附近"), 0.20f),
            AnchorRule("discover_title", listOf("发现"), 0.20f)
        ),
        WeChatPageType.ME_PAGE to listOf(
            AnchorRule("me_entries", listOf("支付", "收藏", "朋友圈", "卡包", "表情", "设置"), 0.50f),
            AnchorRule("me_title", listOf("我"), 0.20f),
            AnchorRule("me_profile", listOf("微信号", "头像"), 0.20f)
        )
    )

    /**
     * Detect current page type using anchor-based matching
     * 
     * @param root Root accessibility node
     * @return Detection result with page type, confidence and matched anchors
     */
    fun detect(root: AccessibilityNodeInfo?): PageDetectResult {
        if (root == null) {
            Log.w(TAG, "Root node is null")
            return PageDetectResult(WeChatPageType.UNKNOWN, 0f, listOf("root_null"))
        }

        val nodes = collectSnapshots(root)
        if (nodes.isEmpty()) {
            Log.w(TAG, "Empty node tree")
            return PageDetectResult(WeChatPageType.UNKNOWN, 0f, listOf("empty_tree"))
        }

        Log.d(TAG, "========== 开始锚点匹配 ==========")
        Log.d(TAG, "收集节点数: ${nodes.size}")

        // Score each page type
        val scored = pageRules.map { (pageType, rules) ->
            val result = scorePage(pageType, rules, nodes)
            Log.d(TAG, "页面评分: ${pageType.name} = ${result.first}, 匹配锚点: ${result.second.size}")
            pageType to result
        }.maxByOrNull { it.second.first } ?: (WeChatPageType.UNKNOWN to (0f to emptyList<String>()))

        val bestScore = scored.second.first
        val matched = scored.second.second

        val result = if (bestScore <= 0f) {
            PageDetectResult(WeChatPageType.UNKNOWN, 0f, listOf("no_rule_matched"))
        } else {
            PageDetectResult(
                pageType = scored.first,
                confidence = bestScore.coerceAtMost(1f),
                matchedRules = matched
            )
        }

        Log.e(TAG, "📱 页面识别结果: ${result.pageType.name}")
        Log.e(TAG, "置信度: ${result.confidence}")
        Log.e(TAG, "匹配锚点: ${result.matchedRules.joinToString()}")
        Log.d(TAG, "========== 页面识别完成 ==========")

        return result
    }

    /**
     * Score a specific page type based on anchor rules
     * 
     * @return Pair of (score, matched anchor names)
     */
    private fun scorePage(
        pageType: WeChatPageType,
        rules: List<AnchorRule>,
        nodes: List<NodeSnapshot>
    ): Pair<Float, List<String>> {
        var score = 0f
        val matched = mutableListOf<String>()

        for (rule in rules) {
            val hit = nodes.any { nodeMatches(it, rule.keywords, rule.requireAny) }
            if (hit) {
                score += rule.weight
                matched.add("${pageType.name}.${rule.name}")
                Log.d(TAG, "✅ 匹配锚点: ${pageType.name}.${rule.name} (权重: ${rule.weight})")
            }
        }

        // Additional morphological scores for robustness
        when (pageType) {
            WeChatPageType.CHAT_PAGE -> {
                if (nodes.count { it.editable } > 0) {
                    score += 0.20f
                    Log.d(TAG, "✅ 形态特征: 可编辑节点")
                }
                if (nodes.count { it.clickable } > 8) {
                    score += 0.05f
                    Log.d(TAG, "✅ 形态特征: 多可点击节点")
                }
            }
            WeChatPageType.SESSION_LIST,
            WeChatPageType.CONTACTS_PAGE,
            WeChatPageType.DISCOVER_PAGE,
            WeChatPageType.ME_PAGE -> {
                if (nodes.count { it.clickable } > 6) {
                    score += 0.05f
                    Log.d(TAG, "✅ 形态特征: 多可点击节点")
                }
            }
            else -> Unit
        }

        return score to matched
    }

    /**
     * Check if a node matches the anchor rule keywords
     */
    private fun nodeMatches(
        node: NodeSnapshot,
        keywords: List<String>,
        requireAny: Boolean
    ): Boolean {
        val pool = listOf(node.text, node.contentDescription, node.className, node.viewId)
            .filter { it.isNotBlank() }

        if (pool.isEmpty()) return false

        return if (requireAny) {
            pool.any { field -> keywords.any { kw -> field.contains(kw, ignoreCase = true) } }
        } else {
            keywords.all { kw -> pool.any { field -> field.contains(kw, ignoreCase = true) } }
        }
    }

    /**
     * Collect node snapshots from accessibility tree using BFS
     * Limits traversal to avoid performance issues
     */
    private fun collectSnapshots(root: AccessibilityNodeInfo): List<NodeSnapshot> {
        val result = mutableListOf<NodeSnapshot>()
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(root)

        var count = 0
        val maxNodes = 800

        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            count++

            result.add(
                NodeSnapshot(
                    text = node.text?.toString().orEmpty().trim(),
                    contentDescription = node.contentDescription?.toString().orEmpty().trim(),
                    className = node.className?.toString().orEmpty().trim(),
                    viewId = node.viewIdResourceName?.trim().orEmpty(),
                    clickable = node.isClickable,
                    editable = node.isEditable
                )
            )

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        if (count >= maxNodes) {
            Log.w(TAG, "达到最大节点遍历限制: $maxNodes")
        }

        return result
    }
}
