package com.example.wechat_senior_helper.transaction

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.wechat_senior_helper.utils.WeChatPageDetector
import kotlinx.coroutines.delay

/**
 * Transaction Scheduler
 * Responsible for orchestrating transaction execution in a state machine pattern
 * Ensures proper sequencing: detect -> identify -> execute -> verify
 * 
 * @author moxuan
 */
object TransactionScheduler {

    private const val TAG = "TransactionScheduler"

    /**
     * Execute the back-once transaction
     * This is the main entry point for the minimum viable transaction prototype
     * 
 * Transaction flow:
     * WAITING → DETECT_WECHAT_WINDOW → DETECT_CHAT_PAGE → EXECUTE_BACK → VERIFY_PAGE_CHANGED → SUCCESS/FAIL
     * 
     * @return Transaction object with final status and result
     */
    suspend fun executeBackOnceTransaction(): Transaction {
        val transaction = Transaction(
            txId = "tx_back_once_${System.currentTimeMillis()}",
            type = TransactionType.BACK_ONCE,
            precondition = "chat_page",
            action = "global_back",
            verify = "page_changed"
        )

        Log.e(TAG, "========================================")
        Log.e(TAG, "🚀 开始执行事务: ${transaction.txId}")
        Log.e(TAG, "事务类型: ${transaction.type.name}")
        Log.e(TAG, "========================================")

        var rootBefore: AccessibilityNodeInfo? = null
        var rootAfter: AccessibilityNodeInfo? = null

        try {
            // Step 1: Detect WeChat window
            transaction.updateStatus(TransactionStatus.DETECT_WECHAT_WINDOW)
            Log.e(TAG, "📍 步骤1: 检测微信窗口...")
            
            rootBefore = WindowDetector.detectWeChatWindow(retryCount = 3, retryDelay = 500)
            if (rootBefore == null) {
                transaction.markFailed("未能检测到微信窗口")
                Log.e(TAG, "❌ 事务失败: ${transaction.reason}")
                return transaction
            }

            // Step 2: Detect chat page
            transaction.updateStatus(TransactionStatus.DETECT_CHAT_PAGE)
            Log.e(TAG, "📍 步骤2: 识别当前页面...")
            
            val pageTypeBefore = WeChatPageDetector.detectPageType(rootBefore)
            Log.e(TAG, "当前页面类型: ${pageTypeBefore.name}")
            
            if (pageTypeBefore == WeChatPageDetector.PageType.UNKNOWN) {
                transaction.markFailed("无法识别当前页面类型")
                Log.e(TAG, "❌ 事务失败: ${transaction.reason}")
                rootBefore.recycle()
                return transaction
            }

            Log.e(TAG, "✅ 页面识别成功: ${pageTypeBefore.name}")

            // Step 3: Execute back action
            transaction.updateStatus(TransactionStatus.EXECUTE_BACK)
            Log.e(TAG, "📍 步骤3: 执行返回动作...")
            
            // Add delay to ensure page is stable
            delay(500)
            
            val actionSuccess = ActionExecutor.executeGlobalBack()
            if (!actionSuccess) {
                transaction.markFailed("返回动作执行失败")
                Log.e(TAG, "❌ 事务失败: ${transaction.reason}")
                rootBefore.recycle()
                return transaction
            }

            Log.e(TAG, "✅ 返回动作执行成功")

            // Wait for page transition
            Log.e(TAG, "⏳ 等待页面切换...")
            delay(1000)

            // Step 4: Verify page changed
            transaction.updateStatus(TransactionStatus.VERIFY_PAGE_CHANGED)
            Log.e(TAG, "📍 步骤4: 验证页面变化...")
            
            rootAfter = WindowDetector.detectWeChatWindow(retryCount = 2, retryDelay = 300)
            if (rootAfter == null) {
                transaction.markFailed("动作后未能检测到微信窗口")
                Log.e(TAG, "❌ 事务失败: ${transaction.reason}")
                rootBefore.recycle()
                return transaction
            }

            val pageChanged = ResultVerifier.verifyPageChanged(rootBefore, rootAfter)
            
            if (pageChanged) {
                transaction.markSuccess()
                Log.e(TAG, "========================================")
                Log.e(TAG, "🎉 事务执行成功!")
                Log.e(TAG, "事务ID: ${transaction.txId}")
                Log.e(TAG, "耗时: ${transaction.duration()}ms")
                Log.e(TAG, "========================================")
            } else {
                transaction.markFailed("页面未发生变化")
                Log.e(TAG, "❌ 事务失败: ${transaction.reason}")
            }

        } catch (e: Exception) {
            transaction.markFailed("事务执行异常: ${e.message}")
            Log.e(TAG, "❌ 事务执行异常", e)
            
            // Clean up resources
            rootBefore?.recycle()
            rootAfter?.recycle()
        }

        return transaction
    }

    /**
     * Get transaction status description for UI display
     * 
     * @param transaction Current transaction
     * @return Human-readable status description
     */
    fun getStatusDescription(transaction: Transaction): String {
        return when (transaction.status) {
            TransactionStatus.WAITING -> "⏸️ 等待执行"
            TransactionStatus.DETECT_WECHAT_WINDOW -> "🔍 检测微信窗口..."
            TransactionStatus.DETECT_CHAT_PAGE -> "🔍 识别页面类型..."
            TransactionStatus.EXECUTE_BACK -> "⬅️ 执行返回动作..."
            TransactionStatus.VERIFY_PAGE_CHANGED -> "✔️ 验证页面变化..."
            TransactionStatus.SUCCESS -> "✅ 事务成功 (${transaction.duration()}ms)"
            TransactionStatus.FAIL -> "❌ 事务失败: ${transaction.reason}"
        }
    }
}
