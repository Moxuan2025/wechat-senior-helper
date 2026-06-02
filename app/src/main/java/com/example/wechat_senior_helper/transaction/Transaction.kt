package com.example.wechat_senior_helper.transaction

/**
 * Transaction status enum
 * Represents the lifecycle states of a transaction
 * 
 * @author moxuan
 */
enum class TransactionStatus {
    WAITING,              // Waiting to start
    DETECT_WECHAT_WINDOW, // Detecting WeChat window
    DETECT_CHAT_PAGE,     // Detecting chat page
    EXECUTE_BACK,         // Executing back action
    VERIFY_PAGE_CHANGED,  // Verifying page change
    SUCCESS,              // Transaction completed successfully
    FAIL                  // Transaction failed
}

/**
 * Transaction type enum
 */
enum class TransactionType {
    BACK_ONCE,            // Single back action
    FIND_CONTACT,         // Find contact (future)
    SEND_MESSAGE          // Send message (future)
}

/**
 * Transaction data model
 * Represents a single atomic operation with precondition, action, and verification
 * 
 * @author moxuan
 */
data class Transaction(
    val txId: String,
    val type: TransactionType,
    val targetApp: String = "com.tencent.mm",
    val precondition: String,
    val action: String,
    val verify: String,
    var status: TransactionStatus = TransactionStatus.WAITING,
    var reason: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null
) {
    fun markSuccess() {
        status = TransactionStatus.SUCCESS
        completedAt = System.currentTimeMillis()
    }
    
    fun markFailed(reason: String) {
        status = TransactionStatus.FAIL
        this.reason = reason
        completedAt = System.currentTimeMillis()
    }
    
    fun updateStatus(newStatus: TransactionStatus) {
        status = newStatus
    }
    
    fun isCompleted(): Boolean {
        return status == TransactionStatus.SUCCESS || status == TransactionStatus.FAIL
    }
    
    fun duration(): Long {
        return (completedAt ?: System.currentTimeMillis()) - createdAt
    }
}
