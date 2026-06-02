package com.example.wechat_senior_helper.tree

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * WeChat Window Tree Provider
 * Responsible for obtaining stable WeChat accessibility tree from service.windows
 * Ensures the tree is complete before passing to page detector
 * 
 * @author moxuan
 */
class WeChatWindowTreeProvider(
    private val service: AccessibilityService
) {
    companion object {
        private const val TAG = "WeChatWindowTreeProvider"
        private const val WECHAT_PACKAGE = "com.tencent.mm"
    }

    /**
     * Get stable WeChat root node with retry mechanism
     * Should be called in background thread/coroutine
     * 
     * @param maxRetry Maximum retry attempts (default: 6)
     * @param retryDelayMs Delay between retries in milliseconds (default: 120)
     * @param minNodeCount Minimum expected node count to consider tree stable (default: 10)
     * @return Stable WeChat root node, or null if not found after retries
     */
    fun getStableWeChatRoot(
        maxRetry: Int = 6,
        retryDelayMs: Long = 120L,
        minNodeCount: Int = 10
    ): AccessibilityNodeInfo? {
        repeat(maxRetry) { attempt ->
            val root = findWeChatRoot() ?: run {
                Log.w(TAG, "第 ${attempt + 1} 次：未找到微信 root")
                SystemClock.sleep(retryDelayMs)
                return@repeat
            }

            try {
                // Refresh to avoid stale tree
                root.refresh()

                val pkg = root.packageName?.toString().orEmpty()
                val cls = root.className?.toString().orEmpty()
                val childCount = root.childCount
                val nodeCount = AccessibilityTreeInspector.countNodes(root, maxNodes = 600)

                Log.d(
                    TAG,
                    "第 ${attempt + 1} 次：pkg=$pkg class=$cls childCount=$childCount nodeCount=$nodeCount"
                )

                if (pkg == WECHAT_PACKAGE && nodeCount >= minNodeCount) {
                    Log.e(TAG, "✅ 获取到稳定微信树: nodeCount=$nodeCount")
                    return root
                }

                Log.w(TAG, "第 ${attempt + 1} 次：树不稳定或节点过少，准备重试")
            } catch (e: Throwable) {
                Log.e(TAG, "第 ${attempt + 1} 次：检查微信树失败", e)
            }

            root.recycle()
            SystemClock.sleep(retryDelayMs)
        }

        Log.e(TAG, "❌ 经过 $maxRetry 次重试仍未获取到稳定微信树")
        return null
    }

    /**
     * Find WeChat root from service.windows list
     * Prioritizes active/focused windows
     * Falls back to rootInActiveWindow only if package matches
     */
    private fun findWeChatRoot(): AccessibilityNodeInfo? {
        val windows = service.windows ?: emptyList()

        Log.d(TAG, "========== 查找微信窗口 ==========")
        Log.d(TAG, "可用窗口数: ${windows.size}")

        // 打印所有窗口信息，便于诊断
        windows.forEachIndexed { index, window ->
            val root = window.root
            val pkg = root?.packageName?.toString().orEmpty()
            val cls = root?.className?.toString().orEmpty()
            val childCount = root?.childCount ?: -1
            Log.d(
                TAG,
                "窗口[$index]: id=${window.id}, type=${window.type}, layer=${window.layer}, " +
                    "isActive=${window.isActive}, isFocused=${window.isFocused}, pkg=$pkg, className=$cls, childCount=$childCount"
            )
        }

        val candidates = windows
            .mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                val pkg = root.packageName?.toString().orEmpty()
                
                if (pkg == WECHAT_PACKAGE) {
                    Log.d(TAG, "找到候选微信窗口: isActive=${window.isActive}, isFocused=${window.isFocused}, layer=${window.layer}")
                    window to AccessibilityNodeInfo.obtain(root)
                } else {
                    null
                }
            }

        if (candidates.isNotEmpty()) {
            // Prefer windows that actually have children
            val nonEmpty = candidates.filter { (_, node) -> node.childCount > 0 }

            val preferredPair = when {
                nonEmpty.isNotEmpty() -> {
                    nonEmpty.firstOrNull { (window, _) -> window.isActive || window.isFocused }
                        ?: nonEmpty.minByOrNull { it.first.layer }!!
                }
                else -> {
                    // all candidates empty; still prefer active/focused, else lowest layer
                    candidates.firstOrNull { (window, _) -> window.isActive || window.isFocused }
                        ?: candidates.minByOrNull { it.first.layer }!!
                }
            }

            // Recycle non-preferred candidates
            candidates.forEach { (_, node) ->
                if (node !== preferredPair.second) node.recycle()
            }

            val rootNode = preferredPair.second
            val window = preferredPair.first

            Log.d(TAG, "选择窗口: isActive=${window.isActive}, isFocused=${window.isFocused}, layer=${window.layer}, childCount=${rootNode.childCount}")

            // If chosen root has no children, try rootInActiveWindow as a backup
            if (rootNode.childCount == 0) {
                val activeRoot = service.rootInActiveWindow
                if (activeRoot != null) {
                    val activePkg = activeRoot.packageName?.toString().orEmpty()
                    val activeChildCount = activeRoot.childCount
                    Log.d(TAG, "备选 rootInActiveWindow: pkg=$activePkg childCount=$activeChildCount")
                    if (activePkg == WECHAT_PACKAGE && activeChildCount > 0) {
                        rootNode.recycle()
                        return AccessibilityNodeInfo.obtain(activeRoot)
                    }
                }
            }

            Log.d(TAG, "====================================")
            return rootNode
        }

        Log.w(TAG, "windows 列表中未找到微信窗口，尝试 rootInActiveWindow...")

        val activeRoot = service.rootInActiveWindow
        if (activeRoot != null) {
            val pkg = activeRoot.packageName?.toString().orEmpty()
            val childCount = activeRoot.childCount
            Log.d(TAG, "rootInActiveWindow: pkg=$pkg childCount=$childCount")
            if (pkg == WECHAT_PACKAGE) {
                if (childCount > 0) {
                    Log.d(TAG, "rootInActiveWindow 包名匹配且有子节点")
                    Log.d(TAG, "====================================")
                    return AccessibilityNodeInfo.obtain(activeRoot)
                } else {
                    Log.w(TAG, "rootInActiveWindow 包名匹配但 childCount=0")
                }
            } else {
                Log.w(TAG, "rootInActiveWindow 包名不匹配: $pkg")
            }
        }

        Log.w(TAG, "未找到任何微信窗口")
        Log.d(TAG, "====================================")
        return null
    }
}
