package com.example.wechat_senior_helper.ocr

import android.graphics.Rect

enum class BubbleSide {
    LEFT, RIGHT, UNKNOWN
}

data class OcrLine(
    val text: String,
    val rect: Rect
)

data class OcrHit(
    val text: String,
    val rect: Rect,
    val score: Float
)

data class ChatMessageBlock(
    val text: String,
    val rect: Rect,
    val side: BubbleSide
)
