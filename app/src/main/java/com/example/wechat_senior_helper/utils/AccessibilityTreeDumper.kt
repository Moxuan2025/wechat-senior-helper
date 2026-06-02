package com.example.wechat_senior_helper.utils

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点树调试工具
 * 用于将 UI 树结构输出为可读字符串，方便调试
 */
object AccessibilityTreeDumper {

    private const val TAG = "AccessibilityTreeDumper"

    /**
     * Dump 整个节点树
     * @param root 根节点
     * @return 格式化的树形字符串
     */
    fun dumpNodeTree(root: AccessibilityNodeInfo): String {
        val stringBuilder = StringBuilder()
        dumpNodeRecursive(root, stringBuilder, 0)
        return stringBuilder.toString()
    }

    /**
     * 递归遍历节点树
     */
    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        // 缩进
        val indent = "  ".repeat(depth)
        
        // 节点基本信息
        builder.append(indent).append("Node[\n")
        
        // className
        builder.append(indent).append("  className=").append(node.className ?: "null").append("\n")
        
        // text
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            builder.append(indent).append("  text=\"").append(text).append("\"\n")
        }
        
        // contentDescription
        val contentDesc = node.contentDescription?.toString()
        if (!contentDesc.isNullOrEmpty()) {
            builder.append(indent).append("  contentDescription=\"").append(contentDesc).append("\"\n")
        }
        
        // viewIdResourceName
        val viewId = node.viewIdResourceName
        if (!viewId.isNullOrEmpty()) {
            builder.append(indent).append("  viewIdResourceName=").append(viewId).append("\n")
        }
        
        // clickable
        builder.append(indent).append("  clickable=").append(node.isClickable).append("\n")
        
        // enabled
        builder.append(indent).append("  enabled=").append(node.isEnabled).append("\n")
        
        // focusable
        builder.append(indent).append("  focusable=").append(node.isFocusable).append("\n")
        
        // focused
        builder.append(indent).append("  focused=").append(node.isFocused).append("\n")
        
        // boundsInScreen
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        builder.append(indent).append("  boundsInScreen=[")
            .append(bounds.left).append(", ")
            .append(bounds.top).append(", ")
            .append(bounds.right).append(", ")
            .append(bounds.bottom).append("]\n")
        
        // childCount
        val childCount = node.childCount
        builder.append(indent).append("  childCount=").append(childCount).append("\n")
        
        // 递归处理子节点
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                dumpNodeRecursive(child, builder, depth + 1)
                child.recycle()
            }
        }
        
        builder.append(indent).append("]\n")
    }

    /**
     * 查找具有指定文本的节点（广度优先搜索）
     * @param root 根节点
     * @param targetText 目标文本
     * @return 找到的第一个匹配节点，未找到返回 null
     */
    fun findNodeByText(root: AccessibilityNodeInfo, targetText: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            
            val text = node.text?.toString()
            if (text == targetText) {
                Log.d(TAG, "找到文本匹配的节点: $targetText")
                return node
            }
            
            // 添加子节点到队列
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        
        Log.w(TAG, "未找到文本为 '$targetText' 的节点")
        return null
    }

    /**
     * 查找具有指定 ID 的节点（广度优先搜索）
     * @param root 根节点
     * @param targetId 目标 ID
     * @return 找到的第一个匹配节点，未找到返回 null
     */
    fun findNodeById(root: AccessibilityNodeInfo, targetId: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            
            val viewId = node.viewIdResourceName
            if (viewId == targetId) {
                Log.d(TAG, "找到 ID 匹配的节点: $targetId")
                return node
            }
            
            // 添加子节点到队列
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        
        Log.w(TAG, "未找到 ID 为 '$targetId' 的节点")
        return null
    }

    /**
     * 查找具有指定内容描述的节点（广度优先搜索）
     * @param root 根节点
     * @param targetDesc 目标内容描述
     * @return 找到的第一个匹配节点，未找到返回 null
     */
    fun findNodeByContentDescription(root: AccessibilityNodeInfo, targetDesc: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            
            val contentDesc = node.contentDescription?.toString()
            if (contentDesc == targetDesc) {
                Log.d(TAG, "找到 contentDescription 匹配的节点: $targetDesc")
                return node
            }
            
            // 添加子节点到队列
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }
        
        Log.w(TAG, "未找到 contentDescription 为 '$targetDesc' 的节点")
        return null
    }

    /**
     * 向上查找最近的可点击父节点
     * @param node 起始节点
     * @return 最近的可点击父节点，未找到返回 null
     */
    fun findNearestClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) {
                Log.d(TAG, "找到可点击的父节点: ${parent.className}")
                return parent
            }
            val temp = parent.parent
            parent.recycle() // 回收不需要的父节点引用
            parent = temp
        }
        Log.w(TAG, "未找到可点击的父节点")
        return null
    }
}
