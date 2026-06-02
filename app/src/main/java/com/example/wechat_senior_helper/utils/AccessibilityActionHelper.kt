package com.example.wechat_senior_helper.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.service.WeChatAssistAccessibilityService

/**
 * 无障碍基础动作辅助类
 * 提供点击、返回、输入等常用操作的封装
 */
object AccessibilityActionHelper {

    private const val TAG = "AccessibilityAction"

    /**
     * 执行全局返回操作
     * @return 是否成功执行
     */
    fun performGlobalBack(): Boolean {
        val service = WeChatAssistAccessibilityService.instance
        if (service == null) {
            Log.e(TAG, "无障碍服务未启动")
            return false
        }

        val result = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        Log.d(TAG, "全局返回操作: ${if (result) "成功" else "失败"}")
        return result
    }

    /**
     * 点击指定节点
     * @param node 要点击的节点
     * @return 是否成功点击
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isClickable) {
            Log.w(TAG, "节点不可点击，尝试查找可点击的父节点")
            val clickableParent = AccessibilityTreeDumper.findNearestClickableParent(node)
            if (clickableParent != null) {
                val result = clickableParent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                clickableParent.recycle()
                Log.d(TAG, "点击父节点: ${if (result) "成功" else "失败"}")
                return result
            }
            Log.e(TAG, "未找到可点击的父节点")
            return false
        }

        val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Log.d(TAG, "点击节点: ${if (result) "成功" else "失败"}")
        return result
    }

    /**
     * 通过文本查找并点击节点
     * @param root 根节点
     * @param text 目标文本
     * @return 是否成功点击
     */
    fun clickNodeByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val targetNode = AccessibilityTreeDumper.findNodeByText(root, text)
        if (targetNode == null) {
            Log.e(TAG, "未找到文本为 '$text' 的节点")
            return false
        }

        val result = clickNode(targetNode)
        targetNode.recycle()
        return result
    }

    /**
     * 通过 ID 查找并点击节点
     * @param root 根节点
     * @param viewId 目标 ID
     * @return 是否成功点击
     */
    fun clickNodeById(root: AccessibilityNodeInfo, viewId: String): Boolean {
        val targetNode = AccessibilityTreeDumper.findNodeById(root, viewId)
        if (targetNode == null) {
            Log.e(TAG, "未找到 ID 为 '$viewId' 的节点")
            return false
        }

        val result = clickNode(targetNode)
        targetNode.recycle()
        return result
    }

    /**
     * 通过内容描述查找并点击节点
     * @param root 根节点
     * @param contentDesc 目标内容描述
     * @return 是否成功点击
     */
    fun clickNodeByContentDescription(root: AccessibilityNodeInfo, contentDesc: String): Boolean {
        val targetNode = AccessibilityTreeDumper.findNodeByContentDescription(root, contentDesc)
        if (targetNode == null) {
            Log.e(TAG, "未找到 contentDescription 为 '$contentDesc' 的节点")
            return false
        }

        val result = clickNode(targetNode)
        targetNode.recycle()
        return result
    }

    /**
     * 向指定节点输入文本（优先使用 ACTION_SET_TEXT）
     * @param node 目标输入框节点
     * @param text 要输入的文本
     * @return 是否成功输入
     */
    fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        // 方法1: 尝试使用 ACTION_SET_TEXT (Android 8.0+)
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        
        var result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        
        if (result) {
            Log.d(TAG, "使用 ACTION_SET_TEXT 输入成功")
            return true
        }

        Log.w(TAG, "ACTION_SET_TEXT 失败，尝试剪贴板粘贴方式")
        
        // 方法2: 剪贴板粘贴方式
        result = inputTextByPaste(node, text)
        if (result) {
            Log.d(TAG, "使用剪贴板粘贴输入成功")
            return true
        }

        Log.e(TAG, "所有输入方式均失败")
        return false
    }

    /**
     * 通过文本查找节点并输入
     * @param root 根节点
     * @param hintText 输入框的提示文本或标识
     * @param text 要输入的文本
     * @return 是否成功输入
     */
    fun inputTextByHint(root: AccessibilityNodeInfo, hintText: String, text: String): Boolean {
        val targetNode = AccessibilityTreeDumper.findNodeByText(root, hintText)
        if (targetNode == null) {
            Log.e(TAG, "未找到提示文本为 '$hintText' 的输入框")
            return false
        }

        val result = inputText(targetNode, text)
        targetNode.recycle()
        return result
    }

    /**
     * 通过 ID 查找节点并输入
     * @param root 根节点
     * @param viewId 输入框的 ID
     * @param text 要输入的文本
     * @return 是否成功输入
     */
    fun inputTextById(root: AccessibilityNodeInfo, viewId: String, text: String): Boolean {
        val targetNode = AccessibilityTreeDumper.findNodeById(root, viewId)
        if (targetNode == null) {
            Log.e(TAG, "未找到 ID 为 '$viewId' 的输入框")
            return false
        }

        val result = inputText(targetNode, text)
        targetNode.recycle()
        return result
    }

    /**
     * 使用剪贴板粘贴方式输入文本（fallback 方案）
     * @param node 目标节点
     * @param text 要输入的文本
     * @return 是否成功
     */
    private fun inputTextByPaste(node: AccessibilityNodeInfo, text: String): Boolean {
        val service = WeChatAssistAccessibilityService.instance ?: run {
            Log.e(TAG, "无障碍服务未启动")
            return false
        }

        try {
            // 1. 复制到剪贴板
            val clipboard = service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("input_text", text)
            clipboard.setPrimaryClip(clip)

            // 2. 聚焦到目标节点
            val focusResult = node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (!focusResult) {
                Log.w(TAG, "节点聚焦失败")
                return false
            }

            // 3. 执行粘贴
            Thread.sleep(100) // 短暂延迟确保聚焦完成
            val pasteResult = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            
            Log.d(TAG, "剪贴板粘贴: 聚焦=${focusResult}, 粘贴=${pasteResult}")
            return pasteResult

        } catch (e: Exception) {
            Log.e(TAG, "剪贴板输入异常: ${e.message}", e)
            return false
        }
    }

    /**
     * 滚动节点（向下）
     * @param node 要滚动的节点
     * @return 是否成功
     */
    fun scrollDown(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        Log.d(TAG, "向下滚动: ${if (result) "成功" else "失败"}")
        return result
    }

    /**
     * 滚动节点（向上）
     * @param node 要滚动的节点
     * @return 是否成功
     */
    fun scrollUp(node: AccessibilityNodeInfo): Boolean {
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        Log.d(TAG, "向上滚动: ${if (result) "成功" else "失败"}")
        return result
    }
}
