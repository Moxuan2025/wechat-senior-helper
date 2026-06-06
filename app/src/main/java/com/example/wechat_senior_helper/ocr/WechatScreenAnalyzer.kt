package com.example.wechat_senior_helper.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

class WechatScreenAnalyzer(
    private val ocrEngine: MlKitOcrEngine
) {
    suspend fun findContactHit(fullScreen: Bitmap, targetName: String): OcrHit? = withContext(Dispatchers.Default) {
        val cropped = ScreenCropper.cropSearchResultArea(fullScreen)
        val text = ocrEngine.recognize(cropped, preferChinese = true)
        val hit = OcrLineMatcher.findBestContactLine(text, targetName) ?: return@withContext null

        val offsetTop = (fullScreen.height * 0.16f).toInt()
        OcrHit(
            text = hit.text,
            rect = Rect(
                hit.rect.left,
                hit.rect.top + offsetTop,
                hit.rect.right,
                hit.rect.bottom + offsetTop
            ),
            score = hit.score
        )
    }

    suspend fun parseVisibleChatBlocks(fullScreen: Bitmap): List<ChatMessageBlock> = withContext(Dispatchers.Default) {
        val cropped = ScreenCropper.cropChatMessageArea(fullScreen)
        val text = ocrEngine.recognize(cropped, preferChinese = true)
        val lines = OcrLineMatcher.extractLines(text)

        val grouped = mutableListOf<MutableList<OcrLine>>()
        val threshold = 24

        for (line in lines) {
            val last = grouped.lastOrNull()
            if (last == null) {
                grouped += mutableListOf(line)
                continue
            }
            val prev = last.last()
            if (abs(prev.rect.centerY() - line.rect.centerY()) <= threshold) {
                last += line
            } else {
                grouped += mutableListOf(line)
            }
        }

        val offsetTop = (fullScreen.height * 0.14f).toInt()
        grouped.mapNotNull { group ->
            val rect = mergeRects(group.map { it.rect })
            val rawText = group.joinToString("") { it.text }
            val cleaned = cleanChatText(rawText)
            if (cleaned.isBlank()) return@mapNotNull null
            if (isNoise(cleaned)) return@mapNotNull null

            val side = when {
                rect.centerX() < fullScreen.width / 2 -> BubbleSide.LEFT
                rect.centerX() > fullScreen.width / 2 -> BubbleSide.RIGHT
                else -> BubbleSide.UNKNOWN
            }

            ChatMessageBlock(
                text = cleaned,
                rect = Rect(
                    rect.left,
                    rect.top + offsetTop,
                    rect.right,
                    rect.bottom + offsetTop
                ),
                side = side
            )
        }
    }

    private fun mergeRects(rects: List<Rect>): Rect {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (r in rects) {
            left = minOf(left, r.left)
            top = minOf(top, r.top)
            right = maxOf(right, r.right)
            bottom = maxOf(bottom, r.bottom)
        }

        return Rect(left, top, right, bottom)
    }

    private fun cleanChatText(text: String): String {
        return text.trim()
            .replace("\n", "")
            .replace(" ", "")
            .replace("：", ":")
    }

    private fun isNoise(text: String): Boolean {
        val timePattern = Regex("""^\d{1,2}:\d{2}$""")
        val datePattern = Regex("""^(今天|昨天|前天|周[一二三四五六日天]|星期[一二三四五六日天])""")
        return timePattern.matches(text) ||
                datePattern.containsMatchIn(text) ||
                text == "以下为新消息"
    }
}
