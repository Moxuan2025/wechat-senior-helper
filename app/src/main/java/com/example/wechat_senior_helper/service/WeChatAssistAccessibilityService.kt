package com.example.wechat_senior_helper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.utils.WeChatPageDetector
import java.util.ArrayDeque

/**
 * 微信老年助手无障碍服务
 * 用于识别当前窗口、打印节点树、执行点击/回退/滑动等基础动作
 */
class WeChatAssistAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WeChatAssistService"

        @Deprecated("Use AccessibilityServiceStateManager.serviceStatus instead")
        val instance: WeChatAssistAccessibilityService?
            get() = AccessibilityServiceStateManager.instance
    }

    private var lastClickedTab: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceStateManager.setInstance(this)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.CONNECTED)
        Log.e(TAG, "========================================")
        Log.e(TAG, "无障碍服务已连接！")
        Log.e(TAG, "服务类名: ${this.javaClass.name}")
        Log.e(TAG, "包名: ${packageName}")
        Log.e(TAG, "========================================")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            Log.w(TAG, "收到空事件")
            return
        }

        Log.d(TAG, "收到事件类型: ${eventTypeToString(event.eventType)}")
        Log.d(TAG, "事件包名: ${event.packageName}")
        Log.d(TAG, "事件类名: ${event.className}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                handleWindowContentChanged(event)
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                handleViewClicked(event)
            }
            else -> {
                // 其他事件忽略
            }
        }
    }

    private fun eventTypeToString(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            else -> "UNKNOWN($eventType)"
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        Log.d(TAG, "========== 窗口状态变化 ==========")
        Log.d(TAG, "包名: $packageName")
        Log.d(TAG, "类名: $className")
        Log.d(TAG, "事件时间: ${System.currentTimeMillis()}")

        if (packageName != "com.tencent.mm") return

        postDelayed({
            val root = rootInActiveWindow
            if (root == null) {
                Log.w(TAG, "rootInActiveWindow 为空，只靠 className 尝试判断")
                val type = WeChatPageDetector.detectPageTypeByClassName(className)
                Log.e(TAG, "📱 页面识别结果（仅className）: ${type.name}")
                return@postDelayed
            }

            val type = WeChatPageDetector.detectPageType(className, root)
            Log.e(TAG, "📱 页面识别结果: ${type.name}（className=${className}）")

            root.recycle()
        }, 300)
    }

    private fun handleViewClicked(event: AccessibilityEvent) {
        if (event.packageName != "com.tencent.mm") return

        val clickedText = event.text?.joinToString("")?.trim()
        if (clickedText.isNullOrEmpty()) return

        val tabNames = listOf("微信", "通讯录", "发现", "我")
        if (clickedText in tabNames) {
            lastClickedTab = clickedText
            Log.d(TAG, "点击了底部Tab: $clickedText")
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        if (event.packageName != "com.tencent.mm") return

        postDelayed({
            val tab = lastClickedTab
            if (tab != null) {
                val type = when (tab) {
                    "微信" -> WeChatPageDetector.PageType.WECHAT_HOME
                    "通讯录" -> WeChatPageDetector.PageType.CONTACTS
                    "发现" -> WeChatPageDetector.PageType.DISCOVER
                    "我" -> WeChatPageDetector.PageType.ME
                    else -> null
                }
                if (type != null) {
                    Log.e(TAG, "📱 CONTENT_CHANGED 推断页面: ${type.name}（上次点击Tab: $tab）")
                    lastClickedTab = null
                    return@postDelayed
                }
            }

            // 兜底：检测是否为聊天详情页（有 EditText 或发送按钮）
            val root = rootInActiveWindow ?: return@postDelayed
            val isChat = checkIsChatPage(root)
            root.recycle()

            if (isChat) {
                Log.e(TAG, "📱 CONTENT_CHANGED 识别为聊天页面: CHAT_DETAIL")
            } else {
                Log.d(TAG, "CONTENT_CHANGED 未识别Tab且无聊天特征，忽略")
            }
        }, 200)
    }

    private fun checkIsChatPage(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var foundEditText = false
        var foundSendButton = false

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val className = node.className?.toString() ?: ""
            if (className.contains("EditText", ignoreCase = true)) {
                foundEditText = true
            }

            val text = node.text?.toString() ?: ""
            if (text.contains("发送", ignoreCase = true)) {
                foundSendButton = true
            }

            val desc = node.contentDescription?.toString() ?: ""
            if (desc.contains("输入框", ignoreCase = true)) {
                foundEditText = true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        return foundEditText || foundSendButton
    }

    private fun postDelayed(action: () -> Unit, delayMillis: Long) {
        android.os.Handler(mainLooper).postDelayed(action, delayMillis)
    }

    override fun onInterrupt() {
        Log.w(TAG, "无障碍服务被中断")
        AccessibilityServiceStateManager.setInstance(null)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.DISCONNECTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "无障碍服务已销毁")
        AccessibilityServiceStateManager.setInstance(null)
        AccessibilityServiceStateManager.updateStatus(AccessibilityServiceStateManager.ServiceStatus.DISABLED)
    }

    fun getRootNodeWithRetry(retryCount: Int = 3): AccessibilityNodeInfo? {
        for (i in 1..retryCount) {
            val root = rootInActiveWindow
            if (root != null) return root
            Log.w(TAG, "第 $i 次尝试获取 root 节点失败")
            Thread.sleep(200)
        }
        Log.e(TAG, "多次尝试后仍无法获取 root 节点")
        return null
    }
}