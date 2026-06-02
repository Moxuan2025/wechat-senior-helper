package com.example.wechat_senior_helper.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * 微信页面识别器（新方案）
 * 基于 AccessibilityEvent.className + 少量锚点节点
 */
object WeChatPageDetector {

    private const val TAG = "WeChatPageDetector"

    enum class PageType {
        UNKNOWN,
        WECHAT_HOME,         // 聊天列表（微信主界面）
        CHAT_DETAIL,         // 聊天详情页
        CONTACTS,            // 通讯录（在 LauncherUI 中）
        DISCOVER,            // 发现（在 LauncherUI 中）
        ME,                  // 我（在 LauncherUI 中）
        MOMENTS,             // 朋友圈
        MINI_PROGRAM,        // 小程序
        OTHERS               // 其他微信页面
    }

    // 已知的微信 Activity 类名（硬连表，后续可根据日志补充）
    private val WX_CLASS_NAMES = mapOf(
        // LauncherUI 是主界面，底部四个 Tab 都在这个 Activity 里
        "com.tencent.mm.ui.LauncherUI" to PageType.WECHAT_HOME,
        // 聊天详情
        "com.tencent.mm.ui.chatting.ChattingUI" to PageType.CHAT_DETAIL,
        // 朋友圈
        "com.tencent.mm.plugin.sns.ui.SnsTimeLineUI" to PageType.MOMENTS,
        // 小程序
        "com.tencent.mm.plugin.appbrand.ui.AppBrandUI" to PageType.MINI_PROGRAM
    )

    /**
     * 兼容旧调用：直接根据 root 节点判断
     * 内部从 root 提取 className 后走新流程
     */
    fun detectPageType(root: AccessibilityNodeInfo): PageType {
        val className = root.className?.toString() ?: ""
        Log.d(TAG, "兼容调用 detectPageType(root)，提取 className=$className")
        return detectPageType(className, root)
    }

    /**
     * 入口：根据 className 和 root 节点（可选）判断页面
     * @param className 事件中的 className
     * @param root 根节点（可为 null），用于区分 LauncherUI 下的不同 Tab
     */
    fun detectPageType(className: String, root: AccessibilityNodeInfo?): PageType {
        // 1. 先查 className 映射表
        val typeFromClass = WX_CLASS_NAMES[className]
        if (typeFromClass != null) {
            // 如果是 LauncherUI，需要进一步判断 Tab
            if (className == "com.tencent.mm.ui.LauncherUI") {
                return identifyLauncherTab(root)
            }
            return typeFromClass
        }

        // 2. 未知 className：尝试用 root 树寻找特征（兜底）
        Log.d(TAG, "未知 className: $className，尝试通过节点特征判断")
        if (root == null) return PageType.UNKNOWN

        // 检查是否是聊天详情页（有输入框或发送按钮）
        if (findText(root, "发送") != null || findContentDesc(root, "输入框") != null) {
            return PageType.CHAT_DETAIL
        }
        // 检查是否是通讯录
        if (findText(root, "新的朋友") != null || findText(root, "群聊") != null) {
            return PageType.CONTACTS
        }
        // 检查是否是发现
        if (findText(root, "朋友圈") != null || findText(root, "扫一扫") != null) {
            return PageType.DISCOVER
        }
        // 检查是否是我
        if (findText(root, "服务") != null || findText(root, "收藏") != null) {
            return PageType.ME
        }

        return PageType.UNKNOWN
    }

    /**
     * 仅根据 className 判断（当 root 不可用时使用）
     */
    fun detectPageTypeByClassName(className: String): PageType {
        return WX_CLASS_NAMES[className] ?: PageType.UNKNOWN
    }

    /**
     * 识别 LauncherUI 下的具体 Tab 页面
     * 通过查找 2~3 个锚点文本
     */
    private fun identifyLauncherTab(root: AccessibilityNodeInfo?): PageType {
        if (root == null) {
            Log.w(TAG, "LauncherUI 但 root 为空，默认返回 WECHAT_HOME")
            return PageType.WECHAT_HOME
        }

        // 按照优先级：通讯录 > 发现 > 我 > 默认聊天列表
        if (findText(root, "新的朋友") != null || findText(root, "群聊") != null) {
            Log.d(TAG, "LauncherUI → 通讯录")
            return PageType.CONTACTS
        }
        if (findText(root, "朋友圈") != null || findText(root, "扫一扫") != null) {
            Log.d(TAG, "LauncherUI → 发现")
            return PageType.DISCOVER
        }
        if (findText(root, "服务") != null || findText(root, "收藏") != null || findText(root, "设置") != null) {
            Log.d(TAG, "LauncherUI → 我")
            return PageType.ME
        }

        // 默认聊天列表
        Log.d(TAG, "LauncherUI → 聊天列表（默认）")
        return PageType.WECHAT_HOME
    }

    // ---------- 辅助查找方法 ----------
    private fun findText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        return findNodeMatching(root) { node ->
            node.text?.toString()?.contains(target, ignoreCase = true) == true
        }
    }

    private fun findContentDesc(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        return findNodeMatching(root) { node ->
            node.contentDescription?.toString()?.contains(target, ignoreCase = true) == true
        }
    }

    private fun findNodeMatching(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (predicate(node)) return node

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                queue.add(child)
            }
        }
        return null
    }
}