package com.example.wechat_senior_helper.utils

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * OCR Helper using ML Kit Text Recognition
 * Provides screenshot-based page detection when accessibility tree is empty
 * 
 * @author moxuan
 */
object OcrHelper {
    private const val TAG = "OcrHelper"
    
    // Use Chinese text recognizer for better WeChat OCR
    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

    /**
     * Recognize text from bitmap using ML Kit
     * Must be called from coroutine scope
     * 
     * @param bitmap Screenshot bitmap
     * @return Recognized text, or empty string if failed
     */
    suspend fun recognizeText(bitmap: Bitmap): String {
        return try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            val visionText = recognizer.process(inputImage).await()
            Log.d(TAG, "OCR recognized ${visionText.textBlocks.size} blocks")
            visionText.text
        } catch (e: Exception) {
            Log.e(TAG, "OCR recognition failed", e)
            ""
        }
    }

    /**
     * Classify WeChat page type based on OCR text
     * 
     * @param ocrText Recognized text from screenshot
     * @return Page type description, or null if cannot determine
     */
    fun classifyWeChatPage(ocrText: String): String? {
        if (ocrText.isBlank()) return null

        Log.d(TAG, "Classifying page with OCR text length: ${ocrText.length}")

        return when {
            // Chat conversation page indicators
            isChatConversationPage(ocrText) -> "CHAT_CONVERSATION"
            
            // Session list page indicators
            isSessionListPage(ocrText) -> "SESSION_LIST"
            
            // Contacts page indicators
            isContactsPage(ocrText) -> "CONTACTS"
            
            // Discover page indicators
            isDiscoverPage(ocrText) -> "DISCOVER"
            
            // Me page indicators
            isMePage(ocrText) -> "ME"
            
            else -> {
                Log.d(TAG, "Cannot classify page, text preview: ${ocrText.take(100)}")
                null
            }
        }
    }

    /**
     * Check if text indicates chat conversation page
     */
    private fun isChatConversationPage(text: String): Boolean {
        // Common indicators: time stamps, message bubbles
        val hasTimeIndicator = text.contains("上午") || text.contains("下午") || 
                              text.contains("刚刚") || text.contains("昨天")
        val hasShortMessages = text.lines().any { it.length < 50 && it.isNotBlank() }
        
        return hasTimeIndicator && hasShortMessages
    }

    /**
     * Check if text indicates session list page
     */
    private fun isSessionListPage(text: String): Boolean {
        // Common indicators: "聊天", "群聊", "新的朋友", timestamps like "12:30"
        return text.contains("聊天") || 
               text.contains("群聊") || 
               text.contains("新的朋友") ||
               Regex("\\d{1,2}:\\d{2}").containsMatchIn(text)
    }

    /**
     * Check if text indicates contacts page
     */
    private fun isContactsPage(text: String): Boolean {
        return text.contains("通讯录") || 
               text.contains("新的朋友") || 
               text.contains("群聊") ||
               text.contains("标签") ||
               text.contains("公众号")
    }

    /**
     * Check if text indicates discover page
     */
    private fun isDiscoverPage(text: String): Boolean {
        return text.contains("朋友圈") || 
               text.contains("视频号") || 
               text.contains("扫一扫") ||
               text.contains("搜一搜")
    }

    /**
     * Check if text indicates me page
     */
    private fun isMePage(text: String): Boolean {
        return text.contains("服务") || 
               text.contains("收藏") || 
               text.contains("朋友圈") ||
               text.contains("设置")
    }
}
