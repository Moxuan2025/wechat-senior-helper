package com.example.wechat_senior_helper.transaction

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.utils.WeChatPageDetector

/**
 * Result Verifier
 * Responsible for verifying transaction results by comparing page states before and after action
 * 
 * @author moxuan
 */
object ResultVerifier {

    private const val TAG = "ResultVerifier"

    /**
     * Verify if page has changed after executing an action
     * Compares page type before and after the action
     * 
     * @param rootBefore Root node before action
     * @param rootAfter Root node after action
     * @return true if page has changed, false otherwise
     */
    fun verifyPageChanged(rootBefore: AccessibilityNodeInfo?, rootAfter: AccessibilityNodeInfo?): Boolean {
        if (rootBefore == null || rootAfter == null) {
            Log.w(TAG, "验证失败: 节点为空")
            return false
        }

        val pageTypeBefore = WeChatPageDetector.detectPageType(rootBefore)
        val pageTypeAfter = WeChatPageDetector.detectPageType(rootAfter)

        val changed = pageTypeBefore != pageTypeAfter
        
        Log.d(TAG, "========== 页面变化验证 ==========")
        Log.d(TAG, "动作前页面类型: ${pageTypeBefore.name}")
        Log.d(TAG, "动作后页面类型: ${pageTypeAfter.name}")
        Log.d(TAG, "页面是否变化: ${if (changed) "✅ 是" else "❌ 否"}")
        Log.d(TAG, "========== 验证完成 ==========")

        // Recycle nodes
        rootBefore.recycle()
        rootAfter.recycle()

        return changed
    }

    /**
     * Verify if current page matches expected page type
     * 
     * @param root Current root node
     * @param expectedPageType Expected page type
     * @return true if matches expected type
     */
    fun verifyPageType(root: AccessibilityNodeInfo?, expectedPageType: WeChatPageDetector.PageType): Boolean {
        if (root == null) {
            Log.w(TAG, "验证失败: 节点为空")
            return false
        }

        val currentPageType = WeChatPageDetector.detectPageType(root)
        val matches = currentPageType == expectedPageType

        Log.d(TAG, "========== 页面类型验证 ==========")
        Log.d(TAG, "当前页面类型: ${currentPageType.name}")
        Log.d(TAG, "期望页面类型: ${expectedPageType.name}")
        Log.d(TAG, "是否匹配: ${if (matches) "✅ 是" else "❌ 否"}")
        Log.d(TAG, "========== 验证完成 ==========")

        root.recycle()
        return matches
    }

    /**
     * Get verification result description
     * 
     * @param pageChanged Whether page changed
     * @param pageTypeBefore Page type before action
     * @param pageTypeAfter Page type after action
     * @return Human-readable result description
     */
    fun getResultDescription(
        pageChanged: Boolean,
        pageTypeBefore: WeChatPageDetector.PageType,
        pageTypeAfter: WeChatPageDetector.PageType
    ): String {
        return if (pageChanged) {
            "✅ 页面已从 ${pageTypeBefore.name} 变为 ${pageTypeAfter.name}"
        } else {
            "❌ 页面未变化，仍为 ${pageTypeBefore.name}"
        }
    }
}
