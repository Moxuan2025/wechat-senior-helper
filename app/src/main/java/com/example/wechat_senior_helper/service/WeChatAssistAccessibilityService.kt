package com.example.wechat_senior_helper.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.utils.AccessibilityTreeDumper
import com.example.wechat_senior_helper.utils.WeChatPageDetector

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

        // 所有事件都打印（调试用）
        Log.d(TAG, "收到事件类型: ${eventTypeToString(event.eventType)}")
        Log.d(TAG, "事件包名: ${event.packageName}")
        Log.d(TAG, "事件类名: ${event.className}")

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // 可选：监听内容变化，调试时可打开
                // handleWindowContentChanged(event)
            }
            else -> {
                // 其他事件忽略
            }
        }
    }

    /**
     * 将事件类型转换为可读字符串
     */
    private fun eventTypeToString(eventType: Int): String {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "TYPE_WINDOW_STATE_CHANGED"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "TYPE_WINDOW_CONTENT_CHANGED"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "TYPE_VIEW_CLICKED"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "TYPE_VIEW_SCROLLED"
            else -> "UNKNOWN($eventType)"
        }
    }

    /**
     * 处理窗口状态变化事件
     */
    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""
        
        Log.d(TAG, "========== 窗口状态变化 ==========")
        Log.d(TAG, "包名: $packageName")
        Log.d(TAG, "类名: $className")
        Log.d(TAG, "事件时间: ${System.currentTimeMillis()}")

        // 如果是微信窗口，dump 节点树并识别页面
        if (packageName == "com.tencent.mm") {
            Log.d(TAG, "检测到微信窗口！")
            
            // 延迟一下确保窗口完全加载
            postDelayed({
                dumpCurrentWindowTreeAndDetectPage()
            }, 500)
        }
    }

    /**
     * 处理窗口内容变化事件（可选）
     */
    @Suppress("unused")
    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: ""
        if (packageName == "com.tencent.mm") {
            Log.d(TAG, "微信窗口内容发生变化")
        }
    }

    /**
     * Dump 当前窗口的 UI 树到 Logcat
     */
    private fun dumpCurrentWindowTree() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow 为空，稍后重试...")
            // 延迟重试
            postDelayed({
                dumpCurrentWindowTree()
            }, 500)
            return
        }

        // 检查根节点是否有效
        if (root.className == null && root.childCount == 0) {
            Log.w(TAG, "根节点无效（className=null, childCount=0），稍后重试...")
            root.recycle()
            postDelayed({
                dumpCurrentWindowTree()
            }, 300)
            return
        }

        Log.d(TAG, "========== 开始 Dump UI 树 ==========")
        val treeString = AccessibilityTreeDumper.dumpNodeTree(root)
        Log.d(TAG, treeString)
        Log.d(TAG, "========== UI 树 Dump 完成 ==========")
        
        // 记得回收节点
        root.recycle()
    }

    /**
     * Dump UI 树并识别页面类型
     */
    private fun dumpCurrentWindowTreeAndDetectPage() {
        val root = rootInActiveWindow
        if (root == null) {
            Log.w(TAG, "rootInActiveWindow 为空，无法识别页面")
            return
        }

        // 检查根节点是否有效
        if (root.className == null && root.childCount == 0) {
            Log.w(TAG, "根节点无效，稍后重试...")
            root.recycle()
            postDelayed({
                dumpCurrentWindowTreeAndDetectPage()
            }, 300)
            return
        }

        Log.d(TAG, "========== 开始 Dump UI 树 ==========")
        val treeString = AccessibilityTreeDumper.dumpNodeTree(root)
        Log.d(TAG, treeString)
        Log.d(TAG, "========== UI 树 Dump 完成 ==========")

        // 识别页面类型
        val pageType = WeChatPageDetector.detectPageType(root)
        Log.e(TAG, "📱 页面识别结果: ${pageType.name}")

        // 记得回收节点
        root.recycle()
    }

    /**
     * 延迟执行任务
     */
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

    /**
     * 获取根节点（带重试机制）
     */
    fun getRootNodeWithRetry(retryCount: Int = 3): AccessibilityNodeInfo? {
        for (i in 1..retryCount) {
            val root = rootInActiveWindow
            if (root != null) {
                return root
            }
            Log.w(TAG, "第 $i 次尝试获取 root 节点失败")
            Thread.sleep(200)
        }
        Log.e(TAG, "多次尝试后仍无法获取 root 节点")
        return null
    }
}
