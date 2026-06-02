package com.example.wechat_senior_helper.tree

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Accessibility Tree Inspector
 * Provides utilities for inspecting and debugging accessibility node trees
 * 
 * @author moxuan
 */
object AccessibilityTreeInspector {
    private const val TAG = "AccessibilityTreeInspector"

    /**
     * Count total nodes in tree (BFS traversal)
     * 
     * @param root Root node to count from
     * @param maxNodes Maximum nodes to traverse (prevents infinite loops)
     * @return Number of nodes counted
     */
    fun countNodes(root: AccessibilityNodeInfo, maxNodes: Int = 1000): Int {
        var count = 0
        val queue: ArrayDeque<AccessibilityNodeInfo> = ArrayDeque()
        queue.add(AccessibilityNodeInfo.obtain(root))

        while (queue.isNotEmpty() && count < maxNodes) {
            val node = queue.removeFirst()
            count++

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }

            node.recycle()
        }

        return count
    }

    /**
     * Dump tree structure to log for debugging
     * Shows first few levels with node details
     * 
     * @param root Root node to dump
     * @param maxDepth Maximum depth to traverse (default: 4)
     * @param maxNodes Maximum nodes to visit (default: 200)
     */
    fun dumpTree(root: AccessibilityNodeInfo, maxDepth: Int = 4, maxNodes: Int = 200) {
        Log.d(TAG, "========== 开始 Dump 树 ==========")

        val queue: ArrayDeque<Pair<AccessibilityNodeInfo, Int>> = ArrayDeque()
        queue.add(AccessibilityNodeInfo.obtain(root) to 0)

        var visited = 0

        while (queue.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = queue.removeFirst()
            visited++

            val indent = "  ".repeat(depth.coerceAtMost(10))
            Log.d(
                TAG,
                buildString {
                    append(indent)
                    append("#")
                    append(visited)
                    append(" depth=")
                    append(depth)
                    append(" class=")
                    append(node.className?.toString().orEmpty())
                    append(" text=")
                    append(node.text?.toString().orEmpty())
                    append(" desc=")
                    append(node.contentDescription?.toString().orEmpty())
                    append(" viewId=")
                    append(node.viewIdResourceName?.orEmpty())
                    append(" clickable=")
                    append(node.isClickable)
                    append(" editable=")
                    append(node.isEditable)
                    append(" childCount=")
                    append(node.childCount)
                }
            )

            if (depth < maxDepth) {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { child ->
                        queue.add(child to (depth + 1))
                    }
                }
            }

            node.recycle()
        }

        Log.d(TAG, "========== Dump 结束，visited=$visited ==========")
    }

    /**
     * Log basic root node information for diagnosis
     * 
     * @param root Root node to inspect
     */
    fun logRootBasics(root: AccessibilityNodeInfo) {
        Log.d(TAG, "========== Root 诊断 ==========")
        Log.d(TAG, "packageName=${root.packageName}")
        Log.d(TAG, "className=${root.className}")
        Log.d(TAG, "text=${root.text}")
        Log.d(TAG, "contentDescription=${root.contentDescription}")
        Log.d(TAG, "viewIdResourceName=${root.viewIdResourceName}")
        Log.d(TAG, "childCount=${root.childCount}")
        Log.d(TAG, "clickable=${root.isClickable}")
        Log.d(TAG, "editable=${root.isEditable}")
        Log.d(TAG, "================================")
    }
}
