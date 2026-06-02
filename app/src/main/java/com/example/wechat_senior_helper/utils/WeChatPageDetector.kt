package com.example.wechat_senior_helper.utils

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 微信页面识别器
 * 根据 UI 树特征识别当前所在的微信页面
 */
object WeChatPageDetector {

    private const val TAG = "WeChatPageDetector"

    /**
     * 页面类型枚举
     */
    enum class PageType {
        UNKNOWN,          // 未知页面
        WECHAT_HOME,      // 微信主页（底部有四个Tab）
        CHAT_LIST,        // 聊天列表页
        CHAT_DETAIL,      // 聊天详情页
        CONTACTS,         // 通讯录
        DISCOVER,         // 发现
        ME,               // 我
        MOMENTS,          // 朋友圈
        MINI_PROGRAM      // 小程序
    }

    /**
     * 识别当前页面类型
     * @param root 根节点
     * @return 页面类型
     */
    fun detectPageType(root: AccessibilityNodeInfo): PageType {
        // 策略1: 检查底部导航栏特征（微信主页）
        if (isWeChatHome(root)) {
            return PageType.WECHAT_HOME
        }

        // 策略2: 检查聊天输入框（聊天详情页）
        if (isChatDetail(root)) {
            return PageType.CHAT_DETAIL
        }

        // 策略3: 检查通讯录特征
        if (isContactsPage(root)) {
            return PageType.CONTACTS
        }

        // 策略4: 检查发现页特征
        if (isDiscoverPage(root)) {
            return PageType.DISCOVER
        }

        // 策略5: 检查"我"页面特征
        if (isMePage(root)) {
            return PageType.ME
        }

        return PageType.UNKNOWN
    }

    /**
     * 判断是否为微信主页
     * 特征：底部有"微信"、"通讯录"、"发现"、"我"四个Tab
     */
    private fun isWeChatHome(root: AccessibilityNodeInfo): Boolean {
        val hasWeChat = findNodeByText(root, "微信") != null
        val hasContacts = findNodeByText(root, "通讯录") != null
        val hasDiscover = findNodeByText(root, "发现") != null
        val hasMe = findNodeByText(root, "我") != null

        val result = hasWeChat && hasContacts && hasDiscover && hasMe
        if (result) {
            Log.d(TAG, "识别为微信主页")
        }
        return result
    }

    /**
     * 判断是否为聊天详情页
     * 特征：存在输入框或发送按钮
     */
    private fun isChatDetail(root: AccessibilityNodeInfo): Boolean {
        // 查找输入框特征
        val hasInputBox = findNodeByContentDescription(root, "输入框") != null
                || findNodeByIdContains(root, "input") != null
                || findNodeByIdContains(root, "edittext") != null

        // 查找发送按钮特征
        val hasSendButton = findNodeByText(root, "发送") != null
                || findNodeByContentDescription(root, "发送") != null

        val result = hasInputBox || hasSendButton
        if (result) {
            Log.d(TAG, "识别为聊天详情页")
        }
        return result
    }

    /**
     * 判断是否为通讯录页面
     */
    private fun isContactsPage(root: AccessibilityNodeInfo): Boolean {
        // 查找"新的朋友"、"群聊"、"标签"、"公众号"等特征
        val hasNewFriends = findNodeByText(root, "新的朋友") != null
        val hasGroupChat = findNodeByText(root, "群聊") != null

        val result = hasNewFriends || hasGroupChat
        if (result) {
            Log.d(TAG, "识别为通讯录页面")
        }
        return result
    }

    /**
     * 判断是否为发现页面
     */
    private fun isDiscoverPage(root: AccessibilityNodeInfo): Boolean {
        // 查找"朋友圈"、"扫一扫"、"搜一搜"等特征
        val hasMoments = findNodeByText(root, "朋友圈") != null
        val hasScan = findNodeByText(root, "扫一扫") != null

        val result = hasMoments || hasScan
        if (result) {
            Log.d(TAG, "识别为发现页面")
        }
        return result
    }

    /**
     * 判断是否为"我"页面
     */
    private fun isMePage(root: AccessibilityNodeInfo): Boolean {
        // 查找"服务"、"收藏"、"朋友圈"、"设置"等特征
        val hasService = findNodeByText(root, "服务") != null
        val hasFavorites = findNodeByText(root, "收藏") != null

        val result = hasService || hasFavorites
        if (result) {
            Log.d(TAG, "识别为\"我\"页面")
        }
        return result
    }

    /**
     * 查找包含指定文本的节点
     */
    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        return AccessibilityTreeDumper.findNodeByText(root, text)
    }

    /**
     * 查找包含指定内容描述的节点
     */
    private fun findNodeByContentDescription(root: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        return AccessibilityTreeDumper.findNodeByContentDescription(root, desc)
    }

    /**
     * 查找 ID 包含指定字符串的节点
     */
    private fun findNodeByIdContains(root: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val viewId = node.viewIdResourceName
            if (viewId != null && viewId.contains(keyword, ignoreCase = true)) {
                Log.d(TAG, "找到 ID 包含 '$keyword' 的节点: $viewId")
                return node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return null
    }
}
