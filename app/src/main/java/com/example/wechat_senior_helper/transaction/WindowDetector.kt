package com.example.wechat_senior_helper.transaction

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.service.WeChatAssistAccessibilityService

/**
 * Window Detector
 * Responsible for finding WeChat root window from system windows
 * 
 * @author moxuan
 */
object WindowDetector {

    private const val TAG = "WindowDetector"
    private const val WECHAT_PACKAGE = "com.tencent.mm"

    /**
     * Get WeChat root window with retry mechanism
     * 
     * @param retryCount Number of retries (default: 3)
     * @param retryDelay Delay between retries in milliseconds (default: 500)
     * @return WeChat root node, or null if not found
     */
    fun detectWeChatWindow(retryCount: Int = 3, retryDelay: Long = 500): AccessibilityNodeInfo? {
        Log.d(TAG, "========== 开始检测微信窗口 ==========")
        
        for (i in 1..retryCount) {
            Log.d(TAG, "第 $i 次尝试获取微信窗口...")
            
            val service = WeChatAssistAccessibilityService.instance
            if (service == null) {
                Log.w(TAG, "无障碍服务未启动")
                return null
            }
            
            val root = service.rootInActiveWindow
            if (root != null) {
                val packageName = root.packageName?.toString() ?: ""
                
                if (packageName == WECHAT_PACKAGE) {
                    Log.d(TAG, "✅ 成功检测到微信窗口")
                    Log.d(TAG, "包名: $packageName")
                    Log.d(TAG, "类名: ${root.className}")
                    Log.d(TAG, "子节点数: ${root.childCount}")
                    Log.d(TAG, "========== 窗口检测完成 ==========")
                    return root
                } else {
                    Log.d(TAG, "当前窗口不是微信: $packageName")
                    root.recycle()
                }
            } else {
                Log.w(TAG, "rootInActiveWindow 为空")
            }
            
            if (i < retryCount) {
                Log.d(TAG, "等待 ${retryDelay}ms 后重试...")
                Thread.sleep(retryDelay)
            }
        }
        
        Log.e(TAG, "❌ 多次尝试后仍未检测到微信窗口")
        Log.d(TAG, "========== 窗口检测失败 ==========")
        return null
    }

    /**
     * Check if WeChat window is currently active (quick check without retry)
     * 
     * @return true if WeChat window is active
     */
    fun isWeChatWindowActive(): Boolean {
        val service = WeChatAssistAccessibilityService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        
        val packageName = root.packageName?.toString() ?: ""
        root.recycle()
        
        return packageName == WECHAT_PACKAGE
    }
}
